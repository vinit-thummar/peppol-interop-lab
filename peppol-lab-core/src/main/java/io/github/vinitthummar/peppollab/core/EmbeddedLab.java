package io.github.vinitthummar.peppollab.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateEncodingException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Deterministic loopback-only fixtures used by the bundled preflight scenarios. */
public final class EmbeddedLab implements AutoCloseable {
  private final HttpServer smp;
  private final As4Fixture as4;
  private final DnsFixture dns;
  private final EphemeralPki pki;
  private final EphemeralPki untrustedPki;
  private final ExecutorService smpExecutor;

  private EmbeddedLab(
      HttpServer smp,
      As4Fixture as4,
      DnsFixture dns,
      EphemeralPki pki,
      EphemeralPki untrustedPki,
      ExecutorService smpExecutor) {
    this.smp = smp;
    this.as4 = as4;
    this.dns = dns;
    this.pki = pki;
    this.untrustedPki = untrustedPki;
    this.smpExecutor = smpExecutor;
  }

  public static EmbeddedLab start() throws IOException, GeneralSecurityException {
    HttpServer smp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService smpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    DnsFixture dns = null;
    EphemeralPki pki = null;
    EphemeralPki untrustedPki = null;
    As4Fixture as4 = null;
    try {
      dns = DnsFixture.start(java.net.URI.create("http://127.0.0.1:" + smp.getAddress().getPort()));
      pki = EphemeralPki.create();
      untrustedPki = EphemeralPki.create();
      as4 = loadAs4Provider().start(pki);
      EmbeddedLab lab = new EmbeddedLab(smp, as4, dns, pki, untrustedPki, smpExecutor);
      smp.createContext("/", lab::handleSmp);
      smp.setExecutor(smpExecutor);
      smp.start();
      return lab;
    } catch (IOException | GeneralSecurityException | RuntimeException ex) {
      smp.stop(0);
      if (as4 != null) {
        try {
          as4.close();
        } catch (IOException ignored) {
          // Preserve the original startup failure.
        }
      }
      if (dns != null) dns.close();
      if (pki != null) {
        try {
          pki.close();
        } catch (IOException ignored) {
          // Preserve the original startup failure.
        }
      }
      if (untrustedPki != null) {
        try {
          untrustedPki.close();
        } catch (IOException ignored) {
          // Preserve the original startup failure.
        }
      }
      smpExecutor.shutdownNow();
      throw ex;
    }
  }

  private static As4FixtureProvider loadAs4Provider() throws IOException {
    var providers = ServiceLoader.load(As4FixtureProvider.class).iterator();
    if (!providers.hasNext()) {
      throw new IOException("No AS4 fixture provider is installed");
    }
    As4FixtureProvider provider = providers.next();
    if (providers.hasNext()) {
      throw new IOException("Multiple AS4 fixture providers are installed");
    }
    return provider;
  }

  public Map<String, String> runtimeValues() {
    Map<String, String> values = new HashMap<>();
    values.put("fixture:smp", "http://127.0.0.1:" + smp.getAddress().getPort());
    values.put("fixture:as4", as4.endpoint().toString());
    values.put("fixture:dns", dns.endpoint().toString());
    values.putAll(pki.runtimeValues());
    Map<String, String> untrusted = untrustedPki.runtimeValues();
    values.put("fixture:pki-untrusted-ca", untrusted.get("fixture:pki-ca"));
    values.put("fixture:pki-untrusted-sender-cert", untrusted.get("fixture:pki-sender-cert"));
    values.put("fixture:pki-untrusted-sender", untrusted.get("fixture:pki-sender"));
    values.put("fixture:pki-untrusted-password", untrusted.get("fixture:pki-password"));
    return Map.copyOf(values);
  }

  public void writePublicEvidence(Path outputDirectory) throws IOException, GeneralSecurityException {
    pki.writePublicEvidence(outputDirectory);
    untrustedPki.writePublicEvidenceDirectory(outputDirectory.resolve("pki/untrusted"));
  }

