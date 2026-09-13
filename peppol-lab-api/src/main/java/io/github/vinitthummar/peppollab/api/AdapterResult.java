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
    List<Evidence> evidence,
    Map<String, String> outputs) {
  public AdapterResult {
    outcome = outcome == null ? "UNKNOWN" : outcome;
    body = body == null ? "" : body;
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    duration = duration == null ? Duration.ZERO : duration;
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
    outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
  }

  public AdapterResult(
      String outcome,
      Integer statusCode,
      String body,
      Map<String, String> headers,
      Duration duration,
      List<Evidence> evidence) {
    this(outcome, statusCode, body, headers, duration, evidence, Map.of());
  }

  public static AdapterResult skipped(String reason) {
    return new AdapterResult("SKIPPED", null, reason, Map.of(), Duration.ZERO, List.of());
  }
}
