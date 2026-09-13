package io.github.vinitthummar.peppollab.adapters;

import io.github.vinitthummar.peppollab.api.AdapterContext;
import io.github.vinitthummar.peppollab.api.AdapterException;
import io.github.vinitthummar.peppollab.api.AdapterResult;
import io.github.vinitthummar.peppollab.api.DoctorCheck;
import io.github.vinitthummar.peppollab.api.Evidence;
import io.github.vinitthummar.peppollab.api.TargetConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AdapterSupport {
  private AdapterSupport() {}

  static AdapterResult send(HttpClient client, HttpRequest request) throws AdapterException {
    Instant started = Instant.now();
    try {
      HttpResponse<String> response = client.send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      Map<String, String> headers = new LinkedHashMap<>();
      response.headers().map().forEach((k, v) -> headers.put(k, String.join(",", v)));
      return new AdapterResult(
          response.statusCode() >= 200 && response.statusCode() < 300 ? "SUCCESS" : "HTTP_ERROR",
          response.statusCode(), response.body(), headers, Duration.between(started, Instant.now()),
          List.of(new Evidence("http.exchange", Instant.now(), Map.of(
              "method", request.method(), "uri", request.uri().toString(), "status", response.statusCode()))));
    } catch (HttpTimeoutException ex) {
      return new AdapterResult("TIMEOUT", null, "request timed out", Map.of(),
          Duration.between(started, Instant.now()), List.of());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AdapterException("HTTP request interrupted", ex, true);
    } catch (IOException ex) {
      throw new AdapterException("HTTP transport failed: " + ex.getMessage(), ex, true);
    }
  }

  static URI resolve(TargetConfig target, String path) {
    String base = target.baseUrl().toString();
    if (!base.endsWith("/") && !path.startsWith("/")) base += "/";
    if (base.endsWith("/") && path.startsWith("/")) path = path.substring(1);
    return URI.create(base + path);
  }

  static List<DoctorCheck> httpEndpointChecks(
      HttpClient client, TargetConfig target, String label) {
    URI endpoint = target.baseUrl();
    boolean valid = endpoint != null
        && endpoint.getHost() != null
        && endpoint.getScheme() != null
        && Set.of("http", "https").contains(endpoint.getScheme().toLowerCase(Locale.ROOT));
    DoctorCheck address = new DoctorCheck(
        label + " URL",
        valid,
        valid ? endpoint.toString() : label + " URL must use HTTP(S) and include a host");
    if (!valid) return List.of(address);

    try {
      HttpRequest request = HttpRequest.newBuilder(endpoint)
          .timeout(Duration.ofSeconds(3))
          .method("HEAD", HttpRequest.BodyPublishers.noBody())
          .build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      return List.of(
          address,
          new DoctorCheck(label + " connectivity", true, "HTTP " + response.statusCode()));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return List.of(address, new DoctorCheck(label + " connectivity", false, "check interrupted"));
    } catch (IOException | RuntimeException ex) {
      String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      return List.of(
          address,
          new DoctorCheck(label + " connectivity", false, "unreachable: " + detail));
    }
  }

  static String parameter(Map<String, Object> parameters, String name, String fallback) {
    Object value = parameters.get(name);
    return value == null ? fallback : String.valueOf(value);
  }

  static String secret(String reference) throws AdapterException {
    if (reference == null || reference.isBlank()) return null;
    if (reference.startsWith("env:")) {
      String name = reference.substring(4);
      String value = System.getenv(name);
      if (value == null) throw new AdapterException("Required environment variable is missing: " + name, true);
      return value;
    }
    if (reference.startsWith("file:")) {
      try {
        return Files.readString(Path.of(reference.substring(5)), StandardCharsets.UTF_8).strip();
      } catch (IOException ex) {
        throw new AdapterException("Cannot read secret file", ex, true);
      }
    }
    throw new AdapterException("Secrets must use env:NAME or file:/path references", true);
  }
}
