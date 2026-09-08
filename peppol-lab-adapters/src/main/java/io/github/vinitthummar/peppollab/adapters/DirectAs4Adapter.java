package io.github.vinitthummar.peppollab.adapters;

import com.helger.phase4.profile.peppol.AS4PeppolProfileRegistarSPI;
import com.helger.phase4.profile.peppol.PeppolPMode;
import io.github.vinitthummar.peppollab.api.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Wire-level AS4 diagnostic adapter. It uses phase4's Peppol profile constants. Cryptographic
 * signing/encryption is intentionally capability-gated for the next stage.
 */
public final class DirectAs4Adapter implements TargetAdapter {
  private final HttpClient client = HttpClient.newHttpClient();

  @Override public String id() { return "direct-as4"; }

  @Override public Set<Capability> capabilities(TargetConfig target) {
    return Set.of(Capability.AS4_SEND, Capability.EVIDENCE);
  }

  @Override
  public AdapterResult execute(TargetConfig target, AdapterRequest request, AdapterContext context)
      throws AdapterException {
    if (!"as4.send".equals(request.action())) return AdapterResult.skipped("Unsupported AS4 action: " + request.action());
    String messageId = AdapterSupport.parameter(request.parameters(), "messageId", UUID.randomUUID() + "@interop-lab");
    String sender = AdapterSupport.parameter(request.parameters(), "sender", "POP000001");
    String receiver = AdapterSupport.parameter(request.parameters(), "receiver", "POP000002");
    String documentType = AdapterSupport.parameter(request.parameters(), "documentType", "invoice");
    String process = AdapterSupport.parameter(request.parameters(), "process", "billing");
    String payload = AdapterSupport.parameter(request.parameters(), "payload", "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"/>");
    String boundary = "----peppol-interop-" + UUID.randomUUID();
    String body = multipart(boundary, messageId, sender, receiver, documentType, process, payload);
    String path = AdapterSupport.parameter(request.parameters(), "path", "/as4/accept");
    HttpRequest httpRequest = HttpRequest.newBuilder(AdapterSupport.resolve(target, path))
        .timeout(request.timeout())
        .header("Content-Type", "multipart/related; type=\"application/soap+xml\"; boundary=\"" + boundary + "\"")
        .header("X-Peppol-Message-Id", messageId)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    return AdapterSupport.send(client, httpRequest);
  }

  private static String multipart(String boundary, String messageId, String sender, String receiver,
      String documentType, String process, String payload) {
    String soap = """
        <S12:Envelope xmlns:S12="http://www.w3.org/2003/05/soap-envelope" xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
          <S12:Header><eb:Messaging><eb:UserMessage>
            <eb:MessageInfo><eb:Timestamp>%s</eb:Timestamp><eb:MessageId>%s</eb:MessageId></eb:MessageInfo>
            <eb:PartyInfo><eb:From><eb:PartyId type="%s">%s</eb:PartyId></eb:From><eb:To><eb:PartyId type="%s">%s</eb:PartyId></eb:To></eb:PartyInfo>
            <eb:CollaborationInfo><eb:AgreementRef>%s</eb:AgreementRef><eb:Service type="process">%s</eb:Service><eb:Action>%s</eb:Action></eb:CollaborationInfo>
            <eb:MessageProperties><eb:Property name="profile">%s</eb:Property></eb:MessageProperties>
            <eb:PayloadInfo><eb:PartInfo href="cid:payload@interop-lab"/></eb:PayloadInfo>
          </eb:UserMessage></eb:Messaging></S12:Header><S12:Body/></S12:Envelope>
        """.formatted(Instant.now(), messageId, PeppolPMode.DEFAULT_PARTY_TYPE_ID, sender,
        PeppolPMode.DEFAULT_PARTY_TYPE_ID, receiver, PeppolPMode.DEFAULT_AGREEMENT_ID, process,
        documentType, AS4PeppolProfileRegistarSPI.AS4_PROFILE_ID);
    return "Content-Type: multipart/related\r\n\r\n--" + boundary
        + "\r\nContent-Type: application/soap+xml\r\n\r\n" + soap
        + "\r\n--" + boundary + "\r\nContent-Type: application/xml\r\nContent-ID: <payload@interop-lab>\r\n\r\n"
        + payload + "\r\n--" + boundary + "--\r\n";
  }
}
