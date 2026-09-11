package io.github.vinitthummar.peppollab.adapters;

import com.helger.base.io.iface.IHasInputStream;
import com.helger.base.io.stream.StreamHelper;
import com.helger.collection.commons.CommonsArrayList;
import com.helger.collection.commons.ICommonsList;
import com.helger.http.header.HttpHeaderMap;
import com.helger.mime.IMimeType;
import com.helger.mime.parse.MimeTypeParser;
import com.helger.phase4.attachment.IAS4IncomingAttachmentFactory;
import com.helger.phase4.attachment.WSS4JAttachment;
import com.helger.phase4.ebms3header.Ebms3SignalMessage;
import com.helger.phase4.ebms3header.Ebms3UserMessage;
import com.helger.phase4.error.AS4ErrorList;
import com.helger.phase4.incoming.AS4IncomingMessageMetadata;
import com.helger.phase4.incoming.AS4IncomingProfileSelectorConstant;
import com.helger.phase4.incoming.AS4IncomingReceiverConfiguration;
import com.helger.phase4.incoming.AS4RequestHandler;
import com.helger.phase4.incoming.IAS4IncomingMessageMetadata;
import com.helger.phase4.incoming.IAS4IncomingMessageState;
import com.helger.phase4.incoming.IAS4ResponseAbstraction;
import com.helger.phase4.incoming.crypto.AS4IncomingSecurityConfiguration;
import com.helger.phase4.incoming.spi.AS4MessageProcessorResult;
import com.helger.phase4.incoming.spi.AS4SignalMessageProcessorResult;
import com.helger.phase4.incoming.spi.IAS4IncomingMessageProcessorSPI;
import com.helger.phase4.model.pmode.IPMode;
import com.helger.phase4.model.pmode.resolve.AS4DefaultPModeResolver;
import com.helger.phase4.profile.peppol.AS4PeppolProfileRegistarSPI;
import com.helger.web.scope.mgr.WebScoped;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.vinitthummar.peppollab.core.As4Fixture;
import io.github.vinitthummar.peppollab.core.EphemeralPki;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.w3c.dom.Node;

/** Loopback-only phase4 receiver that decrypts, verifies, records, and acknowledges AS4 messages. */
final class Phase4As4Fixture implements As4Fixture {
  private final HttpServer server;
  private final ExecutorService executor;
  private final Phase4GlobalScope globalScope;
  private final EphemeralPhase4CryptoFactory cryptoFactory;
  private final Map<String, byte[]> receivedPayloads = new ConcurrentHashMap<>();

  private Phase4As4Fixture(
      HttpServer server,
      ExecutorService executor,
      Phase4GlobalScope globalScope,
      EphemeralPhase4CryptoFactory cryptoFactory) {
    this.server = server;
    this.executor = executor;
    this.globalScope = globalScope;
    this.cryptoFactory = cryptoFactory;
  }

