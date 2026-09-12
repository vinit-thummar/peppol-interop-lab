package io.github.vinitthummar.peppollab.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vinitthummar.peppollab.api.AdapterContext;
import io.github.vinitthummar.peppollab.api.AdapterException;
import io.github.vinitthummar.peppollab.api.AdapterRequest;
import io.github.vinitthummar.peppollab.api.AdapterResult;
import io.github.vinitthummar.peppollab.api.Capability;
import io.github.vinitthummar.peppollab.api.LabConfig;
import io.github.vinitthummar.peppollab.api.Scenario;
import io.github.vinitthummar.peppollab.api.ScenarioMetadata;
import io.github.vinitthummar.peppollab.api.ScenarioStatus;
import io.github.vinitthummar.peppollab.api.ScenarioStep;
import io.github.vinitthummar.peppollab.api.SpecificationVersions;
import io.github.vinitthummar.peppollab.api.StepExpectation;
import io.github.vinitthummar.peppollab.api.TargetAdapter;
import io.github.vinitthummar.peppollab.api.TargetConfig;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioEngineTest {
  @TempDir Path output;

  @Test
  void invokesCleanupEvenAfterAContractFailure() {
    AtomicBoolean cleaned = new AtomicBoolean();
    TargetAdapter adapter = adapter(cleaned, false);
    RunReport report = run(adapter);

    assertThat(report.failed()).isOne();
    assertThat(cleaned).isTrue();
  }

  @Test
  void reportsCleanupFailureAsInfrastructureError() {
    AtomicBoolean cleaned = new AtomicBoolean();
    RunReport report = run(adapter(cleaned, true));

    assertThat(cleaned).isTrue();
    assertThat(report.errors()).isOne();
    assertThat(report.scenarios()).singleElement().satisfies(result -> {
      assertThat(result.status()).isEqualTo(ScenarioStatus.ERROR);
      assertThat(result.steps()).extracting(StepRunResult::id).contains("cleanup:target");
    });
  }

  private RunReport run(TargetAdapter adapter) {
    AdapterRegistry registry = new AdapterRegistry().register(adapter);
    TargetConfig target = new TargetConfig("test", URI.create("http://127.0.0.1"), Map.of());
    Scenario scenario = new Scenario(
        Scenario.API_VERSION,
        "Scenario",
        new ScenarioMetadata("cleanup-contract", "cleanup", List.of("lifecycle")),
        new SpecificationVersions("1.4.0", "2.0.3", "2.0.2"),
        List.of(),
        List.of(new ScenarioStep(
            "failing-step",
            "target",
            "test.execute",
            Map.of(),
            new StepExpectation(200, "SUCCESS", List.of("required"), List.of(), Map.of(), null))));
    return new ScenarioEngine(
        new LabConfig(Map.of("target", target)),
        registry,
        new AdapterContext(output, false, Map.of()))
        .run(List.of(scenario));
  }

  private static TargetAdapter adapter(AtomicBoolean cleaned, boolean failCleanup) {
    return new TargetAdapter() {
      @Override public String id() { return "test"; }

      @Override public Set<Capability> capabilities(TargetConfig target) { return Set.of(); }

      @Override
      public AdapterResult execute(
          TargetConfig target, AdapterRequest request, AdapterContext context) {
        return new AdapterResult(
            "SUCCESS", 200, "missing", Map.of(), Duration.ZERO, List.of());
      }

      @Override
      public void cleanup(TargetConfig target, AdapterContext context) throws AdapterException {
        cleaned.set(true);
        if (failCleanup) throw new AdapterException("controlled cleanup failure", true);
      }
    };
  }
}
