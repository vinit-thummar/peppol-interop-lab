package io.github.vinitthummar.peppollab.adapters;

import com.helger.phase4.crypto.AS4CryptoFactoryInMemoryKeyStore;
import com.helger.phase4.crypto.ECryptoMode;
import com.helger.phase4.crypto.IAS4CryptoFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import org.apache.wss4j.common.crypto.Crypto;

/**
 * A closeable phase4 crypto factory backed by one laboratory PKCS#12 identity and an explicit
 * trust anchor. The caller's password is copied and the owned copy is erased on close.
 */
public final class EphemeralPhase4CryptoFactory implements IAS4CryptoFactory, AutoCloseable {
  private final char[] password;
  private final AS4CryptoFactoryInMemoryKeyStore delegate;
  private boolean closed;

  private EphemeralPhase4CryptoFactory(
      char[] password, AS4CryptoFactoryInMemoryKeyStore delegate) {
    this.password = password;
    this.delegate = delegate;
  }

  public static EphemeralPhase4CryptoFactory load(
      Path keyStorePath, String keyAlias, char[] keyPassword, X509Certificate trustAnchor)
      throws IOException, GeneralSecurityException {
    if (keyStorePath == null) throw new IllegalArgumentException("Key store path is required");
    if (keyAlias == null || keyAlias.isBlank()) {
      throw new IllegalArgumentException("Key alias is required");
    }
    if (keyPassword == null) throw new IllegalArgumentException("Key password is required");
    if (trustAnchor == null) throw new IllegalArgumentException("Trust anchor is required");

    char[] ownedPassword = keyPassword.clone();
    try {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (InputStream input = Files.newInputStream(keyStorePath)) {
        keyStore.load(input, ownedPassword);
      }
      if (!keyStore.isKeyEntry(keyAlias)) {
        throw new GeneralSecurityException(
            "PKCS#12 key entry is missing for alias '" + keyAlias + "'");
      }

      KeyStore trustStore = KeyStore.getInstance("PKCS12");
      trustStore.load(null, null);
      trustStore.setCertificateEntry("interop-lab-trust-anchor", trustAnchor);

      return new EphemeralPhase4CryptoFactory(
          ownedPassword,
          new AS4CryptoFactoryInMemoryKeyStore(
              keyStore, keyAlias, ownedPassword, trustStore));
    } catch (IOException | GeneralSecurityException | RuntimeException ex) {
      Arrays.fill(ownedPassword, '\0');
      throw ex;
    }
  }

  @Override
  public Crypto getCrypto(ECryptoMode mode) {
    requireOpen();
    return delegate.getCrypto(mode);
  }

  @Override
  public KeyStore getKeyStore() {
    requireOpen();
    return delegate.getKeyStore();
  }

  @Override
  public KeyStore.PrivateKeyEntry getPrivateKeyEntry() {
    requireOpen();
    return delegate.getPrivateKeyEntry();
  }

  @Override
  public String getKeyAlias() {
    requireOpen();
    return delegate.getKeyAlias();
  }

  @Override
  public char[] getKeyPasswordPerAliasCharArray(String searchKeyAlias) {
    requireOpen();
    char[] value = delegate.getKeyPasswordPerAliasCharArray(searchKeyAlias);
    return value == null ? null : value.clone();
  }

  @Override
  public KeyStore getTrustStore() {
    requireOpen();
    return delegate.getTrustStore();
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Phase4 crypto factory is closed");
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      Arrays.fill(password, '\0');
    }
  }
}
