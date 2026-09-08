package io.github.vinitthummar.peppollab.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityUtilitiesTest {
  @Test
  void redactsSecretsLongestFirst() {
    assertThat(Redactor.redact("token=abc123 and abc", List.of("abc", "abc123")))
        .isEqualTo("token=[REDACTED] and [REDACTED]");
  }

  @Test
  void blocksProductionPeppolTargetsByDefault() {
    URI production = URI.create("https://smp.prod.tech.peppol.org");
    assertThatThrownBy(() -> ProductionGuard.check(production, false))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--allow-production");
    ProductionGuard.check(production, true);
  }

  @Test
  void permitsLoopbackTargets() {
    ProductionGuard.check(URI.create("http://127.0.0.1:8080"), false);
  }
}
