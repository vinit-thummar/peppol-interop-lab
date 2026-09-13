package io.github.vinitthummar.peppollab.adapters;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vinitthummar.peppollab.api.AdapterContext;
import io.github.vinitthummar.peppollab.api.AdapterException;
import io.github.vinitthummar.peppollab.api.AdapterRequest;
import io.github.vinitthummar.peppollab.api.TargetConfig;
import io.github.vinitthummar.peppollab.core.DnsFixture;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DnsAdapterTest {
  @Test
  void doctorPerformsAReadOnlyResolverProbe() throws Exception {
    try (DnsFixture fixture = DnsFixture.start(URI.create("http://127.0.0.1:8080"))) {
      var checks = new DnsAdapter().doctor(
          new TargetConfig("dns", fixture.endpoint(), Map.of()),
          new AdapterContext(Path.of("reports"), false, Map.of()));

      assertTrue(checks.stream().allMatch(check -> check.successful()));
      assertTrue(checks.stream().anyMatch(check -> check.name().equals("DNS connectivity")));
    }
  }

  @Test
  void blocksProductionDiscoveryNamesUntilExplicitlyAllowed() {
    DnsAdapter adapter = new DnsAdapter();
    TargetConfig target = new TargetConfig("dns", URI.create("dns://127.0.0.1:9"), Map.of());
    AdapterRequest request = new AdapterRequest(
        "dns.lookup",
        Map.of("name", "B-hash.iso6523-actorid-upis.edelivery.tech.ec.europa.eu", "type", "NAPTR"),
        Duration.ofMillis(10));

    AdapterException blocked = assertThrows(
        AdapterException.class,
        () -> adapter.execute(target, request, new AdapterContext(Path.of("reports"), false, Map.of())));
    assertFalse(blocked.isInfrastructureFailure());
    assertTrue(blocked.getMessage().contains("--allow-production"));
  }
}
