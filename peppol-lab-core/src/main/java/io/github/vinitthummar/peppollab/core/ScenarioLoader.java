package io.github.vinitthummar.peppollab.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.vinitthummar.peppollab.api.LabConfig;
import io.github.vinitthummar.peppollab.api.Scenario;
import io.github.vinitthummar.peppollab.api.ScenarioStep;
import io.github.vinitthummar.peppollab.api.TargetConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ScenarioLoader {
  private final ObjectMapper yaml =
      new ObjectMapper(new YAMLFactory())
          .findAndRegisterModules()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  public Scenario loadScenario(Path path) throws IOException, ScenarioValidationException {
    try (InputStream in = Files.newInputStream(path)) {
      return loadScenario(in, path.toString());
    }
  }

  public Scenario loadScenario(InputStream in, String source)
      throws IOException, ScenarioValidationException {
    Scenario scenario = yaml.readValue(in, Scenario.class);
    validate(scenario, source);
    return scenario;
  }

  public LabConfig loadConfig(Path path) throws IOException, ScenarioValidationException {
    try (InputStream in = Files.newInputStream(path)) {
      LabConfig config = yaml.readValue(in, LabConfig.class);
      List<String> violations = new ArrayList<>();
      if (config.targets().isEmpty()) violations.add("configuration must declare at least one target");
      for (Map.Entry<String, TargetConfig> entry : config.targets().entrySet()) {
        if (blank(entry.getKey())) violations.add("target names must not be blank");
        TargetConfig target = entry.getValue();
        if (target == null || blank(target.adapter())) {
          violations.add("target '" + entry.getKey() + "' must declare adapter");
        } else if (target.baseUrl() == null) {
          violations.add("target '" + entry.getKey() + "' must declare baseUrl");
        }
      }
      if (!violations.isEmpty()) throw new ScenarioValidationException(violations);
      return config;
    }
  }

  public void validate(Scenario scenario, String source) throws ScenarioValidationException {
    List<String> violations = new ArrayList<>();
    if (scenario == null) {
      violations.add(source + ": document is empty");
    } else {
      if (!Scenario.API_VERSION.equals(scenario.apiVersion())) {
        violations.add(source + ": apiVersion must be '" + Scenario.API_VERSION + "'");
      }
      if (!"Scenario".equals(scenario.kind())) violations.add(source + ": kind must be 'Scenario'");
      if (scenario.metadata() == null || blank(scenario.metadata().name())) {
        violations.add(source + ": metadata.name is required");
      }
      if (scenario.specifications() == null) {
        violations.add(source + ": specifications are required");
      }
      if (scenario.steps().isEmpty()) violations.add(source + ": at least one step is required");
      Set<String> precedingStepIds = new HashSet<>();
      for (int i = 0; i < scenario.steps().size(); i++) {
        ScenarioStep step = scenario.steps().get(i);
        String prefix = source + ": steps[" + i + "]";
        if (step == null) {
          violations.add(prefix + " must not be null");
          continue;
        }
        if (blank(step.id())) {
          violations.add(prefix + ".id is required");
        } else if (precedingStepIds.contains(step.id())) {
          violations.add(prefix + ".id must be unique; duplicate '" + step.id() + "'");
        }
        if (blank(step.target())) violations.add(prefix + ".target is required");
        if (blank(step.action())) violations.add(prefix + ".action is required");
        if (step.expect() == null) violations.add(prefix + ".expect is required");
        if (StepOutputReferences.hasMalformedReference(step.with())) {
          violations.add(prefix + " contains a malformed step-output reference");
        }
        for (StepOutputReferences.Reference reference : StepOutputReferences.findAll(step.with())) {
          if (!precedingStepIds.contains(reference.stepId())) {
            violations.add(prefix + " references step '" + reference.stepId()
                + "', but outputs may only come from a preceding step");
          }
        }
        if (!blank(step.id())) precedingStepIds.add(step.id());
      }
    }
    if (!violations.isEmpty()) throw new ScenarioValidationException(violations);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
