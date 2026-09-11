package io.github.vinitthummar.peppollab.adapters;

import com.helger.phase4.CAS4;
import com.helger.phase4.attachment.AS4OutgoingAttachment;
import com.helger.phase4.ebms3header.Ebms3Error;
import com.helger.phase4.ebms3header.Ebms3SignalMessage;
import com.helger.phase4.model.MessageProperty;
import com.helger.phase4.model.pmode.PMode;
import com.helger.phase4.profile.peppol.AS4PeppolProfileRegistarSPI;
import com.helger.phase4.profile.peppol.PeppolPMode;
import com.helger.phase4.sender.AS4Sender;
import com.helger.phase4.sender.EAS4UserMessageSendResult;
import com.helger.phase4.sender.IAS4SignalMessageValidationResultHandler;
import com.helger.phase4.util.Phase4Exception;
import io.github.vinitthummar.peppollab.api.AdapterContext;
import io.github.vinitthummar.peppollab.api.AdapterException;
import io.github.vinitthummar.peppollab.api.AdapterRequest;
import io.github.vinitthummar.peppollab.api.AdapterResult;
import io.github.vinitthummar.peppollab.api.Capability;
import io.github.vinitthummar.peppollab.api.Evidence;
import io.github.vinitthummar.peppollab.api.TargetAdapter;
import io.github.vinitthummar.peppollab.api.TargetConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Sends signed and encrypted Peppol-profiled AS4 messages and strictly verifies their receipts. */
public final class DirectAs4Adapter implements TargetAdapter {
  private static final String PROCESS_SCHEME = "cenbii-procid-ubl";
  private static final String PARTICIPANT_SCHEME = "iso6523-actorid-upis";
  private final HttpClient client = HttpClient.newHttpClient();

  @Override
  public String id() {
    return "direct-as4";
  }

  @Override
  public Set<Capability> capabilities(TargetConfig target) {
    return Set.of(Capability.AS4_SEND, Capability.EVIDENCE);
  }

