package io.github.vinitthummar.peppollab.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class PeppolLabTest {
  @TempDir Path temporary;

  @Test
  void bundledScenariosValidate() {
    assertThat(new CommandLine(new PeppolLab()).execute("validate")).isZero();
  }

  @Test
  void bundledPreflightRunsAndWritesBothReports() {
    Path output = temporary.resolve("evidence");
    assertThat(new CommandLine(new PeppolLab()).execute("run", "--output", output.toString())).isZero();
    assertThat(output.resolve("results.json")).isRegularFile();
    assertThat(output.resolve("junit.xml")).isRegularFile();
  }

  @Test
  void initRefusesToOverwriteUserFiles() throws Exception {
    assertThat(new CommandLine(new PeppolLab()).execute("init", "--directory", temporary.toString())).isZero();
    String original = Files.readString(temporary.resolve("peppol-lab.yml"));
    assertThat(new CommandLine(new PeppolLab()).execute("init", "--directory", temporary.toString())).isEqualTo(2);
    assertThat(Files.readString(temporary.resolve("peppol-lab.yml"))).isEqualTo(original);
  }
}
