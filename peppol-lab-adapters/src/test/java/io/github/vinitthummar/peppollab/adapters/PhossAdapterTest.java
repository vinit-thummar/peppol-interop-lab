package io.github.vinitthummar.peppollab.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.vinitthummar.peppollab.api.AdapterContext;
import io.github.vinitthummar.peppollab.api.AdapterException;
import io.github.vinitthummar.peppollab.api.AdapterRequest;
import io.github.vinitthummar.peppollab.api.Capability;
import io.github.vinitthummar.peppollab.api.TargetConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhossAdapterTest {
  @TempDir Path output;

  @Test
  void provisionsReadsAndAutomaticallyCleansUpAServiceGroup() throws Exception {
    AtomicReference<String> stored = new AtomicReference<>();
    List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    try (HttpServerFixture fixture = HttpServerFixture.start(stored, requests)) {
      Path password = output.resolve("password.txt");
      Files.writeString(password, "correct horse", StandardCharsets.UTF_8);
      TargetConfig target = target(fixture.endpoint(), password);
      AdapterContext context = new AdapterContext(output, false, Map.of());
      AdapterRequest request = request("smp.service-group.put");
      PhossAdapter adapter = new PhossAdapter();

      assertThat(adapter.capabilities(target))
          .contains(Capability.SMP_PROVISION)
          .doesNotContain(Capability.AP_SUBMIT, Capability.TRANSACTION_STATUS);
      assertThat(adapter.execute(target, request, context).statusCode()).isEqualTo(200);
      assertThat(adapter.execute(target, request("smp.service-group.get"), context).body())
          .contains("ServiceGroup", "9915:interop-lab-stage3");

      adapter.cleanup(target, context);

      assertThat(stored).hasNullValue();
      assertThat(requests).extracting(CapturedRequest::method)
          .containsExactly("GET", "PUT", "GET", "DELETE");
      assertThat(requests.get(1).rawPath())
          .isEqualTo("/smp/iso6523-actorid-upis%3A%3A9915%3Ainterop-lab-stage3");
      assertThat(requests.get(1).rawQuery()).isEqualTo("create-in-sml=false");
      assertThat(requests.get(1).body())
          .contains(
              "xmlns:id=\"http://busdox.org/transport/identifiers/1.0/\"",
              "<id:ParticipantIdentifier scheme=\"iso6523-actorid-upis\">"
                  + "9915:interop-lab-stage3</id:ParticipantIdentifier>");
      assertThat(requests.get(3).rawQuery()).isEqualTo("delete-in-sml=false");
      assertThat(requests.get(1).authorization()).isEqualTo("Basic " + Base64.getEncoder()
          .encodeToString("interop:correct horse".getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  void provisionsReadsAndAutomaticallyCleansUpServiceMetadata() throws Exception {
    AtomicReference<String> stored = new AtomicReference<>();
    List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    try (HttpServerFixture fixture = HttpServerFixture.start(stored, requests)) {
      Path password = output.resolve("metadata-password.txt");
      Files.writeString(password, "correct horse", StandardCharsets.UTF_8);
      TargetConfig target = target(fixture.endpoint(), password);
      AdapterContext context = new AdapterContext(output, false, Map.of());
      PhossAdapter adapter = new PhossAdapter();

      assertThat(adapter.execute(target, metadataRequest("smp.service-metadata.put"), context)
          .statusCode()).isEqualTo(200);
      assertThat(adapter.execute(target, metadataRequest("smp.service-metadata.get"), context)
          .body()).contains("ServiceMetadata", "urn:test:invoice::1");

      adapter.cleanup(target, context);

      assertThat(stored).hasNullValue();
      assertThat(requests).extracting(CapturedRequest::method)
          .containsExactly("GET", "PUT", "GET", "DELETE");
      assertThat(requests.get(1).rawPath()).isEqualTo(
          "/smp/iso6523-actorid-upis%3A%3A9915%3Ainterop-lab-stage3/services/"
              + "busdox-docid-qns%3A%3Aurn%3Atest%3Ainvoice%3A%3A1");
      assertThat(requests.get(1).rawQuery()).isNull();
      assertThat(requests.get(1).body())
          .contains(
              "<id:ParticipantIdentifier scheme=\"iso6523-actorid-upis\">"
                  + "9915:interop-lab-stage3</id:ParticipantIdentifier>",
              "<id:DocumentIdentifier scheme=\"busdox-docid-qns\">"
                  + "urn:test:invoice::1</id:DocumentIdentifier>",
              "<id:ProcessIdentifier scheme=\"cenbii-procid-ubl\">"
                  + "urn:test:billing</id:ProcessIdentifier>",
              "<wsa:Address>http://127.0.0.1:8090/as4</wsa:Address>",
              "<smp:Certificate>bGFiLWNlcnRpZmljYXRl</smp:Certificate>");
    }
  }

  @Test
  void acceptsCallerSuppliedServiceMetadataWithoutGeneratorParameters() throws Exception {
    AtomicReference<String> stored = new AtomicReference<>();
    List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    try (HttpServerFixture fixture = HttpServerFixture.start(stored, requests)) {
      Path password = output.resolve("custom-metadata-password.txt");
      Files.writeString(password, "correct horse", StandardCharsets.UTF_8);
      PhossAdapter adapter = new PhossAdapter();
      AdapterRequest request = new AdapterRequest(
          "smp.service-metadata.put",
          Map.of(
              "participantValue", "9915:interop-lab-stage3",
              "documentValue", "urn:test:custom::1",
              "payload", "<ServiceMetadata>caller supplied</ServiceMetadata>"),
          Duration.ofSeconds(5));

      assertThat(adapter.execute(
          target(fixture.endpoint(), password),
          request,
          new AdapterContext(output, false, Map.of())).statusCode()).isEqualTo(200);
      assertThat(requests.get(1).body())
          .isEqualTo("<ServiceMetadata>caller supplied</ServiceMetadata>");
    }
  }

  @Test
  void refusesToOverwriteAnExistingServiceGroup() throws Exception {
    AtomicReference<String> stored = new AtomicReference<>("existing");
    List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    try (HttpServerFixture fixture = HttpServerFixture.start(stored, requests)) {
      Path password = output.resolve("existing-password.txt");
      Files.writeString(password, "correct horse", StandardCharsets.UTF_8);
      PhossAdapter adapter = new PhossAdapter();

      assertThatThrownBy(() -> adapter.execute(
          target(fixture.endpoint(), password),
          request("smp.service-group.put"),
          new AdapterContext(output, false, Map.of())))
          .isInstanceOf(AdapterException.class)
          .hasMessageContaining("Refusing to overwrite existing");
      assertThat(requests).extracting(CapturedRequest::method).containsExactly("GET");
      assertThat(stored).hasValue("existing");
    }
  }

  @Test
  void requiresExplicitProductionOverrideForSmlMutation() throws Exception {
    PhossAdapter adapter = new PhossAdapter();
    AdapterRequest request = new AdapterRequest(
        "smp.service-group.put",
        Map.of("participantValue", "9915:test", "createInSml", true),
        Duration.ofSeconds(1));
    TargetConfig target = new TargetConfig(
        "phoss", URI.create("http://127.0.0.1:9"), Map.of("publisherApi", "true"));

    assertThatThrownBy(() -> adapter.execute(
        target, request, new AdapterContext(output, false, Map.of())))
        .isInstanceOf(AdapterException.class)
        .hasMessageContaining("--allow-production");
  }

  @Test
  void submitsRawDocumentToThePhossApContract() throws Exception {
    AtomicReference<String> stored = new AtomicReference<>();
    List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    try (HttpServerFixture fixture = HttpServerFixture.start(stored, requests)) {
      Path token = output.resolve("ap-token.txt");
      Files.writeString(token, "local-test-token", StandardCharsets.UTF_8);
      Path payload = output.resolve("invoice.xml");
      Files.writeString(payload, "<Invoice><ID>interop-1</ID></Invoice>", StandardCharsets.UTF_8);
      PhossAdapter adapter = new PhossAdapter();
      AdapterRequest request = new AdapterRequest(
          "phoss.submit",
          Map.ofEntries(
              Map.entry("senderId", "iso6523-actorid-upis::9915:sender"),
              Map.entry("receiverId", "iso6523-actorid-upis::9915:receiver"),
              Map.entry("documentTypeId", "busdox-docid-qns::urn:test:invoice::1"),
              Map.entry("processId", "cenbii-procid-ubl::urn:test:billing"),
              Map.entry("countryCode", "in"),
              Map.entry("sbdhInstanceID", "interop instance/1"),
              Map.entry("custom1", "contract-test"),
              Map.entry("payloadFile", payload.toString())),
          Duration.ofSeconds(5));

      assertThat(adapter.execute(
          apTarget(fixture.endpoint(), token), request,
          new AdapterContext(output, false, Map.of())).statusCode()).isEqualTo(200);

      assertThat(requests).hasSize(1);
      CapturedRequest captured = requests.getFirst();
      assertThat(captured.method()).isEqualTo("POST");
      assertThat(captured.rawPath()).isEqualTo(
          "/api/outbound/submit/"
              + "iso6523-actorid-upis%3A%3A9915%3Asender/"
              + "iso6523-actorid-upis%3A%3A9915%3Areceiver/"
              + "busdox-docid-qns%3A%3Aurn%3Atest%3Ainvoice%3A%3A1/"
              + "cenbii-procid-ubl%3A%3Aurn%3Atest%3Abilling/IN");
      assertThat(captured.rawQuery())
          .isEqualTo("sbdhInstanceID=interop%20instance%2F1&custom1=contract-test");
      assertThat(captured.apiToken()).isEqualTo("local-test-token");
      assertThat(captured.contentType()).isEqualTo("application/xml");
      assertThat(captured.body()).isEqualTo("<Invoice><ID>interop-1</ID></Invoice>");
    }
  }

  @Test
  void readsArchivedPhossApTransactionStatus() throws Exception {
    AtomicReference<String> stored = new AtomicReference<>(
        "{\"sbdhInstanceID\":\"interop/1\",\"status\":\"sent\"}");
    List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    try (HttpServerFixture fixture = HttpServerFixture.start(stored, requests)) {
      Path token = output.resolve("status-token.txt");
      Files.writeString(token, "status-token", StandardCharsets.UTF_8);
      AdapterRequest request = new AdapterRequest(
          "phoss.status",
          Map.of("instanceId", "interop/1", "includeArchive", true),
          Duration.ofSeconds(5));

      var result = new PhossAdapter().execute(
          apTarget(fixture.endpoint(), token), request,
          new AdapterContext(output, false, Map.of()));

      assertThat(result.statusCode()).isEqualTo(200);
      assertThat(result.body()).contains("\"status\":\"sent\"");
      assertThat(requests.getFirst().rawPath())
          .isEqualTo("/api/outbound/status/interop%2F1");
      assertThat(requests.getFirst().rawQuery()).isEqualTo("includeArchive=true");
      assertThat(requests.getFirst().apiToken()).isEqualTo("status-token");
    }
  }

  @Test
  void doctorChecksCredentialsWithoutSendingThem() throws Exception {
    AtomicReference<String> stored = new AtomicReference<>();
    List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    try (HttpServerFixture fixture = HttpServerFixture.start(stored, requests)) {
      Path password = output.resolve("doctor-password.txt");
      Files.writeString(password, "never-send-me", StandardCharsets.UTF_8);

      var checks = new PhossAdapter().doctor(
          target(fixture.endpoint(), password),
          new AdapterContext(output, false, Map.of()));

      assertThat(checks).allMatch(check -> check.successful());
      assertThat(checks).extracting(check -> check.name())
          .contains("phoss endpoint connectivity", "phoss API credentials");
      assertThat(requests).singleElement().satisfies(request -> {
        assertThat(request.method()).isEqualTo("HEAD");
        assertThat(request.authorization()).isNull();
        assertThat(request.body()).doesNotContain("never-send-me");
      });
    }
  }

  @Test
  void doctorReportsAnUnreadableCredentialReference() {
    TargetConfig target = new TargetConfig(
        "phoss",
        URI.create("http://127.0.0.1:9"),
        Map.of(
            "publisherApi", "true",
            "username", "interop",
            "password", output.resolve("missing-password.txt").toUri().toString()));

    assertThat(new PhossAdapter().doctor(
        target, new AdapterContext(output, false, Map.of())))
        .anySatisfy(check -> {
          assertThat(check.name()).isEqualTo("phoss API credentials");
          assertThat(check.successful()).isFalse();
          assertThat(check.message()).contains("Cannot read secret file");
        });
  }

  @Test
  void rejectsIncompletePhossApSubmissionBeforeNetworkAccess() {
    PhossAdapter adapter = new PhossAdapter();
    AdapterRequest request = new AdapterRequest(
        "phoss.submit", Map.of("payload", "<Invoice/>"), Duration.ofSeconds(1));

    assertThatThrownBy(() -> adapter.execute(
        new TargetConfig("phoss", URI.create("http://127.0.0.1:9"), Map.of()),
        request,
        new AdapterContext(output, false, Map.of())))
        .isInstanceOf(AdapterException.class)
        .hasMessageContaining("senderId");
  }

  @Test
  void requiresReferencedAuthenticationForPhossApSubmission() {
    PhossAdapter adapter = new PhossAdapter();
    AdapterRequest request = new AdapterRequest(
        "phoss.submit",
        Map.of(
            "senderId", "iso6523-actorid-upis::9915:sender",
            "receiverId", "iso6523-actorid-upis::9915:receiver",
            "documentTypeId", "busdox-docid-qns::urn:test:invoice::1",
            "processId", "cenbii-procid-ubl::urn:test:billing",
            "countryCode", "IN",
            "payload", "<Invoice/>"),
        Duration.ofSeconds(1));

    assertThatThrownBy(() -> adapter.execute(
        new TargetConfig("phoss", URI.create("http://127.0.0.1:9"), Map.of()),
        request,
        new AdapterContext(output, false, Map.of())))
        .isInstanceOf(AdapterException.class)
        .hasMessageContaining("authentication is required");
  }

  private static TargetConfig target(URI endpoint, Path password) {
    return new TargetConfig(
        "phoss",
        endpoint.resolve("/smp"),
        Map.of(
            "publisherApi", "true",
            "username", "interop",
            "password", password.toUri().toString()));
  }

  private static TargetConfig apTarget(URI endpoint, Path token) {
    return new TargetConfig(
        "phoss",
        endpoint,
        Map.of(
            "apApi", "true",
            "authHeader", "X-Token",
            "authValue", token.toUri().toString()));
  }

  private static AdapterRequest request(String action) {
    return new AdapterRequest(
        action,
        Map.of(
            "participantScheme", "iso6523-actorid-upis",
            "participantValue", "9915:interop-lab-stage3"),
        Duration.ofSeconds(5));
  }

  private static AdapterRequest metadataRequest(String action) {
    return new AdapterRequest(
        action,
        Map.of(
            "participantScheme", "iso6523-actorid-upis",
            "participantValue", "9915:interop-lab-stage3",
            "documentScheme", "busdox-docid-qns",
            "documentValue", "urn:test:invoice::1",
            "processScheme", "cenbii-procid-ubl",
            "processValue", "urn:test:billing",
            "endpointUrl", "http://127.0.0.1:8090/as4",
            "certificate", "bGFiLWNlcnRpZmljYXRl"),
        Duration.ofSeconds(5));
  }

  private record CapturedRequest(
      String method,
      String rawPath,
      String rawQuery,
      String authorization,
      String apiToken,
      String contentType,
      String body) {}

  private static final class HttpServerFixture implements AutoCloseable {
    private final HttpServer server;

    private HttpServerFixture(HttpServer server) {
      this.server = server;
    }

    static HttpServerFixture start(
        AtomicReference<String> stored, List<CapturedRequest> requests) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> handle(exchange, stored, requests));
      server.start();
      return new HttpServerFixture(server);
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void handle(
        HttpExchange exchange,
        AtomicReference<String> stored,
        List<CapturedRequest> requests) throws IOException {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(new CapturedRequest(
          exchange.getRequestMethod(),
          exchange.getRequestURI().getRawPath(),
          exchange.getRequestURI().getRawQuery(),
          exchange.getRequestHeaders().getFirst("Authorization"),
          exchange.getRequestHeaders().getFirst("X-Token"),
          exchange.getRequestHeaders().getFirst("Content-Type"),
          body));
      switch (exchange.getRequestMethod()) {
        case "GET" -> respond(exchange, stored.get() == null ? 404 : 200,
            stored.get() == null ? "<error>not found</error>" : stored.get());
        case "PUT", "POST" -> {
          stored.set(body);
          respond(exchange, 200, "");
        }
        case "DELETE" -> {
          stored.set(null);
          respond(exchange, 200, "");
        }
        default -> respond(exchange, 405, "");
      }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
      if ("HEAD".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
        return;
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/xml");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var response = exchange.getResponseBody()) {
        response.write(bytes);
      }
    }

    @Override public void close() {
      server.stop(0);
    }
  }
}
