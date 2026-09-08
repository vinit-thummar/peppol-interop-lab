package io.github.vinitthummar.peppollab.core;

import io.github.vinitthummar.peppollab.api.ScenarioStatus;
import java.time.Duration;
import java.util.List;

public record ScenarioRunResult(
    String name, ScenarioStatus status, String message, Duration duration, List<StepRunResult> steps) {
  public ScenarioRunResult {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }
}
