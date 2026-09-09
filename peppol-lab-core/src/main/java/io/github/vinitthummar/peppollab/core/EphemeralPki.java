package io.github.vinitthummar.peppollab.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Per-run CA and endpoint identities. Private material exists only in a private temp directory. */
public final class EphemeralPki implements AutoCloseable {
  private static final BouncyCastleProvider BC = new BouncyCastleProvider();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final ObjectMapper JSON =
      new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.INDENT_OUTPUT);

  private final Path directory;
  private final Path caCertificatePath;
  private final Path senderCertificatePath;
  private final Path receiverCertificatePath;
  private final Path senderKeyStorePath;
  private final Path receiverKeyStorePath;
  private final Path passwordPath;
  private final char[] password;
  private final X509Certificate caCertificate;
  private final X509Certificate senderCertificate;
  private final X509Certificate receiverCertificate;

  private EphemeralPki(
      Path directory,
      Path caCertificatePath,
      Path senderCertificatePath,
      Path receiverCertificatePath,
      Path senderKeyStorePath,
      Path receiverKeyStorePath,
      Path passwordPath,
      char[] password,
      X509Certificate caCertificate,
      X509Certificate senderCertificate,
      X509Certificate receiverCertificate) {
    this.directory = directory;
    this.caCertificatePath = caCertificatePath;
    this.senderCertificatePath = senderCertificatePath;
    this.receiverCertificatePath = receiverCertificatePath;
    this.senderKeyStorePath = senderKeyStorePath;
    this.receiverKeyStorePath = receiverKeyStorePath;
    this.passwordPath = passwordPath;
    this.password = password;
    this.caCertificate = caCertificate;
    this.senderCertificate = senderCertificate;
    this.receiverCertificate = receiverCertificate;
  }

  public static EphemeralPki create() throws IOException, GeneralSecurityException {
    Path directory = Files.createTempDirectory("peppol-lab-pki-");
    restrict(directory, true);
    try {
      KeyPair caKeys = keyPair();
      X509Certificate ca = issueCa(caKeys);
      KeyPair senderKeys = keyPair();
      KeyPair receiverKeys = keyPair();
      X509Certificate sender = issueLeaf("CN=sender.interop.invalid,O=Peppol Interop Lab", senderKeys, caKeys, ca);
      X509Certificate receiver = issueLeaf("CN=receiver.interop.invalid,O=Peppol Interop Lab", receiverKeys, caKeys, ca);
      char[] password = randomPassword();

      Path caPath = directory.resolve("ca.pem");
      Path senderCertificatePath = directory.resolve("sender.pem");
      Path receiverCertificatePath = directory.resolve("receiver.pem");
      Path senderPath = directory.resolve("sender.p12");
      Path receiverPath = directory.resolve("receiver.p12");
      Path passwordPath = directory.resolve("password.txt");
      Files.writeString(caPath, pem(ca), StandardCharsets.US_ASCII);
      Files.writeString(senderCertificatePath, pem(sender), StandardCharsets.US_ASCII);
      Files.writeString(receiverCertificatePath, pem(receiver), StandardCharsets.US_ASCII);
      writeKeyStore(senderPath, "sender", senderKeys, sender, ca, password);
      writeKeyStore(receiverPath, "receiver", receiverKeys, receiver, ca, password);
      try (var writer = Files.newBufferedWriter(passwordPath, StandardCharsets.UTF_8)) {
        writer.write(password);
      }
      restrict(caPath, false);
      restrict(senderCertificatePath, false);
      restrict(receiverCertificatePath, false);
      restrict(senderPath, false);
      restrict(receiverPath, false);
      restrict(passwordPath, false);
      return new EphemeralPki(
          directory,
          caPath,
          senderCertificatePath,
          receiverCertificatePath,
          senderPath,
          receiverPath,
          passwordPath,
          password,
          ca,
          sender,
          receiver);
    } catch (IOException | GeneralSecurityException | RuntimeException ex) {
      deleteTree(directory);
      throw ex;
    }
  }

  public Path directory() {
    return directory;
  }

  public Path senderKeyStorePath() {
    return senderKeyStorePath;
  }

  public Path receiverKeyStorePath() {
    return receiverKeyStorePath;
  }

  public Path senderCertificatePath() {
    return senderCertificatePath;
  }

  public Path receiverCertificatePath() {
    return receiverCertificatePath;
  }

  public char[] password() {
    return password.clone();
  }

  public X509Certificate caCertificate() {
    return caCertificate;
  }

  public X509Certificate senderCertificate() {
    return senderCertificate;
  }

  public X509Certificate receiverCertificate() {
    return receiverCertificate;
  }

  public Map<String, String> runtimeValues() {
    return Map.of(
        "fixture:pki-ca", caCertificatePath.toUri().toString(),
        "fixture:pki-sender-cert", senderCertificatePath.toUri().toString(),
        "fixture:pki-receiver-cert", receiverCertificatePath.toUri().toString(),
        "fixture:pki-sender", senderKeyStorePath.toUri().toString(),
        "fixture:pki-receiver", receiverKeyStorePath.toUri().toString(),
        "fixture:pki-password", passwordPath.toUri().toString());
  }

  /** Writes only public certificates and fingerprints; private keys and passwords never leave temp storage. */
  public void writePublicEvidence(Path outputDirectory) throws IOException, GeneralSecurityException {
    Path evidenceDirectory = outputDirectory.resolve("pki");
    Files.createDirectories(evidenceDirectory);
    Files.writeString(evidenceDirectory.resolve("ca.pem"), pem(caCertificate), StandardCharsets.US_ASCII);
    Files.writeString(evidenceDirectory.resolve("sender.pem"), pem(senderCertificate), StandardCharsets.US_ASCII);
    Files.writeString(evidenceDirectory.resolve("receiver.pem"), pem(receiverCertificate), StandardCharsets.US_ASCII);
    Map<String, Object> summary = Map.of(
        "ephemeral", true,
        "privateKeysIncluded", false,
        "ca", certificateSummary(caCertificate),
        "sender", certificateSummary(senderCertificate),
        "receiver", certificateSummary(receiverCertificate));
    JSON.writeValue(evidenceDirectory.resolve("certificates.json").toFile(), summary);
  }

  private static KeyPair keyPair() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048, RANDOM);
    return generator.generateKeyPair();
  }

  private static X509Certificate issueCa(KeyPair keys) throws GeneralSecurityException {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    X500Name subject = new X500Name("CN=Peppol Interop Lab Ephemeral CA,O=Peppol Interop Lab");
    JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
        subject,
        serial(),
        java.util.Date.from(now.minus(1, ChronoUnit.MINUTES)),
        java.util.Date.from(now.plus(24, ChronoUnit.HOURS)),
        subject,
        keys.getPublic());
    try {
      JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
      builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
      builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
      builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(keys.getPublic()));
      return convert(builder.build(signer(keys)));
    } catch (IOException | OperatorCreationException ex) {
      throw new GeneralSecurityException("Unable to issue ephemeral CA", ex);
    }
  }

  private static X509Certificate issueLeaf(
      String distinguishedName,
      KeyPair leafKeys,
      KeyPair caKeys,
      X509Certificate caCertificate)
      throws GeneralSecurityException {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
        caCertificate,
        serial(),
        java.util.Date.from(now.minus(1, ChronoUnit.MINUTES)),
        java.util.Date.from(now.plus(8, ChronoUnit.HOURS)),
        new X500Name(distinguishedName),
        leafKeys.getPublic());
    try {
      JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
      String commonName = distinguishedName.substring(3, distinguishedName.indexOf(','));
      builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
      builder.addExtension(
          Extension.keyUsage,
          true,
          new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(new KeyPurposeId[] {KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth}));
      builder.addExtension(
          Extension.authorityKeyIdentifier,
          false,
          extensions.createAuthorityKeyIdentifier(caCertificate));
      builder.addExtension(
          Extension.subjectKeyIdentifier,
          false,
          extensions.createSubjectKeyIdentifier(leafKeys.getPublic()));
      builder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(new GeneralName(GeneralName.dNSName, commonName)));
      X509Certificate certificate = convert(builder.build(signer(caKeys)));
      certificate.verify(caCertificate.getPublicKey());
      return certificate;
    } catch (IOException | OperatorCreationException ex) {
      throw new GeneralSecurityException("Unable to issue ephemeral endpoint certificate", ex);
    }
  }

  private static ContentSigner signer(KeyPair keys) throws OperatorCreationException {
    return new JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(keys.getPrivate());
  }

  private static X509Certificate convert(X509CertificateHolder holder) throws GeneralSecurityException {
    X509Certificate certificate = new JcaX509CertificateConverter().setProvider(BC).getCertificate(holder);
    certificate.checkValidity();
    return certificate;
  }

  private static void writeKeyStore(
      Path path,
      String alias,
      KeyPair keys,
      X509Certificate leaf,
      X509Certificate ca,
      char[] password)
      throws IOException, GeneralSecurityException {
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, password);
    store.setKeyEntry(alias, keys.getPrivate(), password, new Certificate[] {leaf, ca});
    try (var output = Files.newOutputStream(path)) {
      store.store(output, password);
    }
  }

  private static char[] randomPassword() {
    char[] alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_".toCharArray();
    char[] value = new char[32];
    for (int i = 0; i < value.length; i++) value[i] = alphabet[RANDOM.nextInt(alphabet.length)];
    return value;
  }

  private static BigInteger serial() {
    return new BigInteger(160, RANDOM).abs().add(BigInteger.ONE);
  }

  private static String pem(X509Certificate certificate) throws GeneralSecurityException {
    try {
      String content = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
          .encodeToString(certificate.getEncoded());
      return "-----BEGIN CERTIFICATE-----\n" + content + "\n-----END CERTIFICATE-----\n";
    } catch (java.security.cert.CertificateEncodingException ex) {
      throw new GeneralSecurityException("Unable to encode public certificate", ex);
    }
  }

  private static Map<String, Object> certificateSummary(X509Certificate certificate)
      throws GeneralSecurityException {
    try {
      String fingerprint = HexFormat.of().withUpperCase().formatHex(
          MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
      return Map.of(
          "subject", certificate.getSubjectX500Principal().getName(),
          "issuer", certificate.getIssuerX500Principal().getName(),
          "serialNumber", certificate.getSerialNumber().toString(16),
          "notBefore", certificate.getNotBefore().toInstant().toString(),
          "notAfter", certificate.getNotAfter().toInstant().toString(),
          "sha256", fingerprint);
    } catch (java.security.cert.CertificateEncodingException ex) {
      throw new GeneralSecurityException("Unable to fingerprint public certificate", ex);
    }
  }

  private static void restrict(Path path, boolean directory) throws IOException {
    try {
      Files.setPosixFilePermissions(
          path,
          directory
              ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
              : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // POSIX permissions are unavailable on some supported filesystems.
    }
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  @Override
  public void close() throws IOException {
    java.util.Arrays.fill(password, '\0');
    deleteTree(directory);
  }
}
