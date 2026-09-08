package io.github.vinitthummar.peppollab.api;

import java.time.Duration;
import java.util.Map;

public record AdapterRequest(String action, Map<String, Object> parameters, Duration timeout) {
  public AdapterRequest {
    parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
  }
}
