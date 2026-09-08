package io.github.vinitthummar.peppollab.api;

import java.util.Map;

public record ScenarioStep(
    String id,
    String target,
    String action,
    Map<String, Object> with,
    StepExpectation expect) {
  public ScenarioStep {
    with = with == null ? Map.of() : Map.copyOf(with);
  }
}
