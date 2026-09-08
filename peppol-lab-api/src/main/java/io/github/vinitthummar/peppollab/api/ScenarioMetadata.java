package io.github.vinitthummar.peppollab.api;

import java.util.List;

public record ScenarioMetadata(String name, String description, List<String> tags) {
  public ScenarioMetadata {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
