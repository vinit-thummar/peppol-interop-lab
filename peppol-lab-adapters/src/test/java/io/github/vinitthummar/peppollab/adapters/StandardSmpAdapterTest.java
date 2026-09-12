package io.github.vinitthummar.peppollab.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.vinitthummar.peppollab.api.AdapterContext;
import io.github.vinitthummar.peppollab.api.AdapterRequest;
import io.github.vinitthummar.peppollab.api.TargetConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandardSmpAdapterTest {
  @TempDir Path output;

  @Test
  void encodesStructuredParticipantAndDocumentIdentifiers() throws Exception {
    AtomicReference<String> requestedPath = new AtomicReference<>();
    try (SmpServer server = SmpServer.start(validMetadata(), requestedPath)) {
      AdapterRequest request = new AdapterRequest(
          "smp.get",
          Map.of(
              "participantScheme", "iso6523-actorid-upis",
              "participantValue", "9915:receiver / test",
              "documentScheme", "busdox-docid-qns",
              "documentValue", "urn:test:invoice::1"),
          Duration.ofSeconds(5));

      var result = new StandardSmpAdapter().execute(
          new TargetConfig("standard-smp", server.endpoint(), Map.of()),
          request,
          new AdapterContext(output, false, Map.of()));

      assertThat(result.outcome()).isEqualTo("SUCCESS");
      assertThat(requestedPath).hasValue(
          "/iso6523-actorid-upis%3A%3A9915%3Areceiver%20%2F%20test/services/"
              + "busdox-docid-qns%3A%3Aurn%3Atest%3Ainvoice%3A%3A1");
    }
  }

  @Test
  void marksMalformedSuccessfulResponseAsInvalidMetadata() throws Exception {
    try (SmpServer server = SmpServer.start("<SignedServiceMetadata>", new AtomicReference<>())) {
      var result = new StandardSmpAdapter().execute(
          new TargetConfig("standard-smp", server.endpoint(), Map.of()),
          new AdapterRequest("smp.get", Map.of("path", "/metadata"), Duration.ofSeconds(5)),
          new AdapterContext(output, false, Map.of()));

      assertThat(result.statusCode()).isEqualTo(200);
      assertThat(result.outcome()).isEqualTo("INVALID_METADATA");
      assertThat(result.body()).contains("Malformed SMP XML");
    }
  }

  @Test
  void rejectsDoctypesThatCanResolveExternalEntities() throws Exception {
    String body = """
        <!DOCTYPE ServiceGroup [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
        <ServiceGroup xmlns="http://busdox.org/serviceMetadata/publishing/1.0/">&secret;</ServiceGroup>
        """;
    try (SmpServer server = SmpServer.start(body, new AtomicReference<>())) {
      var result = new StandardSmpAdapter().execute(
          new TargetConfig("standard-smp", server.endpoint(), Map.of()),
          new AdapterRequest("smp.get", Map.of("path", "/metadata"), Duration.ofSeconds(5)),
          new AdapterContext(output, false, Map.of()));

      assertThat(result.outcome()).isEqualTo("INVALID_METADATA");
      assertThat(result.body()).contains("Malformed SMP XML").doesNotContain("root:");
    }
  }

  @Test
  void rejectsUnexpectedSmpNamespace() throws Exception {
    String body = "<ServiceGroup xmlns=\"urn:not-peppol-smp\"/>";
    try (SmpServer server = SmpServer.start(body, new AtomicReference<>())) {
      var result = new StandardSmpAdapter().execute(
          new TargetConfig("standard-smp", server.endpoint(), Map.of()),
          new AdapterRequest("smp.get", Map.of("path", "/metadata"), Duration.ofSeconds(5)),
          new AdapterContext(output, false, Map.of()));

      assertThat(result.outcome()).isEqualTo("INVALID_METADATA");
      assertThat(result.body()).contains("Unexpected SMP document namespace");
    }
  }

  private static String validMetadata() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <SignedServiceMetadata xmlns="http://busdox.org/serviceMetadata/publishing/1.0/">
          <ServiceMetadata/>
        </SignedServiceMetadata>
        """;
  }

  private static final class SmpServer implements AutoCloseable {
    private final HttpServer server;

    private SmpServer(HttpServer server) {
      this.server = server;
    }

    static SmpServer start(String body, AtomicReference<String> requestedPath) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        requestedPath.set(exchange.getRequestURI().getRawPath());
        respond(exchange, body);
      });
      server.start();
      return new SmpServer(server);
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/xml");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var response = exchange.getResponseBody()) {
        response.write(bytes);
      }
    }

    @Override public void close() {
      server.stop(0);
    }
  }
}