  static Phase4As4Fixture start(EphemeralPki pki)
      throws IOException, GeneralSecurityException {
    Phase4GlobalScope scope = Phase4GlobalScope.open();
    EphemeralPhase4CryptoFactory crypto = null;
    HttpServer server = null;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      crypto = EphemeralPhase4CryptoFactory.load(
          pki.receiverKeyStorePath(), "receiver", pki.password(), pki.caCertificate());
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      Phase4As4Fixture fixture = new Phase4As4Fixture(server, executor, scope, crypto);
      server.createContext("/", fixture::handle);
      server.setExecutor(executor);
      server.start();
      return fixture;
    } catch (IOException | GeneralSecurityException | RuntimeException ex) {
      if (server != null) server.stop(0);
      executor.shutdownNow();
      if (crypto != null) crypto.close();
      scope.close();
      throw ex;
    }
  }

  @Override
  public URI endpoint() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void handle(HttpExchange exchange) throws IOException {
    String query = exchange.getRequestURI().getRawQuery();
    if (query != null && query.contains("mode=slow")) pause(300);
    if (query != null && query.contains("mode=error")) {
      respond(
          exchange,
          500,
          "application/soap+xml",
          "<eb:Error xmlns:eb=\"http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/\" "
              + "errorCode=\"EBMS:0004\"><eb:Description>controlled failure</eb:Description></eb:Error>");
      return;
    }

    HttpHeaderMap headers = new HttpHeaderMap();
    exchange.getRequestHeaders().forEach(
        (name, values) -> values.forEach(value -> headers.addHeader(name, value)));
    JdkResponse response = new JdkResponse(exchange);
    InputStream requestBody = exchange.getRequestBody();
    if (query != null && query.contains("mode=tamper-payload")) {
      try (InputStream originalRequestBody = requestBody) {
        requestBody = new ByteArrayInputStream(tamperEncryptedPayload(
            originalRequestBody.readAllBytes(),
            exchange.getRequestHeaders().getFirst("Content-Type")));
      }
    }

    try (WebScoped ignored = new WebScoped();
         AS4RequestHandler handler =
             new AS4RequestHandler(AS4IncomingMessageMetadata.createForRequest())) {
      handler.setCryptoFactory(cryptoFactory);
      handler.setPModeResolver(
          new AS4DefaultPModeResolver(AS4PeppolProfileRegistarSPI.AS4_PROFILE_ID));
      handler.setIncomingProfileSelector(
          new AS4IncomingProfileSelectorConstant(
              AS4PeppolProfileRegistarSPI.AS4_PROFILE_ID, true));
      handler.setIncomingAttachmentFactory(IAS4IncomingAttachmentFactory.DEFAULT_INSTANCE);
      handler.setIncomingSecurityConfiguration(
          AS4IncomingSecurityConfiguration.createDefaultInstance());
      handler.setIncomingReceiverConfiguration(new AS4IncomingReceiverConfiguration());
      handler.setProcessorSupplier(
          () -> new CommonsArrayList<IAS4IncomingMessageProcessorSPI>(processor()));
      handler.handleRequest(requestBody, headers, response);
      response.write();
    } catch (Exception ex) {
      respond(exchange, 500, "text/plain", "phase4 fixture processing failed");
    }
  }

  private static byte[] tamperEncryptedPayload(byte[] message, String contentType)
      throws IOException {
    IMimeType mimeType = MimeTypeParser.safeParseMimeType(contentType);
    String boundary = mimeType == null ? null : mimeType.getParameterValueWithName("boundary");
    if (boundary == null || boundary.isBlank()) {
      throw new IOException("Cannot inject payload mutation without a MIME boundary");
    }

    byte[] partBoundary = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
    byte[] headerSeparator = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    int attachmentBoundary = -1;
    int headersEnd = -1;
    String attachmentHeaders = null;
    int searchFrom = 0;
    while (true) {
      int candidateBoundary = indexOf(message, partBoundary, searchFrom);
      if (candidateBoundary < 0) break;
      int candidateHeadersEnd =
          indexOf(message, headerSeparator, candidateBoundary + partBoundary.length);
      if (candidateHeadersEnd < 0) break;
      String candidateHeaders = new String(
          message,
          candidateBoundary + partBoundary.length,
          candidateHeadersEnd - candidateBoundary - partBoundary.length,
          StandardCharsets.ISO_8859_1);
      if (candidateHeaders.toLowerCase(Locale.ROOT).contains("content-id:")) {
        attachmentBoundary = candidateBoundary;
        headersEnd = candidateHeadersEnd;
        attachmentHeaders = candidateHeaders;
        break;
      }
      searchFrom = candidateHeadersEnd + headerSeparator.length;
    }
    if (attachmentBoundary < 0) {
      throw new IOException("Cannot locate the encrypted MIME attachment boundary");
    }
    int payloadStart = headersEnd + headerSeparator.length;
    int payloadEnd = indexOf(message, partBoundary, payloadStart);
    if (payloadEnd <= payloadStart) {
      throw new IOException("Cannot locate the encrypted MIME attachment payload");
    }

    if (!attachmentHeaders.toLowerCase(Locale.ROOT)
        .contains("content-transfer-encoding: binary")) {
      throw new IOException("Encrypted MIME attachment is not binary encoded");
    }

    byte[] mutated = message.clone();
    mutated[payloadStart + (payloadEnd - payloadStart) / 2] ^= 0x01;
    return mutated;
  }

  private static int indexOf(byte[] source, byte[] target, int fromIndex) {
    if (fromIndex < 0) return -1;
    for (int i = fromIndex; i <= source.length - target.length; i++) {
      int j = 0;
      while (j < target.length && source[i + j] == target[j]) j++;
      if (j == target.length) return i;
    }
    return -1;
  }

  private IAS4IncomingMessageProcessorSPI processor() {
    return new IAS4IncomingMessageProcessorSPI() {
      @Override
      public AS4MessageProcessorResult processAS4UserMessage(
          IAS4IncomingMessageMetadata metadata,
          HttpHeaderMap headers,
          Ebms3UserMessage userMessage,
          IPMode pmode,
          Node payload,
          ICommonsList<WSS4JAttachment> attachments,
          IAS4IncomingMessageState state,
          AS4ErrorList errors) {
        try {
          byte[] bytes = null;
          if (attachments != null && !attachments.isEmpty()) {
            bytes = StreamHelper.getAllBytes(attachments.getFirst().getInputStreamProvider());
          }
          if (bytes == null && payload != null) {
            bytes = payload.getTextContent().getBytes(StandardCharsets.UTF_8);
          }
          if (bytes == null) return AS4MessageProcessorResult.createFailure();
          receivedPayloads.put(userMessage.getMessageInfo().getMessageId(), bytes);
          return AS4MessageProcessorResult.createSuccess();
        } catch (Exception ex) {
          return AS4MessageProcessorResult.createFailure();
        }
      }

      @Override
      public AS4SignalMessageProcessorResult processAS4SignalMessage(
          IAS4IncomingMessageMetadata metadata,
          HttpHeaderMap headers,
          Ebms3SignalMessage signalMessage,
          IPMode pmode,
          IAS4IncomingMessageState state,
          AS4ErrorList errors) {
        return AS4SignalMessageProcessorResult.createSuccess();
      }

      @Override
      public void processAS4ResponseMessage(
          IAS4IncomingMessageMetadata metadata,
          IAS4IncomingMessageState state,
          String responseMessageId,
          byte[] responseBytes,
          boolean responsePayloadIsAvailable,
          AS4ErrorList errors) {
        // The fixture receives user messages; it does not process asynchronous responses.
      }
    };
  }

  byte[] receivedPayload(String messageId) {
    byte[] value = receivedPayloads.get(messageId);
    return value == null ? null : value.clone();
  }

  private static void respond(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    cryptoFactory.close();
    globalScope.close();
  }

  private static final class JdkResponse implements IAS4ResponseAbstraction {
    private final HttpExchange exchange;
    private final HttpHeaderMap headers = new HttpHeaderMap();
    private int status = 200;
    private String mimeType = "application/soap+xml";
    private byte[] content = new byte[0];

    private JdkResponse(HttpExchange exchange) {
      this.exchange = exchange;
    }

    @Override
    public void setContent(byte[] bytes, Charset charset) {
      content = bytes.clone();
    }

    @Override
    public void setContent(HttpHeaderMap responseHeaders, IHasInputStream source) {
      headers.addAllHeaders(responseHeaders);
      try (InputStream input = source.getInputStream()) {
        if (input == null) throw new IOException("phase4 response stream is unavailable");
        content = input.readAllBytes();
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }

    @Override
    public void setMimeType(IMimeType value) {
      mimeType = value.getAsString();
    }

    @Override
    public void setStatus(int statusCode) {
      status = statusCode;
    }

    private void write() throws IOException {
      headers.forEachSingleHeader((name, value) -> {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.equals("content-length")
            && !lower.equals("transfer-encoding")
            && !lower.equals("connection")) {
          exchange.getResponseHeaders().add(name, value);
        }
      }, false, false);
      if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
        exchange.getResponseHeaders().set("Content-Type", mimeType);
      }
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(status, content.length);
      try (var output = exchange.getResponseBody()) {
        output.write(content);
      }
    }
  }
}
