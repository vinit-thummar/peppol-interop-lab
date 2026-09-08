package io.github.vinitthummar.peppollab.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EphemeralPkiTest {
  @TempDir Path output;

  @Test
  void createsValidChainsAndDeletesPrivateMaterialOnly() throws Exception {
    Path privateDirectory = null;
    char[] password = null;
    try (EphemeralPki pki = EphemeralPki.create()) {
      privateDirectory = pki.directory();
      password = pki.password();
      assertThat(pki.senderKeyStorePath()).isRegularFile();
      assertThat(pki.receiverKeyStorePath()).isRegularFile();

      KeyStore store = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(pki.senderKeyStorePath())) {
        store.load(input, password);
      }
      assertThat(store.getCertificateChain("sender")).hasSize(2);
      X509Certificate sender = (X509Certificate) store.getCertificate("sender");
      sender.verify(pki.caCertificate().getPublicKey());
      assertThat(sender.getBasicConstraints()).isNegative();
      assertThat(pki.caCertificate().getBasicConstraints()).isNotNegative();

      pki.writePublicEvidence(output);
      assertThat(output.resolve("pki/ca.pem")).isRegularFile();
      assertThat(output.resolve("pki/sender.pem")).isRegularFile();
      assertThat(output.resolve("pki/receiver.pem")).isRegularFile();
      String summary = Files.readString(output.resolve("pki/certificates.json"));
      assertThat(summary).contains("\"privateKeysIncluded\" : false", "\"sha256\"")
          .doesNotContain(new String(password), ".p12", privateDirectory.toString());
    } finally {
      if (password != null) Arrays.fill(password, '\0');
    }

    assertThat(privateDirectory).isNotNull().doesNotExist();
    assertThat(output.resolve("pki/certificates.json")).isRegularFile();
  }
}
