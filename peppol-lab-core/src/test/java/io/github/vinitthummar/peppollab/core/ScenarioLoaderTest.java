package io.github.vinitthummar.peppollab.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScenarioLoaderTest {
  private final ScenarioLoader loader = new ScenarioLoader();

  @Test
  void loadsAValidScenario() throws Exception {
    var scenario = loader.loadScenario(stream(validYaml()), "test");
    assertThat(scenario.metadata().name()).isEqualTo("valid-contract");
    assertThat(scenario.steps()).hasSize(1);
  }

  @Test
  void rejectsAnUnknownApiVersion() {
    assertThatThrownBy(() -> loader.loadScenario(stream(validYaml().replace(
        "peppol-interop-lab/v1alpha1", "unknown/v9")), "test"))
        .isInstanceOf(ScenarioValidationException.class)
        .hasMessageContaining("apiVersion");
  }

  @Test
  void rejectsUnknownFields() {
    assertThatThrownBy(() -> loader.loadScenario(stream(validYaml() + "unexpected: true\n"), "test"))
        .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
  }

  private static ByteArrayInputStream stream(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String validYaml() {
    return """
        apiVersion: peppol-interop-lab/v1alpha1
        kind: Scenario
        metadata: { name: valid-contract, description: valid, tags: [smp] }
        specifications: { smp: "1.4.0", as4: "2.0.3", sbdh: "2.0.2" }
        requires: [SMP_LOOKUP]
        steps:
          - id: lookup
            target: smp
            action: smp.get
            expect: { status: 200 }
        """;
  }
}
