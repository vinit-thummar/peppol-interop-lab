package io.github.vinitthummar.peppollab.api;

import java.time.Instant;
import java.util.Map;

public record Evidence(String type, Instant capturedAt, Map<String, Object> attributes) {
  public Evidence {
    capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
