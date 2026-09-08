package io.github.vinitthummar.peppollab.api;

import java.net.URI;
import java.util.Map;

public record TargetConfig(String adapter, URI baseUrl, Map<String, String> options) {
  public TargetConfig {
    options = options == null ? Map.of() : Map.copyOf(options);
  }
}
