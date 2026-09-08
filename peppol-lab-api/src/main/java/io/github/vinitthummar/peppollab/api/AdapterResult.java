package io.github.vinitthummar.peppollab.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record AdapterResult(
    String outcome,
    Integer statusCode,
    String body,
    Map<String, String> headers,
    Duration duration,
    List<Evidence> evidence) {
  public AdapterResult {
    outcome = outcome == null ? "UNKNOWN" : outcome;
    body = body == null ? "" : body;
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    duration = duration == null ? Duration.ZERO : duration;
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
  }

  public static AdapterResult skipped(String reason) {
    return new AdapterResult("SKIPPED", null, reason, Map.of(), Duration.ZERO, List.of());
  }
}
