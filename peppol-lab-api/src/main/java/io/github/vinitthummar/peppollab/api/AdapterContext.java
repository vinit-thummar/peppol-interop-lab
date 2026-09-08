package io.github.vinitthummar.peppollab.api;

import java.nio.file.Path;
import java.util.Map;

public record AdapterContext(
    Path outputDirectory,
    boolean allowProduction,
    Map<String, String> runtimeValues) {
  public AdapterContext {
    runtimeValues = runtimeValues == null ? Map.of() : Map.copyOf(runtimeValues);
  }
}
