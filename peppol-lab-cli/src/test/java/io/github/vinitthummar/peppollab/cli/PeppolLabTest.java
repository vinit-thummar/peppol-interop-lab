package io.github.vinitthummar.peppollab.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vinitthummar.peppollab.core.BuildVersion;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class PeppolLabTest {
  @TempDir Path temporary;

  @Test
  void reportsTheEmbeddedBuildVersion() {
    StringWriter output = new StringWriter();
    CommandLine command = new CommandLine(new PeppolLab());
    command.setOut(new PrintWriter(output));

    assertThat(command.execute("--version")).isZero();
    assertThat(output.toString()).isEqualTo("peppol-lab " + BuildVersion.current() + "\n");
  }

  @Test
  void bundledScenariosValidate() {
    assertThat(new CommandLine(new PeppolLab()).execute("validate")).isZero();
  }

  @Test
  void doctorChecksConfiguredTargetsAndFixtureAvailability() throws Exception {
    Path config = temporary.resolve("doctor.yml");
    Files.writeString(config, """
        targets:
          public-smp:
            adapter: standard-smp
            baseUrl: fixture:smp
        """);

    CommandLine command = new CommandLine(new PeppolLab());
    assertThat(command.execute("doctor", "--config", config.toString())).isZero();
    assertThat(command.execute(
        "doctor", "--config", config.toString(), "--no-fixtures")).isEqualTo(3);
  }

  @Test
  void doctorTreatsUnreadableConfigurationAsInvalidInput() {
    assertThat(new CommandLine(new PeppolLab()).execute(
        "doctor", "--config", temporary.resolve("missing.yml").toString())).isEqualTo(2);
  }

  @Test
  void bundledPreflightRunsAndWritesBothReports() throws Exception {
    Path output = temporary.resolve("evidence");
    assertThat(new CommandLine(new PeppolLab()).execute("run", "--output", output.toString())).isZero();
    assertThat(output.resolve("results.json")).isRegularFile();
    assertThat(output.resolve("junit.xml")).isRegularFile();
    assertThat(output.resolve("route-summary.json")).isRegularFile();
    assertThat(output.resolve("route-summary.txt")).isRegularFile();
    assertThat(output.resolve("pki/certificates.json")).isRegularFile();
    assertThat(output.resolve("pki/untrusted/certificates.json")).isRegularFile();
    String results = Files.readString(output.resolve("results.json"));
    assertThat(results)
        .contains(
            "as4-signed-encrypted-success",
            "\"signed\" : true",
            "\"encrypted\" : true",
            "\"receiptReferencesVerified\" : true",
            "as4-untrusted-sender-rejection",
            "\"receiptReferencesVerified\" : false",
            "as4-duplicate-message-id",
            "EBMS:4001",
            "as4-payload-integrity",
            "EBMS:0102",
            "dns-naptr-success",
            "dns-nxdomain",
            "dns-servfail",
            "dns-controlled-timeout",
            "peppol-route-proof",
            "receiverCertificateSha256",
            "payloadSha256")
        .doesNotContain(
            "receiverCertificateBase64", "pki-password", ".p12", "PRIVATE KEY");
    assertThat(Files.readString(output.resolve("route-summary.txt")))
        .contains(
            "PEPPOL ROUTE PROOF",
            "PASSED  peppol-route-proof",
            "discover-smp  [SUCCESS]",
            "retrieve-metadata  [SUCCESS]",
            "deliver-as4  [SUCCESS]",
            "route-dns-nxdomain",
            "observed-failure=discover-smp",
            "route-smp-participant-missing",
            "observed-failure=retrieve-metadata",
            "route-as4-http-failure",
            "observed-failure=deliver-as4");
    assertThat(Files.readString(output.resolve("route-summary.json")))
        .contains(
            "receiverCertificateSha256",
            "receiptMessageId",
            "NXDOMAIN",
            "INVALID_METADATA",
            "HTTP_ERROR")
        .doesNotContain("receiverCertificateBase64", "PRIVATE KEY");
  }

  @Test
  void initRefusesToOverwriteUserFiles() throws Exception {
    assertThat(new CommandLine(new PeppolLab()).execute("init", "--directory", temporary.toString())).isZero();
    String original = Files.readString(temporary.resolve("peppol-lab.yml"));
    assertThat(new CommandLine(new PeppolLab()).execute("init", "--directory", temporary.toString())).isEqualTo(2);
    assertThat(Files.readString(temporary.resolve("peppol-lab.yml"))).isEqualTo(original);
  }
}