  @Override
  public AdapterResult execute(TargetConfig target, AdapterRequest request, AdapterContext context)
      throws AdapterException {
    if (!"as4.send".equals(request.action())) {
      return AdapterResult.skipped("Unsupported AS4 action: " + request.action());
    }

    String path = AdapterSupport.parameter(request.parameters(), "path", "/as4/accept");
    URI endpoint = AdapterSupport.resolve(target, path);
    if (path.contains("mode=error")) return sendControlledFailureProbe(endpoint, request.timeout());

    String messageId = AdapterSupport.parameter(
        request.parameters(), "messageId", UUID.randomUUID() + "@interop-lab");
    String sender = AdapterSupport.parameter(request.parameters(), "sender", "POP000001");
    String receiver = AdapterSupport.parameter(request.parameters(), "receiver", "POP000002");
    String documentType = AdapterSupport.parameter(
        request.parameters(),
        "documentType",
        "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2");
    String process = AdapterSupport.parameter(
        request.parameters(),
        "process",
        "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0");
    String originalSender = AdapterSupport.parameter(
        request.parameters(), "originalSender", "9915:sender");
    String finalRecipient = AdapterSupport.parameter(
        request.parameters(), "finalRecipient", "9915:receiver");
    String payload = AdapterSupport.parameter(
        request.parameters(),
        "payload",
        "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"/>");

    String keyStoreReference = setting(
        target, context, "senderKeyStore", "fixture:pki-sender");
    String passwordReference = setting(
        target, context, "senderKeyPassword", "fixture:pki-password");
    String trustReference = setting(
        target, context, "trustCertificate", "fixture:pki-ca");
    String receiverCertificateReference = setting(
        target, context, "receiverCertificate", "fixture:pki-receiver-cert");
    String keyAlias = target.options().getOrDefault("senderKeyAlias", "sender");

    char[] password = AdapterSupport.secret(passwordReference).toCharArray();
    Instant started = Instant.now();
    try (Phase4GlobalScope ignored = Phase4GlobalScope.open();
         EphemeralPhase4CryptoFactory cryptoFactory = EphemeralPhase4CryptoFactory.load(
             path(keyStoreReference), keyAlias, password, certificate(path(trustReference)))) {
      X509Certificate receiverCertificate = certificate(path(receiverCertificateReference));
      PMode pmode = PeppolPMode.createPeppolPMode(
          sender,
          receiver,
          endpoint.toString(),
          AS4PeppolProfileRegistarSPI.PMODE_ID_PROVIDER,
          false);
      StrictReceiptValidation receiptValidation = new StrictReceiptValidation();
      AtomicReference<Ebms3SignalMessage> signalMessage = new AtomicReference<>();
      AtomicReference<Phase4Exception> sendingException = new AtomicReference<>();

      EAS4UserMessageSendResult sendResult = AS4Sender.builderUserMessage()
          .as4ProfileID(AS4PeppolProfileRegistarSPI.AS4_PROFILE_ID)
          .pmode(pmode)
          .cryptoFactory(cryptoFactory)
          .receiverCertificate(receiverCertificate)
          .endpointURL(endpoint.toString())
          .messageID(messageId)
          .conversationID(UUID.randomUUID().toString())
          .fromPartyIDType(PeppolPMode.DEFAULT_PARTY_TYPE_ID)
          .fromPartyID(sender)
          .fromRole(CAS4.DEFAULT_INITIATOR_URL)
          .toPartyIDType(PeppolPMode.DEFAULT_PARTY_TYPE_ID)
          .toPartyID(receiver)
          .toRole(CAS4.DEFAULT_RESPONDER_URL)
          .agreementRef(PeppolPMode.DEFAULT_AGREEMENT_ID)
          .service(PROCESS_SCHEME, process)
          .action(documentType)
          .addMessageProperty(MessageProperty.builder()
              .name(CAS4.ORIGINAL_SENDER)
              .type(PARTICIPANT_SCHEME)
              .value(originalSender))
          .addMessageProperty(MessageProperty.builder()
              .name(CAS4.FINAL_RECIPIENT)
              .type(PARTICIPANT_SCHEME)
              .value(finalRecipient))
          .payload(AS4OutgoingAttachment.builder()
              .data(payload.getBytes(StandardCharsets.UTF_8))
              .mimeTypeXML()
              .compressionGZIP()
              .contentID("payload@interop-lab"))
          .signalMsgConsumer((signal, metadata, state) -> signalMessage.set(signal))
          .signalMsgValidationResultHdl(receiptValidation)
          .sendMessageAndCheckForReceipt(sendingException::set);

      return result(
          sendResult,
          sendingException.get(),
          receiptValidation,
          signalMessage.get(),
          messageId,
          started);
    } catch (IOException | GeneralSecurityException | RuntimeException ex) {
      throw new AdapterException("AS4 cryptographic exchange failed: " + ex.getMessage(), ex, true);
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private AdapterResult sendControlledFailureProbe(URI endpoint, Duration timeout)
      throws AdapterException {
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .timeout(timeout)
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
    return AdapterSupport.send(client, request);
  }

  private static AdapterResult result(
      EAS4UserMessageSendResult sendResult,
      Phase4Exception sendingException,
      StrictReceiptValidation validation,
      Ebms3SignalMessage signal,
      String messageId,
      Instant started) {
    Duration duration = Duration.between(started, Instant.now());
    if (sendResult.isSuccess() && signal != null) {
      String refToMessageId = signal.getMessageInfo().getRefToMessageId();
      if (!messageId.equals(refToMessageId)) {
        validation.onError("Receipt RefToMessageId does not match the transmitted MessageId");
      }
      if (validation.isValid()) {
        String receiptId = signal.getMessageInfo().getMessageId();
        return new AdapterResult(
            "SUCCESS",
            200,
            "SignalMessage Receipt cryptographically-verified messageId=" + receiptId
                + " refToMessageId=" + refToMessageId,
            Map.of(),
            duration,
            List.of(new Evidence("as4.exchange", Instant.now(), Map.of(
                "messageId", messageId,
                "receiptMessageId", receiptId,
                "refToMessageId", refToMessageId,
                "profile", "peppol-as4-2.0.3",
                "signed", true,
                "encrypted", true,
                "receiptReferencesVerified", true))));
      }
    }

    List<String> ebmsErrors = signalErrors(signal);
    List<String> failures = new ArrayList<>(ebmsErrors);
    failures.addAll(validation.errors());
    if (sendingException != null) failures.add(describe(sendingException));
    if (failures.isEmpty()) failures.add("phase4 result: " + sendResult.getID());
    return new AdapterResult(
        "AS4_ERROR",
        null,
        String.join("; ", failures),
        Map.of(),
        duration,
        List.of(new Evidence("as4.exchange", Instant.now(), Map.of(
            "messageId", messageId,
            "phase4Result", sendResult.getID(),
            "ebmsErrors", ebmsErrors,
            "receiptReferencesVerified", false))));
  }

  private static List<String> signalErrors(Ebms3SignalMessage signal) {
    if (signal == null) return List.of();
    return signal.getError().stream().map(DirectAs4Adapter::describe).toList();
  }

  private static String describe(Ebms3Error error) {
    List<String> details = new ArrayList<>();
    if (error.getErrorCode() != null) details.add(error.getErrorCode());
    if (error.getShortDescription() != null) details.add(error.getShortDescription());
    if (error.getDescriptionValue() != null) details.add(error.getDescriptionValue());
    if (error.getErrorDetail() != null) details.add(error.getErrorDetail());
    return details.isEmpty() ? "Unspecified ebMS error" : String.join(": ", details);
  }

  private static String setting(
      TargetConfig target, AdapterContext context, String optionName, String fixtureName)
      throws AdapterException {
    String reference = target.options().getOrDefault(optionName, fixtureName);
    String value = context.runtimeValues().getOrDefault(reference, reference);
    if (reference.startsWith("fixture:") && reference.equals(value)) {
      throw new AdapterException("AS4 fixture value is unavailable: " + reference, true);
    }
    if (value == null || value.isBlank()) {
      throw new AdapterException("Missing direct AS4 setting '" + optionName + "'", true);
    }
    return value;
  }

  private static Path path(String reference) throws AdapterException {
    try {
      return reference.startsWith("file:") ? Path.of(URI.create(reference)) : Path.of(reference);
    } catch (RuntimeException ex) {
      throw new AdapterException("Invalid AS4 file reference", ex, true);
    }
  }

  private static X509Certificate certificate(Path path)
      throws IOException, GeneralSecurityException {
    try (InputStream input = java.nio.file.Files.newInputStream(path)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509")
          .generateCertificate(input);
    }
  }

  private static String describe(Throwable throwable) {
    List<String> messages = new ArrayList<>();
    Throwable current = throwable;
    while (current != null && messages.size() < 6) {
      String message = current.getMessage();
      messages.add(current.getClass().getSimpleName()
          + (message == null || message.isBlank() ? "" : ": " + message));
      current = current.getCause();
    }
    return String.join(" -> ", messages);
  }

  private static final class StrictReceiptValidation
      implements IAS4SignalMessageValidationResultHandler {
    private final List<String> errors = new ArrayList<>();
    private boolean referencesVerified;

    @Override
    public void onSuccess() {
      referencesVerified = true;
    }

    @Override
    public void onError(String errorMessage) {
      errors.add(errorMessage);
    }

    @Override
    public void onNotApplicable() {
      errors.add("Receipt did not contain verifiable signature references");
    }

    private boolean isValid() {
      return referencesVerified && errors.isEmpty();
    }

    private List<String> errors() {
      return List.copyOf(errors);
    }
  }
}
