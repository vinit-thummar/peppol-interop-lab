package io.github.vinitthummar.peppollab.adapters;

import io.github.vinitthummar.peppollab.api.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class StandardSmpAdapter implements TargetAdapter {
  private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @Override public String id() { return "standard-smp"; }

  @Override public Set<Capability> capabilities(TargetConfig target) {
    return Set.of(Capability.SMP_LOOKUP, Capability.EVIDENCE);
  }

  @Override public List<DoctorCheck> doctor(TargetConfig target, AdapterContext context) {
    boolean http = Set.of("http", "https").contains(target.baseUrl().getScheme());
    return List.of(new DoctorCheck("SMP URL", http, http ? target.baseUrl().toString() : "SMP URL must use HTTP(S)"));
  }

  @Override
  public AdapterResult execute(TargetConfig target, AdapterRequest request, AdapterContext context)
      throws AdapterException {
    if (!"smp.get".equals(request.action())) return AdapterResult.skipped("Unsupported SMP action: " + request.action());
    String path = AdapterSupport.parameter(request.parameters(), "path", "/");
    HttpRequest httpRequest = HttpRequest.newBuilder(AdapterSupport.resolve(target, path))
        .timeout(request.timeout()).header("Accept", "application/xml").GET().build();
    return AdapterSupport.send(client, httpRequest);
  }
}
