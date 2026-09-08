package io.github.vinitthummar.peppollab.core;

import java.util.Collection;
import java.util.Comparator;

public final class Redactor {
  private Redactor() {}

  public static String redact(String value, Collection<String> secrets) {
    if (value == null || secrets == null) return value;
    String result = value;
    for (String secret : secrets.stream()
        .filter(s -> s != null && !s.isBlank())
        .sorted(Comparator.comparingInt(String::length).reversed())
        .toList()) {
      result = result.replace(secret, "[REDACTED]");
    }
    return result;
  }
}
