package io.github.vinitthummar.peppollab.core;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class ProductionGuard {
  private static final Set<String> PRODUCTION_SUFFIXES =
      Set.of("prod.tech.peppol.org", "edelivery.tech.ec.europa.eu");

  private ProductionGuard() {}

  public static void check(URI uri, boolean allowProduction) {
    if (allowProduction || uri == null || uri.getHost() == null) return;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (PRODUCTION_SUFFIXES.stream().anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix))) {
      throw new IllegalArgumentException(
          "Production target '" + host + "' is blocked; pass --allow-production explicitly");
    }
  }
}
