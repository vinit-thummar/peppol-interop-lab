package io.github.vinitthummar.peppollab.cli;

import io.github.vinitthummar.peppollab.api.Scenario;
import io.github.vinitthummar.peppollab.core.ScenarioLoader;
import io.github.vinitthummar.peppollab.core.ScenarioValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

final class BuiltinCatalog {
  static final List<String> RESOURCES = List.of(
      "/scenarios/v0.1/smp-service-group.yaml",
      "/scenarios/v0.1/smp-unknown-participant.yaml",
      "/scenarios/v0.1/as4-success.yaml",
      "/scenarios/v0.1/as4-untrusted-sender.yaml",
      "/scenarios/v0.1/as4-duplicate-message-id.yaml",
      "/scenarios/v0.1/as4-payload-integrity.yaml",
      "/scenarios/v0.1/as4-controlled-error.yaml",
      "/scenarios/v0.1/smp-timeout.yaml",
      "/scenarios/v0.1/dns-naptr-success.yaml",
      "/scenarios/v0.1/dns-nxdomain.yaml",
      "/scenarios/v0.1/dns-servfail.yaml",
      "/scenarios/v0.1/dns-delay.yaml",
      "/scenarios/v0.1/dns-timeout.yaml");

  private BuiltinCatalog() {}

  static List<Scenario> load(ScenarioLoader loader) throws IOException, ScenarioValidationException {
    java.util.ArrayList<Scenario> scenarios = new java.util.ArrayList<>();
    for (String resource : RESOURCES) {
      try (InputStream in = BuiltinCatalog.class.getResourceAsStream(resource)) {
        if (in == null) throw new IOException("Missing built-in scenario: " + resource);
        scenarios.add(loader.loadScenario(in, resource));
      }
    }
    return List.copyOf(scenarios);
  }
}
