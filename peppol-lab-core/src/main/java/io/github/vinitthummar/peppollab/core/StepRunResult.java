package io.github.vinitthummar.peppollab.core;

import io.github.vinitthummar.peppollab.api.AdapterResult;
import io.github.vinitthummar.peppollab.api.ScenarioStatus;
import java.util.List;

public record StepRunResult(
    String id, ScenarioStatus status, String message, AdapterResult adapterResult, List<String> failures) {
  public StepRunResult {
    failures = failures == null ? List.of() : List.copyOf(failures);
  }
}
