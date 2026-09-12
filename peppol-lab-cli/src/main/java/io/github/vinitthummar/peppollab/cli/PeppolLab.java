package io.github.vinitthummar.peppollab.cli;

import io.github.vinitthummar.peppollab.api.*;
import io.github.vinitthummar.peppollab.core.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "peppol-lab", mixinStandardHelpOptions = true,
    versionProvider = PeppolLab.VersionProvider.class,
    description = "Vendor-neutral Peppol interoperability preflight",
    subcommands = {PeppolLab.Init.class, PeppolLab.Doctor.class, PeppolLab.Validate.class,
        PeppolLab.ListScenarios.class, PeppolLab.Run.class})
public final class PeppolLab implements Runnable {
  public static void main(String[] args) {
    System.exit(new CommandLine(new PeppolLab()).execute(args));
  }

  @Override public void run() {
    new CommandLine(this).usage(System.out);
  }

  static final class VersionProvider implements IVersionProvider {
    @Override public String[] getVersion() {
      return new String[] {"peppol-lab " + BuildVersion.current()};
    }
  }

  @Command(name = "init", description = "Create a safe local starter configuration")
  static final class Init implements Callable<Integer> {
    @Option(names = "--directory", defaultValue = ".") Path directory;
    @Option(names = "--force", description = "Replace files created by this command") boolean force;

    @Override public Integer call() throws Exception {
      Files.createDirectories(directory);
      Path config = directory.resolve("peppol-lab.yml");
      Path scenario = directory.resolve("example-scenario.yml");
      if (!force && (Files.exists(config) || Files.exists(scenario))) {
        System.err.println("Refusing to overwrite existing starter files; use --force");
        return 2;
      }
      Files.writeString(config, CONFIG_TEMPLATE, StandardCharsets.UTF_8);
      Files.writeString(scenario, SCENARIO_TEMPLATE, StandardCharsets.UTF_8);
      System.out.println("Created " + config + " and " + scenario);
      return 0;
    }
  }

  @Command(name = "validate", description = "Validate scenario YAML without executing it")
  static final class Validate implements Callable<Integer> {
    @Parameters(arity = "0..*", paramLabel = "SCENARIO") List<Path> paths = new ArrayList<>();

    @Override public Integer call() {
      ScenarioLoader loader = new ScenarioLoader();
      try {
        List<Scenario> scenarios = paths.isEmpty() ? BuiltinCatalog.load(loader)
            : paths.stream().map(path -> {
              try { return loader.loadScenario(path); }
              catch (Exception ex) { throw new InvalidScenario(ex); }
            }).toList();
        scenarios.forEach(s -> System.out.println("VALID  " + s.metadata().name()));
        return 0;
      } catch (InvalidScenario ex) {
        System.err.println(ex.getCause().getMessage());
        return 2;
      } catch (Exception ex) {
        System.err.println(ex.getMessage());
        return 2;
      }
    }
  }

  @Command(name = "list", description = "List bundled scenario contracts")
  static final class ListScenarios implements Callable<Integer> {
    @Override public Integer call() throws Exception {
      for (Scenario scenario : BuiltinCatalog.load(new ScenarioLoader())) {
        System.out.printf("%-30s %-18s %s%n", scenario.metadata().name(), scenario.requires(),
            scenario.metadata().description());
      }
      return 0;
    }
  }

  @Command(name = "doctor", description = "Check the local runtime and installed adapters")
  static final class Doctor implements Callable<Integer> {
    @Override public Integer call() {
      int javaFeature = Runtime.version().feature();
      boolean javaOk = javaFeature >= 21;
      System.out.println(mark(javaOk) + " Java " + javaFeature + (javaOk ? "" : " (Java 21+ required)"));
      boolean docker = commandOk("docker", "info");
      System.out.println(mark(docker) + " Docker" + (docker ? "" : " (required for external container targets)"));
      try (AdapterRegistry registry = AdapterRegistry.load(); EmbeddedLab lab = EmbeddedLab.start()) {
        System.out.println(mark(!registry.all().isEmpty()) + " adapters: "
            + registry.all().stream().map(TargetAdapter::id).sorted().toList());
        System.out.println("OK  loopback HTTP and DNS fixtures: " + lab.runtimeValues().get("fixture:dns"));
        System.out.println("OK  per-run ephemeral PKI");
      } catch (Exception ex) {
        System.err.println("ERR fixtures/adapters: " + ex.getMessage());
        return 3;
      }
      return javaOk ? 0 : 3;
    }

    private static String mark(boolean ok) { return ok ? "OK " : "WARN "; }
    private static boolean commandOk(String... command) {
      try {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
      } catch (Exception ex) { return false; }
    }
  }

