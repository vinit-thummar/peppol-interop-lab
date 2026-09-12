package io.github.vinitthummar.peppollab.adapters;

import io.github.vinitthummar.peppollab.api.*;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public final class StandardSmpAdapter implements TargetAdapter {
  private static final String PEPPOL_SMP_NAMESPACE =
      "http://busdox.org/serviceMetadata/publishing/1.0/";
  private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @Override public String id() { return "standard-smp"; }

  @Override public Set<Capability> capabilities(TargetConfig target) {
    return Set.of(Capability.SMP_LOOKUP, Capability.EVIDENCE);
  }

  @Override public List<DoctorCheck> doctor(TargetConfig target, AdapterContext context) {
    boolean http = Set.of("http", "https").contains(target.baseUrl().getScheme());
    return List.of(new DoctorCheck("SMP URL", http, http ? target.baseUrl().toString() : "SMP URL must use HTTP(S)"));
  }

  @Override
  public AdapterResult execute(TargetConfig target, AdapterRequest request, AdapterContext context)
      throws AdapterException {
    if (!"smp.get".equals(request.action())) return AdapterResult.skipped("Unsupported SMP action: " + request.action());
    String path = lookupPath(request);
    HttpRequest httpRequest = HttpRequest.newBuilder(AdapterSupport.resolve(target, path))
        .timeout(request.timeout()).header("Accept", "application/xml").GET().build();
    AdapterResult result = AdapterSupport.send(client, httpRequest);
    return successful(result) ? validateMetadata(result) : result;
  }

  private static String lookupPath(AdapterRequest request) throws AdapterException {
    String explicit = AdapterSupport.parameter(request.parameters(), "path", null);
    if (explicit != null) return explicit;

    String participantScheme = requiredParameter(request, "participantScheme");
    String participantValue = requiredParameter(request, "participantValue");
    String path = "/" + pathSegment(participantScheme + "::" + participantValue);
    String documentValue = AdapterSupport.parameter(request.parameters(), "documentValue", null);
    if (documentValue == null) return path;
    String documentScheme = requiredParameter(request, "documentScheme");
    return path + "/services/" + pathSegment(documentScheme + "::" + documentValue);
  }

  private static AdapterResult validateMetadata(AdapterResult result) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler() {
        @Override public void error(org.xml.sax.SAXParseException ex) throws SAXException {
          throw ex;
        }

        @Override public void fatalError(org.xml.sax.SAXParseException ex) throws SAXException {
          throw ex;
        }
      });
      var document = builder.parse(
          new ByteArrayInputStream(result.body().getBytes(StandardCharsets.UTF_8)));
      String root = document.getDocumentElement().getLocalName();
      if (!Set.of("ServiceGroup", "SignedServiceMetadata").contains(root)) {
        return invalid(result, "Unexpected SMP document root '" + root + "'");
      }
      String namespace = document.getDocumentElement().getNamespaceURI();
      if (!PEPPOL_SMP_NAMESPACE.equals(namespace)) {
        return invalid(result, "Unexpected SMP document namespace '" + namespace + "'");
      }
      return result;
    } catch (Exception ex) {
      return invalid(result, "Malformed SMP XML: " + ex.getMessage());
    }
  }

  private static AdapterResult invalid(AdapterResult result, String reason) {
    return new AdapterResult(
        "INVALID_METADATA", result.statusCode(), reason, result.headers(), result.duration(),
        result.evidence());
  }

  private static String requiredParameter(AdapterRequest request, String name)
      throws AdapterException {
    String value = AdapterSupport.parameter(request.parameters(), name, null);
    if (value == null || value.isBlank()) {
      throw new AdapterException("Missing required parameter '" + name + "'", false);
    }
    return value;
  }

  private static String pathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static boolean successful(AdapterResult result) {
    return result.statusCode() != null
        && result.statusCode() >= 200
        && result.statusCode() < 300;
  }
}
