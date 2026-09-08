package io.github.vinitthummar.peppollab.api;

import java.util.List;
import java.util.Map;

public record StepExpectation(
    Integer status,
    String outcome,
    List<String> contains,
    List<String> notContains,
    Map<String, String> headers,
    Long maxDurationMs) {
  public StepExpectation {
    contains = contains == null ? List.of() : List.copyOf(contains);
    notContains = notContains == null ? List.of() : List.copyOf(notContains);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }
}