  @Command(name = "run", description = "Run bundled or supplied scenarios")
  static final class Run implements Callable<Integer> {
    @Option(names = {"-c", "--config"}) Path configPath;
    @Option(names = {"-o", "--output"}, defaultValue = "reports") Path output;
    @Option(names = "--allow-production") boolean allowProduction;
    @Option(names = "--no-fixtures") boolean noFixtures;
    @Parameters(arity = "0..*", paramLabel = "SCENARIO") List<Path> paths = new ArrayList<>();

    @Override public Integer call() {
      ScenarioLoader loader = new ScenarioLoader();
      try {
        LabConfig config = configPath == null ? defaultConfig() : loader.loadConfig(configPath);
        List<Scenario> scenarios = paths.isEmpty() ? BuiltinCatalog.load(loader)
            : paths.stream().map(path -> {
              try { return loader.loadScenario(path); }
              catch (Exception ex) { throw new InvalidScenario(ex); }
            }).toList();
        try (EmbeddedLab lab = noFixtures ? null : EmbeddedLab.start();
             AdapterRegistry registry = AdapterRegistry.load()) {
          Map<String, String> runtime = lab == null ? Map.of() : lab.runtimeValues();
          if (lab != null) lab.writePublicEvidence(output);
          AdapterContext context = new AdapterContext(output, allowProduction, runtime);
          RunReport report = new ScenarioEngine(config, registry, context).run(scenarios);
          print(report);
          new ReportWriter().write(report, output);
          System.out.println("Evidence: " + output.toAbsolutePath());
          return report.exitCode();
        }
      } catch (InvalidScenario ex) {
        System.err.println(ex.getCause().getMessage());
        return 2;
      } catch (ScenarioValidationException | IllegalArgumentException ex) {
        System.err.println(ex.getMessage());
        return 2;
      } catch (Exception ex) {
        System.err.println("Infrastructure failure: " + ex.getMessage());
        return 3;
      }
    }

    private static void print(RunReport report) {
      report.scenarios().forEach(s -> System.out.printf("%-8s %s%s%n", s.status(), s.name(),
          s.status() == ScenarioStatus.PASSED ? "" : " — " + s.message()));
      System.out.printf("Summary: %d passed, %d failed, %d skipped, %d errors%n",
          report.passed(), report.failed(), report.skipped(), report.errors());
    }
  }

  private static LabConfig defaultConfig() {
    return new LabConfig(Map.of(
        "smp-fixture", new TargetConfig("standard-smp", URI.create("fixture:smp"), Map.of()),
        "as4-fixture", new TargetConfig(
            "direct-as4",
            URI.create("fixture:as4"),
            Map.of("fixtureEvidencePath", "/_lab/messages/{messageId}")),
        "as4-untrusted-sender-fixture", new TargetConfig(
            "direct-as4",
            URI.create("fixture:as4"),
            Map.of(
                "senderKeyStore", "fixture:pki-untrusted-sender",
                "senderKeyPassword", "fixture:pki-untrusted-password")),
        "dns-fixture", new TargetConfig("dns", URI.create("fixture:dns"), Map.of())));
  }

  private static final class InvalidScenario extends RuntimeException {
    InvalidScenario(Throwable cause) { super(cause); }
  }

  static final String CONFIG_TEMPLATE = """
      targets:
        smp-fixture:
          adapter: standard-smp
          baseUrl: fixture:smp
        as4-fixture:
          adapter: direct-as4
          baseUrl: fixture:as4
          options:
            fixtureEvidencePath: /_lab/messages/{messageId}
        as4-untrusted-sender-fixture:
          adapter: direct-as4
          baseUrl: fixture:as4
          options:
            senderKeyStore: fixture:pki-untrusted-sender
            senderKeyPassword: fixture:pki-untrusted-password
        dns-fixture:
          adapter: dns
          baseUrl: fixture:dns
      """;

  static final String SCENARIO_TEMPLATE = """
      apiVersion: peppol-interop-lab/v1alpha1
      kind: Scenario
      metadata:
        name: my-smp-check
        description: Check an SMP service group response
        tags: [smp, local]
      specifications:
        smp: 1.4.0
        as4: 2.0.3
        sbdh: 2.0.2
      requires: [SMP_LOOKUP]
      steps:
        - id: lookup
          target: smp-fixture
          action: smp.get
          with:
            path: /iso6523-actorid-upis::9915:receiver
          expect:
            status: 200
            outcome: SUCCESS
            contains: [ServiceGroup, 9915:receiver]
      """;
}
