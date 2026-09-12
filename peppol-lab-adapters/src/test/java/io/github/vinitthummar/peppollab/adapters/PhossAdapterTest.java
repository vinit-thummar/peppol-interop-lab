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

      assertThat(adapter.capabilities(target)).contains(Capability.SMP_PROVISION);
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

  private static TargetConfig target(URI endpoint, Path password) {
    return new TargetConfig(
        "phoss",
        endpoint.resolve("/smp"),
        Map.of(
            "publisherApi", "true",
            "username", "interop",
            "password", password.toUri().toString()));
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
      String method, String rawPath, String rawQuery, String authorization, String body) {}

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
          body));
      switch (exchange.getRequestMethod()) {
        case "GET" -> respond(exchange, stored.get() == null ? 404 : 200,
            stored.get() == null ? "<error>not found</error>" : stored.get());
        case "PUT" -> {
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
