package io.github.vinitthummar.peppollab.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves deliberately small, non-executable references to outputs of preceding steps. */
final class StepOutputReferences {
  static final Pattern REFERENCE = Pattern.compile(
      "\\$\\{steps\\.([A-Za-z0-9_-]+)\\.outputs\\.([A-Za-z0-9_.-]+)}");

  private StepOutputReferences() {}

  static Map<String, Object> resolve(
      Map<String, Object> parameters, Map<String, Map<String, String>> outputsByStep) {
    Map<String, Object> resolved = new LinkedHashMap<>();
    parameters.forEach((name, value) -> resolved.put(name, resolveValue(value, outputsByStep)));
    return Map.copyOf(resolved);
  }

  static List<Reference> findAll(Object value) {
    List<Reference> references = new ArrayList<>();
    collect(value, references);
    return List.copyOf(references);
  }

  static boolean hasMalformedReference(Object value) {
    if (value instanceof String string) {
      return REFERENCE.matcher(string).replaceAll("").contains("${steps.");
    }
    if (value instanceof Map<?, ?> map) {
      return map.values().stream().anyMatch(StepOutputReferences::hasMalformedReference);
    }
    if (value instanceof List<?> list) {
      return list.stream().anyMatch(StepOutputReferences::hasMalformedReference);
    }
    return false;
  }

  private static Object resolveValue(Object value, Map<String, Map<String, String>> outputsByStep) {
    if (value instanceof String string) return resolveString(string, outputsByStep);
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> resolved = new LinkedHashMap<>();
      map.forEach((key, nested) -> resolved.put(String.valueOf(key), resolveValue(nested, outputsByStep)));
      return Map.copyOf(resolved);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(nested -> resolveValue(nested, outputsByStep)).toList();
    }
    return value;
  }

  private static String resolveString(
      String value, Map<String, Map<String, String>> outputsByStep) {
    Matcher matcher = REFERENCE.matcher(value);
    StringBuilder resolved = new StringBuilder();
    while (matcher.find()) {
      String stepId = matcher.group(1);
      String outputName = matcher.group(2);
      Map<String, String> outputs = outputsByStep.get(stepId);
      if (outputs == null) {
        throw new IllegalArgumentException("Step output references unavailable step '" + stepId + "'");
      }
      String replacement = outputs.get(outputName);
      if (replacement == null) {
        throw new IllegalArgumentException(
            "Step '" + stepId + "' did not produce output '" + outputName + "'");
      }
      matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }

  private static void collect(Object value, List<Reference> references) {
    if (value instanceof String string) {
      Matcher matcher = REFERENCE.matcher(string);
      while (matcher.find()) references.add(new Reference(matcher.group(1), matcher.group(2)));
    } else if (value instanceof Map<?, ?> map) {
      map.values().forEach(nested -> collect(nested, references));
    } else if (value instanceof List<?> list) {
      list.forEach(nested -> collect(nested, references));
    }
  }

  record Reference(String stepId, String outputName) {}
}
