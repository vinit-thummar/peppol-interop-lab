package io.github.vinitthummar.peppollab.core;

import io.github.vinitthummar.peppollab.api.ScenarioStatus;
import java.time.Duration;
import java.util.List;

public record ScenarioRunResult(
    String name,
    ScenarioStatus status,
    String message,
    Duration duration,
    List<StepRunResult> steps,
    List<String> tags) {
  public ScenarioRunResult {
    steps = steps == null ? List.of() : List.copyOf(steps);
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  public ScenarioRunResult(
      String name,
      ScenarioStatus status,
      String message,
      Duration duration,
      List<StepRunResult> steps) {
    this(name, status, message, duration, steps, List.of());
  }
}
