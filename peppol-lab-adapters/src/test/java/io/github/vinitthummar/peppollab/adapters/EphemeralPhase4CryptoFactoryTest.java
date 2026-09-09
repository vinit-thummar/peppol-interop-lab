package io.github.vinitthummar.peppollab.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helger.phase4.crypto.ECryptoMode;
import io.github.vinitthummar.peppollab.core.EphemeralPki;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EphemeralPhase4CryptoFactoryTest {
  @Test
  void loadsBothLaboratoryIdentitiesForPhase4() throws Exception {
    try (EphemeralPki pki = EphemeralPki.create()) {
      verifyIdentity(pki.senderKeyStorePath(), "sender", pki.password(), pki.caCertificate(),
          pki.senderCertificate());
      verifyIdentity(pki.receiverKeyStorePath(), "receiver", pki.password(), pki.caCertificate(),
          pki.receiverCertificate());
    }
  }

  @Test
  void rejectsAnIncorrectAliasAndDisallowsUseAfterClose() throws Exception {
    try (EphemeralPki pki = EphemeralPki.create()) {
      assertThatThrownBy(() -> EphemeralPhase4CryptoFactory.load(
          pki.senderKeyStorePath(), "missing", pki.password(), pki.caCertificate()))
          .isInstanceOf(java.security.GeneralSecurityException.class)
          .hasMessageContaining("missing");

      EphemeralPhase4CryptoFactory factory = EphemeralPhase4CryptoFactory.load(
          pki.senderKeyStorePath(), "sender", pki.password(), pki.caCertificate());
      char[] beforeClose = factory.getKeyPasswordPerAliasCharArray("sender");
      assertThat(beforeClose).isNotEmpty().doesNotContain('\0');
      Arrays.fill(beforeClose, '\0');

      factory.close();
      assertThatThrownBy(() -> factory.getCrypto(ECryptoMode.ENCRYPT_SIGN))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed");
    }
  }

  private static void verifyIdentity(
      java.nio.file.Path keyStorePath,
      String alias,
      char[] password,
      X509Certificate trustAnchor,
      X509Certificate identityCertificate) throws Exception {
    try (EphemeralPhase4CryptoFactory factory = EphemeralPhase4CryptoFactory.load(
        keyStorePath, alias, password, trustAnchor)) {
      assertThat(factory.getKeyAlias()).isEqualTo(alias);
      assertThat(factory.getPrivateKeyEntry()).isNotNull();
      assertThat(factory.getKeyStore().getCertificate(alias)).isEqualTo(identityCertificate);
      assertThat(factory.getTrustStore().getCertificate("interop-lab-trust-anchor"))
          .isEqualTo(trustAnchor);
      assertThat(factory.getCrypto(ECryptoMode.ENCRYPT_SIGN)).isNotNull();
      assertThat(factory.getCrypto(ECryptoMode.DECRYPT_VERIFY)).isNotNull();
    } finally {
      Arrays.fill(password, '\0');
    }
  }
}
