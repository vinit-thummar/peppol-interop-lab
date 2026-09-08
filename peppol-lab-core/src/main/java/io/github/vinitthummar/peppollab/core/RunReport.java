package io.github.vinitthummar.peppollab.core;

import java.time.Instant;
import java.util.List;

public record RunReport(
    String toolVersion,
    Instant startedAt,
    Instant finishedAt,
    List<ScenarioRunResult> scenarios) {
  public RunReport {
    scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
  }

  public int passed() {
    return (int) scenarios.stream().filter(s -> s.status().name().equals("PASSED")).count();
  }

  public int failed() {
    return (int) scenarios.stream().filter(s -> s.status().name().equals("FAILED")).count();
  }

  public int skipped() {
    return (int) scenarios.stream().filter(s -> s.status().name().equals("SKIPPED")).count();
  }

  public int errors() {
    return (int) scenarios.stream().filter(s -> s.status().name().equals("ERROR")).count();
  }

  public int exitCode() {
    if (errors() > 0) return 3;
    if (failed() > 0) return 1;
    return 0;
  }
}
