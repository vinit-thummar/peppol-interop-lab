package io.github.vinitthummar.peppollab.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ReportWriter {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
      .enable(SerializationFeature.INDENT_OUTPUT);

  public void write(RunReport report, Path outputDirectory) throws IOException {
    Files.createDirectories(outputDirectory);
    json.writeValue(outputDirectory.resolve("results.json").toFile(), report);
    Files.writeString(outputDirectory.resolve("junit.xml"), junit(report), StandardCharsets.UTF_8);
    List<RouteSummary> routes = routeSummaries(report);
    json.writeValue(outputDirectory.resolve("route-summary.json").toFile(), routes);
    Files.writeString(
        outputDirectory.resolve("route-summary.txt"), routeText(routes), StandardCharsets.UTF_8);
  }

  private static List<RouteSummary> routeSummaries(RunReport report) {
    return report.scenarios().stream()
        .filter(scenario -> scenario.tags().contains("route-proof"))
        .map(ReportWriter::routeSummary)
        .toList();
  }

  private static RouteSummary routeSummary(ScenarioRunResult scenario) {
    List<RouteHop> hops = scenario.steps().stream().map(step -> {
      var actual = step.adapterResult();
      String outcome = actual == null ? step.status().name() : actual.outcome();
      long durationMs = actual == null ? 0 : actual.duration().toMillis();
      Map<String, String> outputs = actual == null ? Map.of() : actual.outputs();
      String observation = actual == null || "SUCCESS".equalsIgnoreCase(outcome)
          ? null
          : abbreviated(actual.body(), 240);
      return new RouteHop(step.id(), step.status().name(), outcome, durationMs, outputs, observation);
    }).toList();
    String observedFailureHop = hops.stream()
        .filter(hop -> !"SUCCESS".equalsIgnoreCase(hop.outcome()))
        .map(RouteHop::id)
        .findFirst()
        .orElse(null);
    return new RouteSummary(
        scenario.name(), scenario.status().name(), observedFailureHop,
        scenario.duration().toMillis(), hops);
  }

  private static String routeText(List<RouteSummary> routes) {
    StringBuilder text = new StringBuilder("PEPPOL ROUTE PROOF\n");
    if (routes.isEmpty()) return text.append("No route-proof scenarios were executed.\n").toString();
    for (RouteSummary route : routes) {
      text.append('\n').append(route.contractStatus()).append("  ").append(route.scenario())
          .append("  ").append(route.durationMs()).append(" ms");
      if (route.observedFailureHop() != null) {
        text.append("  observed-failure=").append(route.observedFailureHop());
      }
      text.append('\n');
      for (RouteHop hop : route.hops()) {
        text.append("  ").append(hop.contractStatus()).append("  ").append(hop.id())
            .append("  [").append(hop.outcome()).append("]  ").append(hop.durationMs())
            .append(" ms");
        String decision = decision(hop.outputs());
        if (!decision.isBlank()) text.append("  ").append(decision);
        if (hop.observation() != null && !hop.observation().isBlank()) {
          text.append("  ").append(hop.observation());
        }
        text.append('\n');
      }
    }
    return text.toString();
  }

  private static String decision(Map<String, String> outputs) {
    for (String key : List.of(
        "smpBaseUrl", "endpointUrl", "receiverCertificateSha256", "messageId",
        "receiptMessageId", "rcode")) {
      String value = outputs.get(key);
      if (value != null) return key + "=" + value;
    }
    return "";
  }

  private static String abbreviated(String value, int limit) {
    if (value == null) return null;
    String singleLine = value.replaceAll("\\s+", " ").strip();
    return singleLine.length() <= limit ? singleLine : singleLine.substring(0, limit - 1) + "…";
  }

  private static String junit(RunReport report) {
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<testsuite name=\"peppol-interop-lab\" tests=\"").append(report.scenarios().size())
        .append("\" failures=\"").append(report.failed()).append("\" errors=\"")
        .append(report.errors()).append("\" skipped=\"").append(report.skipped()).append("\">\n");
    for (ScenarioRunResult scenario : report.scenarios()) {
      xml.append("  <testcase name=\"").append(escape(scenario.name())).append("\" time=\"")
          .append(scenario.duration().toMillis() / 1000.0).append("\">\n");
      switch (scenario.status()) {
        case FAILED -> xml.append("    <failure message=\"").append(escape(scenario.message())).append("\"/>\n");
        case ERROR -> xml.append("    <error message=\"").append(escape(scenario.message())).append("\"/>\n");
        case SKIPPED -> xml.append("    <skipped message=\"").append(escape(scenario.message())).append("\"/>\n");
        default -> { }
      }
      xml.append("  </testcase>\n");
    }
    return xml.append("</testsuite>\n").toString();
  }

  private static String escape(String value) {
    if (value == null) return "";
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;");
  }

  private record RouteSummary(
      String scenario,
      String contractStatus,
      String observedFailureHop,
      long durationMs,
      List<RouteHop> hops) {}

  private record RouteHop(
      String id,
      String contractStatus,
      String outcome,
      long durationMs,
      Map<String, String> outputs,
      String observation) {}
}
