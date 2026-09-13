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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioEngineTest {
  @TempDir Path output;

  @Test
  void invokesCleanupEvenAfterAContractFailure() {
    AtomicBoolean cleaned = new AtomicBoolean();
    TargetAdapter adapter = adapter(cleaned, false);
    RunReport report = run(adapter);

    assertThat(report.toolVersion()).isEqualTo(BuildVersion.current());
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

  @Test
  void passesStructuredOutputsOnlyToFollowingSteps() {
    AtomicReference<Map<String, Object>> received = new AtomicReference<>();
    TargetAdapter adapter = new TargetAdapter() {
      @Override public String id() { return "chain"; }

      @Override public Set<Capability> capabilities(TargetConfig target) { return Set.of(); }

      @Override
      public AdapterResult execute(
          TargetConfig target, AdapterRequest request, AdapterContext context) {
        if ("discover".equals(request.action())) {
          return new AdapterResult(
              "SUCCESS", 200, "discovered", Map.of(), Duration.ZERO, List.of(),
              Map.of("endpointUrl", "http://127.0.0.1:8081/as4", "participant", "9915:receiver"));
        }
        received.set(request.parameters());
        return new AdapterResult("SUCCESS", 200, "delivered", Map.of(), Duration.ZERO, List.of());
      }
    };
    Scenario scenario = scenario(List.of(
        new ScenarioStep("discover", "target", "discover", Map.of(), expectation("discovered")),
        new ScenarioStep(
            "deliver",
            "target",
            "deliver",
            Map.of(
                "endpointUrl", "${steps.discover.outputs.endpointUrl}",
                "recipient", "urn:test:${steps.discover.outputs.participant}"),
            expectation("delivered"))));

    RunReport report = run(adapter, scenario);

    assertThat(report.passed()).isOne();
    assertThat(received.get()).containsEntry("endpointUrl", "http://127.0.0.1:8081/as4")
        .containsEntry("recipient", "urn:test:9915:receiver");
  }

  @Test
  void checksProductionGuardAfterOutputResolution() {
    AtomicBoolean secondStepCalled = new AtomicBoolean();
    TargetAdapter adapter = new TargetAdapter() {
      @Override public String id() { return "chain"; }

      @Override public Set<Capability> capabilities(TargetConfig target) { return Set.of(); }

      @Override
      public AdapterResult execute(
          TargetConfig target, AdapterRequest request, AdapterContext context) {
        if ("discover".equals(request.action())) {
          return new AdapterResult(
              "SUCCESS", 200, "discovered", Map.of(), Duration.ZERO, List.of(),
              Map.of("endpointUrl", "https://smp.prod.tech.peppol.org/as4"));
        }
        secondStepCalled.set(true);
        return new AdapterResult("SUCCESS", 200, "delivered", Map.of(), Duration.ZERO, List.of());
      }
    };
    Scenario scenario = scenario(List.of(
        new ScenarioStep("discover", "target", "discover", Map.of(), expectation("discovered")),
        new ScenarioStep(
            "deliver", "target", "deliver",
            Map.of("endpointUrl", "${steps.discover.outputs.endpointUrl}"), expectation("delivered"))));

    RunReport report = run(adapter, scenario);

    assertThat(report.errors()).isOne();
    assertThat(secondStepCalled).isFalse();
    assertThat(report.scenarios().getFirst().message()).contains("--allow-production");
  }

  private RunReport run(TargetAdapter adapter) {
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
    return run(adapter, scenario);
  }

  private RunReport run(TargetAdapter adapter, Scenario scenario) {
    AdapterRegistry registry = new AdapterRegistry().register(adapter);
    TargetConfig target = new TargetConfig(adapter.id(), URI.create("http://127.0.0.1"), Map.of());
    return new ScenarioEngine(
        new LabConfig(Map.of("target", target)),
        registry,
        new AdapterContext(output, false, Map.of()))
        .run(List.of(scenario));
  }

  private static Scenario scenario(List<ScenarioStep> steps) {
    return new Scenario(
        Scenario.API_VERSION,
        "Scenario",
        new ScenarioMetadata("output-chain", "output chain", List.of("route-proof")),
        new SpecificationVersions("1.4.0", "2.0.3", "2.0.2"),
        List.of(),
        steps);
  }

  private static StepExpectation expectation(String body) {
    return new StepExpectation(200, "SUCCESS", List.of(body), List.of(), Map.of(), null);
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
