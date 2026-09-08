package io.github.vinitthummar.peppollab.core;

import java.util.List;

public class ScenarioValidationException extends Exception {
  private final List<String> violations;

  public ScenarioValidationException(List<String> violations) {
    super(String.join("; ", violations));
    this.violations = List.copyOf(violations);
  }

  public List<String> violations() {
    return violations;
  }
}
