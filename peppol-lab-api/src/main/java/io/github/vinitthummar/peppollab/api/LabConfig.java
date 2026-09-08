package io.github.vinitthummar.peppollab.api;

import java.util.Map;

public record LabConfig(Map<String, TargetConfig> targets) {
  public LabConfig {
    targets = targets == null ? Map.of() : Map.copyOf(targets);
  }
}
