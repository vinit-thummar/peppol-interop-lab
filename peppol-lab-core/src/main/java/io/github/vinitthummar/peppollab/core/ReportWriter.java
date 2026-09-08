package io.github.vinitthummar.peppollab.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReportWriter {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
      .enable(SerializationFeature.INDENT_OUTPUT);

  public void write(RunReport report, Path outputDirectory) throws IOException {
    Files.createDirectories(outputDirectory);
    json.writeValue(outputDirectory.resolve("results.json").toFile(), report);
    Files.writeString(outputDirectory.resolve("junit.xml"), junit(report), StandardCharsets.UTF_8);
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
}
