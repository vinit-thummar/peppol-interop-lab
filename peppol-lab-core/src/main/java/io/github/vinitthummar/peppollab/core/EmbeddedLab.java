package io.github.vinitthummar.peppollab.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Deterministic loopback-only fixtures used by the bundled preflight scenarios. */
public final class EmbeddedLab implements AutoCloseable {
  private final HttpServer smp;
  private final HttpServer as4;
  private final Set<String> messageIds = ConcurrentHashMap.newKeySet();

  private EmbeddedLab(HttpServer smp, HttpServer as4) {
    this.smp = smp;
    this.as4 = as4;
  }

  public static EmbeddedLab start() throws IOException {
    HttpServer smp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    HttpServer as4 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    EmbeddedLab lab = new EmbeddedLab(smp, as4);
    smp.createContext("/", lab::handleSmp);
    as4.createContext("/", lab::handleAs4);
    smp.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    as4.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    smp.start();
    as4.start();
    return lab;
  }

  public Map<String, String> runtimeValues() {
    return Map.of(
        "fixture:smp", "http://127.0.0.1:" + smp.getAddress().getPort(),
        "fixture:as4", "http://127.0.0.1:" + as4.getAddress().getPort());
  }

  private void handleSmp(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getRawPath();
    String query = exchange.getRequestURI().getRawQuery();
    if (query != null && query.contains("mode=slow")) pause(300);
    if (query != null && query.contains("mode=error")) {
      respond(exchange, 500, "application/xml", "<error>controlled SMP failure</error>");
      return;
    }
    if (query != null && query.contains("mode=malformed")) {
      respond(exchange, 200, "application/xml", "<SignedServiceMetadata>");
      return;
    }
    if (path.contains("unknown")) {
      respond(exchange, 404, "application/xml", "<error>participant not found</error>");
      return;
    }
    if (path.contains("/services/")) {
      String body = """
          <?xml version="1.0" encoding="UTF-8"?>
          <SignedServiceMetadata xmlns="http://busdox.org/serviceMetadata/publishing/1.0/">
            <ServiceMetadata>
              <ServiceInformation>
                <ParticipantIdentifier scheme="iso6523-actorid-upis">9915:receiver</ParticipantIdentifier>
                <DocumentIdentifier scheme="busdox-docid-qns">urn:oasis:names:specification:ubl:schema:xsd:Invoice-2</DocumentIdentifier>
                <ProcessList><Process>
                  <ProcessIdentifier scheme="cenbii-procid-ubl">urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</ProcessIdentifier>
                  <ServiceEndpointList><Endpoint transportProfile="peppol-transport-as4-v2_0">
                    <EndpointReference xmlns="http://www.w3.org/2005/08/addressing"><Address>http://127.0.0.1/as4</Address></EndpointReference>
                    <RequireBusinessLevelSignature>false</RequireBusinessLevelSignature>
                    <Certificate>TEFCLUNFUlQ=</Certificate>
                    <ServiceDescription>Interop Lab fixture</ServiceDescription>
                    <TechnicalContactUrl>mailto:fixture@example.invalid</TechnicalContactUrl>
                  </Endpoint></ServiceEndpointList>
                </Process></ProcessList>
              </ServiceInformation>
            </ServiceMetadata>
          </SignedServiceMetadata>
          """;
      respond(exchange, 200, "application/xml", body);
      return;
    }
    String body = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ServiceGroup xmlns="http://busdox.org/serviceMetadata/publishing/1.0/">
          <ParticipantIdentifier scheme="iso6523-actorid-upis">9915:receiver</ParticipantIdentifier>
          <ServiceMetadataReferenceCollection>
            <ServiceMetadataReference href="/iso6523-actorid-upis%3A%3A9915%3Areceiver/services/invoice"/>
          </ServiceMetadataReferenceCollection>
        </ServiceGroup>
        """;
    respond(exchange, 200, "application/xml", body);
  }

  private void handleAs4(HttpExchange exchange) throws IOException {
    String query = exchange.getRequestURI().getRawQuery();
    if (query != null && query.contains("mode=slow")) pause(300);
    if (query != null && query.contains("mode=error")) {
      respond(exchange, 500, "application/soap+xml", as4Error("EBMS:0004", "controlled failure"));
      return;
    }
    byte[] input = exchange.getRequestBody().readAllBytes();
    String body = new String(input, StandardCharsets.UTF_8);
    String messageId = exchange.getRequestHeaders().getFirst("X-Peppol-Message-Id");
    if (messageId != null && !messageIds.add(messageId)) {
      respond(exchange, 409, "application/soap+xml", as4Error("EBMS:0004", "duplicate message id"));
      return;
    }
    if (!body.contains("eb:Messaging") || !body.contains("multipart/related")) {
      respond(exchange, 400, "application/soap+xml", as4Error("EBMS:0001", "invalid laboratory AS4 envelope"));
      return;
    }
    String receipt = """
        <?xml version="1.0" encoding="UTF-8"?>
        <S12:Envelope xmlns:S12="http://www.w3.org/2003/05/soap-envelope" xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
          <S12:Header><eb:Messaging><eb:SignalMessage><eb:MessageInfo><eb:MessageId>receipt@interop-lab</eb:MessageId></eb:MessageInfo><eb:Receipt/></eb:SignalMessage></eb:Messaging></S12:Header>
          <S12:Body/>
        </S12:Envelope>
        """;
    respond(exchange, 200, "application/soap+xml", receipt);
  }

  private static String as4Error(String code, String detail) {
    return "<eb:Error xmlns:eb=\"http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/\" errorCode=\""
        + code + "\"><eb:Description>" + detail + "</eb:Description></eb:Error>";
  }

  private static void respond(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
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
    smp.stop(0);
    as4.stop(0);
  }
}