  private void handleSmp(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getRawPath();
    String query = exchange.getRequestURI().getRawQuery();
    if (query != null && query.contains("mode=slow")) pause(300);
    if (query != null && query.contains("mode=error")) {
      respond(exchange, 500, "application/xml", "<error>controlled SMP failure</error>");
      return;
    }
    if (query != null && query.contains("mode=malformed")) {
      respond(exchange, 200, "application/xml", "<SignedServiceMetadata>");
      return;
    }
    if (path.contains("unknown")) {
      String resource = path.contains("/services/") ? "document" : "participant";
      respond(exchange, 404, "application/xml", "<error>" + resource + " not found</error>");
      return;
    }
    if (path.contains("/services/")) {
      String body = """
          <?xml version="1.0" encoding="UTF-8"?>
          <SignedServiceMetadata xmlns="http://busdox.org/serviceMetadata/publishing/1.0/"
                                 xmlns:id="http://busdox.org/transport/identifiers/1.0/">
            <ServiceMetadata>
              <ServiceInformation>
                <id:ParticipantIdentifier scheme="iso6523-actorid-upis">9915:receiver</id:ParticipantIdentifier>
                <id:DocumentIdentifier scheme="busdox-docid-qns">urn:oasis:names:specification:ubl:schema:xsd:Invoice-2</id:DocumentIdentifier>
                <ProcessList><Process>
                  <id:ProcessIdentifier scheme="cenbii-procid-ubl">urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</id:ProcessIdentifier>
                  <ServiceEndpointList><Endpoint transportProfile="peppol-transport-as4-v2_0">
                    <EndpointReference xmlns="http://www.w3.org/2005/08/addressing"><Address>%s</Address></EndpointReference>
                    <RequireBusinessLevelSignature>false</RequireBusinessLevelSignature>
                    <ServiceActivationDate>2026-01-01T00:00:00Z</ServiceActivationDate>
                    <ServiceExpirationDate>2099-12-31T23:59:59Z</ServiceExpirationDate>
                    <Certificate>%s</Certificate>
                    <ServiceDescription>Interop Lab fixture</ServiceDescription>
                    <TechnicalContactUrl>mailto:fixture@example.invalid</TechnicalContactUrl>
                  </Endpoint></ServiceEndpointList>
                </Process></ProcessList>
              </ServiceInformation>
            </ServiceMetadata>
          </SignedServiceMetadata>
          """.formatted(as4.endpoint(), receiverCertificateBase64());
      respond(exchange, 200, "application/xml", body);
      return;
    }
    String body = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ServiceGroup xmlns="http://busdox.org/serviceMetadata/publishing/1.0/"
                      xmlns:id="http://busdox.org/transport/identifiers/1.0/">
          <id:ParticipantIdentifier scheme="iso6523-actorid-upis">9915:receiver</id:ParticipantIdentifier>
          <ServiceMetadataReferenceCollection>
            <ServiceMetadataReference href="/iso6523-actorid-upis%3A%3A9915%3Areceiver/services/invoice"/>
          </ServiceMetadataReferenceCollection>
        </ServiceGroup>
        """;
    respond(exchange, 200, "application/xml", body);
  }

  private String receiverCertificateBase64() throws IOException {
    try {
      return Base64.getEncoder().encodeToString(pki.receiverCertificate().getEncoded());
    } catch (CertificateEncodingException ex) {
      throw new IOException("Unable to encode fixture receiver certificate", ex);
    }
  }

  private static void respond(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() throws IOException {
    smp.stop(0);
    IOException failure = null;
    try {
      as4.close();
    } catch (IOException ex) {
      failure = ex;
    }
    try {
      dns.close();
    } catch (RuntimeException ex) {
      IOException wrapped = new IOException("Failed to close DNS fixture", ex);
      if (failure == null) failure = wrapped;
      else failure.addSuppressed(wrapped);
    } finally {
      smpExecutor.shutdownNow();
    }
    try {
      pki.close();
    } catch (IOException ex) {
      if (failure == null) failure = ex;
      else failure.addSuppressed(ex);
    }
    try {
      untrustedPki.close();
    } catch (IOException ex) {
      if (failure == null) failure = ex;
      else failure.addSuppressed(ex);
    }
    if (failure != null) throw failure;
  }
}
