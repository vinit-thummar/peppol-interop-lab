package io.github.vinitthummar.peppollab.adapters;

import io.github.vinitthummar.peppollab.api.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Adapter for the public phoss AP outbound submission and status APIs. */
public final class PhossAdapter implements TargetAdapter {
  private final HttpClient client = HttpClient.newHttpClient();

  @Override public String id() { return "phoss"; }

  @Override public Set<Capability> capabilities(TargetConfig target) {
    Set<Capability> result = new LinkedHashSet<>(Set.of(Capability.AP_SUBMIT, Capability.TRANSACTION_STATUS, Capability.EVIDENCE));
    if (target.options().containsKey("publisherApi")) result.add(Capability.SMP_PROVISION);
    return Set.copyOf(result);
  }

  @Override
  public AdapterResult execute(TargetConfig target, AdapterRequest request, AdapterContext context)
      throws AdapterException {
    return switch (request.action()) {
      case "phoss.submit" -> submit(target, request);
      case "phoss.status" -> status(target, request);
      default -> AdapterResult.skipped("Unsupported phoss action: " + request.action());
    };
  }

  private AdapterResult submit(TargetConfig target, AdapterRequest request) throws AdapterException {
    String path = AdapterSupport.parameter(request.parameters(), "path", "/api/outbound/submit");
    String payload = AdapterSupport.parameter(request.parameters(), "payload", "");
    HttpRequest.Builder builder = HttpRequest.newBuilder(AdapterSupport.resolve(target, path))
        .timeout(request.timeout()).header("Content-Type", "application/xml")
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
    authenticate(target, builder);
    return AdapterSupport.send(client, builder.build());
  }

  private AdapterResult status(TargetConfig target, AdapterRequest request) throws AdapterException {
    String id = AdapterSupport.parameter(request.parameters(), "instanceId", "");
    HttpRequest.Builder builder = HttpRequest.newBuilder(
        AdapterSupport.resolve(target, "/api/outbound/status/" + id)).timeout(request.timeout()).GET();
    authenticate(target, builder);
    return AdapterSupport.send(client, builder.build());
  }

  private static void authenticate(TargetConfig target, HttpRequest.Builder builder) throws AdapterException {
    String header = target.options().get("authHeader");
    String secretRef = target.options().get("authValue");
    if (header != null && secretRef != null) builder.header(header, AdapterSupport.secret(secretRef));
  }
}
