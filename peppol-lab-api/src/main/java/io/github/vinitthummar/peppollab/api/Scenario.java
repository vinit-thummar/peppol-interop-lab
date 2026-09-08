package io.github.vinitthummar.peppollab.api;

import java.util.List;

public record Scenario(
    String apiVersion,
    String kind,
    ScenarioMetadata metadata,
    SpecificationVersions specifications,
    List<Capability> requires,
    List<ScenarioStep> steps) {
  public static final String API_VERSION = "peppol-interop-lab/v1alpha1";

  public Scenario {
    requires = requires == null ? List.of() : List.copyOf(requires);
    steps = steps == null ? List.of() : List.copyOf(steps);
  }
}
