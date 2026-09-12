package io.github.vinitthummar.peppollab.core;

import io.github.vinitthummar.peppollab.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ScenarioEngine {
  private final LabConfig config;
  private final AdapterRegistry adapters;
  private final AdapterContext context;

  public ScenarioEngine(LabConfig config, AdapterRegistry adapters, AdapterContext context) {
    this.config = config;
    this.adapters = adapters;
    this.context = context;
  }

  public RunReport run(List<Scenario> scenarios) {
    Instant started = Instant.now();
    List<ScenarioRunResult> results = scenarios.stream().map(this::runOne).toList();
    return new RunReport("0.1.0-SNAPSHOT", started, Instant.now(), results);
  }

  private ScenarioRunResult runOne(Scenario scenario) {
    Instant started = Instant.now();
    Set<Capability> available = new HashSet<>();
    try {
      for (ScenarioStep step : scenario.steps()) {
        TargetConfig target = requireTarget(step.target());
        ProductionGuard.check(resolve(target.baseUrl()), context.allowProduction());
        available.addAll(adapters.require(target.adapter()).capabilities(target));
      }
    } catch (RuntimeException ex) {
      return result(scenario, ScenarioStatus.ERROR, ex.getMessage(), started, List.of());
    }

    List<Capability> missing = scenario.requires().stream().filter(c -> !available.contains(c)).toList();
    if (!missing.isEmpty()) {
      return result(scenario, ScenarioStatus.SKIPPED, "Missing capabilities: " + missing, started, List.of());
    }

    List<StepRunResult> steps = new ArrayList<>();
    ScenarioStatus scenarioStatus = ScenarioStatus.PASSED;
    String message = "All expectations passed";
    for (ScenarioStep step : scenario.steps()) {
      TargetConfig target = requireTarget(step.target());
      TargetAdapter adapter = adapters.require(target.adapter());
      try {
        long timeoutMs = number(step.with().get("timeoutMs"), 10_000L);
        AdapterResult actual = adapter.execute(
            target, new AdapterRequest(step.action(), step.with(), Duration.ofMillis(timeoutMs)), context);
        if ("SKIPPED".equalsIgnoreCase(actual.outcome())) {
          steps.add(new StepRunResult(step.id(), ScenarioStatus.SKIPPED, actual.body(), actual, List.of()));
          scenarioStatus = ScenarioStatus.SKIPPED;
          message = actual.body();
          break;
        }
        List<String> failures = assertResult(step.expect(), actual);
        ScenarioStatus status = failures.isEmpty() ? ScenarioStatus.PASSED : ScenarioStatus.FAILED;
        steps.add(new StepRunResult(step.id(), status,
            failures.isEmpty() ? "Expectations passed" : String.join("; ", failures), actual, failures));
        if (!failures.isEmpty()) {
          scenarioStatus = ScenarioStatus.FAILED;
          message = "Step '" + step.id() + "' failed";
          break;
        }
      } catch (AdapterException ex) {
        ScenarioStatus status = ex.isInfrastructureFailure() ? ScenarioStatus.ERROR : ScenarioStatus.FAILED;
        steps.add(new StepRunResult(step.id(), status, ex.getMessage(), null, List.of(ex.getMessage())));
        scenarioStatus = status;
        message = ex.getMessage();
        break;
      }
    }
    for (String targetName : scenario.steps().stream()
        .map(ScenarioStep::target)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))) {
      TargetConfig target = requireTarget(targetName);
      try {
        adapters.require(target.adapter()).cleanup(target, context);
      } catch (AdapterException ex) {
        String cleanupMessage = "Cleanup failed for target '" + targetName + "': " + ex.getMessage();
        steps.add(new StepRunResult(
            "cleanup:" + targetName,
            ScenarioStatus.ERROR,
            cleanupMessage,
            null,
            List.of(cleanupMessage)));
        scenarioStatus = ScenarioStatus.ERROR;
        message = cleanupMessage;
      }
    }
    return result(scenario, scenarioStatus, message, started, steps);
  }

  private List<String> assertResult(StepExpectation expected, AdapterResult actual) {
    List<String> failures = new ArrayList<>();
    if (expected.status() != null && !expected.status().equals(actual.statusCode())) {
      failures.add("expected status " + expected.status() + " but was " + actual.statusCode());
    }
    if (expected.outcome() != null && !expected.outcome().equalsIgnoreCase(actual.outcome())) {
      failures.add("expected outcome " + expected.outcome() + " but was " + actual.outcome());
    }
    expected.contains().stream().filter(v -> !actual.body().contains(v))
        .forEach(v -> failures.add("body did not contain '" + v + "'"));
    expected.notContains().stream().filter(actual.body()::contains)
        .forEach(v -> failures.add("body unexpectedly contained '" + v + "'"));
    expected.headers().forEach((name, value) -> {
      String actualValue = actual.headers().entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(name)).map(java.util.Map.Entry::getValue)
          .findFirst().orElse(null);
      if (!value.equals(actualValue)) failures.add("expected header " + name + "=" + value + " but was " + actualValue);
    });
    if (expected.maxDurationMs() != null && actual.duration().toMillis() > expected.maxDurationMs()) {
      failures.add("duration " + actual.duration().toMillis() + "ms exceeded " + expected.maxDurationMs() + "ms");
    }
    return failures;
  }

  private TargetConfig requireTarget(String name) {
    TargetConfig target = config.targets().get(name);
    if (target == null) throw new IllegalArgumentException("Scenario references unknown target '" + name + "'");
    return new TargetConfig(target.adapter(), resolve(target.baseUrl()), target.options());
  }

  private java.net.URI resolve(java.net.URI uri) {
    if (uri != null && "fixture".equalsIgnoreCase(uri.getScheme())) {
      String value = context.runtimeValues().get(uri.toString());
      if (value == null) throw new IllegalArgumentException("Fixture is unavailable: " + uri);
      return java.net.URI.create(value);
    }
    return uri;
  }

  private static long number(Object value, long fallback) {
    return value instanceof Number number ? number.longValue() : fallback;
  }

  private static ScenarioRunResult result(Scenario scenario, ScenarioStatus status, String message,
      Instant started, List<StepRunResult> steps) {
    return new ScenarioRunResult(scenario.metadata().name(), status, message,
        Duration.between(started, Instant.now()), steps);
  }
}
