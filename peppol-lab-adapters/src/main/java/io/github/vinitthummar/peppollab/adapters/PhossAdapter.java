package io.github.vinitthummar.peppollab.adapters;

import io.github.vinitthummar.peppollab.api.AdapterContext;
import io.github.vinitthummar.peppollab.api.AdapterException;
import io.github.vinitthummar.peppollab.api.AdapterRequest;
import io.github.vinitthummar.peppollab.api.AdapterResult;
import io.github.vinitthummar.peppollab.api.Capability;
import io.github.vinitthummar.peppollab.api.TargetAdapter;
import io.github.vinitthummar.peppollab.api.TargetConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Adapter for phoss SMP publishing and phoss AP outbound submission/status APIs. */
public final class PhossAdapter implements TargetAdapter {
  private static final String DEFAULT_PARTICIPANT_SCHEME = "iso6523-actorid-upis";
  private static final String DEFAULT_DOCUMENT_SCHEME = "busdox-docid-qns";
  private static final String DEFAULT_PROCESS_SCHEME = "cenbii-procid-ubl";
  private static final String DEFAULT_TRANSPORT_PROFILE = "peppol-transport-as4-v2_0";
  private final HttpClient client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
  private final Set<ProvisionedServiceGroup> provisioned = ConcurrentHashMap.newKeySet();
  private final Set<ProvisionedServiceMetadata> provisionedMetadata =
      ConcurrentHashMap.newKeySet();

  @Override public String id() { return "phoss"; }

  @Override public Set<Capability> capabilities(TargetConfig target) {
    Set<Capability> result = new LinkedHashSet<>(Set.of(Capability.EVIDENCE));
    if (apApiEnabled(target)) {
      result.add(Capability.AP_SUBMIT);
      result.add(Capability.TRANSACTION_STATUS);
    }
    if (publisherApiEnabled(target)) result.add(Capability.SMP_PROVISION);
    return Set.copyOf(result);
  }

  @Override
  public AdapterResult execute(TargetConfig target, AdapterRequest request, AdapterContext context)
      throws AdapterException {
    return switch (request.action()) {
      case "phoss.submit" -> submit(target, request);
      case "phoss.status" -> status(target, request);
      case "smp.service-group.put" -> putServiceGroup(target, request, context);
      case "smp.service-group.get" -> getServiceGroup(target, request);
      case "smp.service-group.delete" -> deleteServiceGroup(target, request, context);
      case "smp.service-metadata.put" -> putServiceMetadata(target, request);
      case "smp.service-metadata.get" -> getServiceMetadata(target, request);
      case "smp.service-metadata.delete" -> deleteServiceMetadata(target, request);
      default -> AdapterResult.skipped("Unsupported phoss action: " + request.action());
    };
  }

  private AdapterResult submit(TargetConfig target, AdapterRequest request) throws AdapterException {
    requireApApi(target);
    String senderId = requiredParameter(request, "senderId");
    String receiverId = requiredParameter(request, "receiverId");
    String documentTypeId = requiredParameter(request, "documentTypeId");
    String processId = requiredParameter(request, "processId");
    String countryCode = requiredParameter(request, "countryCode").toUpperCase(Locale.ROOT);
    if (!countryCode.matches("[A-Z]{2}")) {
      throw new AdapterException("Parameter 'countryCode' must be an ISO 3166-1 alpha-2 code", false);
    }

    URI uri = AdapterSupport.resolve(target,
        "/api/outbound/submit/" + pathSegment(senderId)
            + "/" + pathSegment(receiverId)
            + "/" + pathSegment(documentTypeId)
            + "/" + pathSegment(processId)
            + "/" + pathSegment(countryCode));
    for (String name : List.of(
        "sbdhInstanceID", "mlsTo", "sbdhStandard", "sbdhTypeVersion", "sbdhType",
        "payloadMimeType", "custom1", "custom2", "custom3")) {
      String value = AdapterSupport.parameter(request.parameters(), name, null);
      if (value != null && !value.isBlank()) uri = query(uri, name, value);
    }

    byte[] payload = payload(request);
    String contentType = AdapterSupport.parameter(
        request.parameters(), "contentType", "application/xml");
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
        .timeout(request.timeout()).header("Content-Type", contentType)
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
    authenticate(target, builder, true);
    return AdapterSupport.send(client, builder.build());
  }

  private AdapterResult status(TargetConfig target, AdapterRequest request) throws AdapterException {
    requireApApi(target);
    String instanceId = requiredParameter(request, "instanceId");
    URI uri = AdapterSupport.resolve(target, "/api/outbound/status/" + pathSegment(instanceId));
    if (booleanParameter(request, "includeArchive", false)) {
      uri = query(uri, "includeArchive", "true");
    }
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
        .timeout(request.timeout()).GET();
    authenticate(target, builder, true);
    return AdapterSupport.send(client, builder.build());
  }

  private static byte[] payload(AdapterRequest request) throws AdapterException {
    String inline = AdapterSupport.parameter(request.parameters(), "payload", null);
    String file = AdapterSupport.parameter(request.parameters(), "payloadFile", null);
    if (inline != null && file != null) {
      throw new AdapterException("Configure only one of 'payload' or 'payloadFile'", false);
    }
    if (inline != null) return inline.getBytes(StandardCharsets.UTF_8);
    if (file == null || file.isBlank()) {
      throw new AdapterException("Missing required parameter 'payload' or 'payloadFile'", false);
    }
    try {
      return Files.readAllBytes(Path.of(file));
    } catch (Exception ex) {
      throw new AdapterException("Cannot read payload file '" + file + "'", ex, true);
    }
  }

  private AdapterResult putServiceGroup(
      TargetConfig target, AdapterRequest request, AdapterContext context) throws AdapterException {
    requirePublisherApi(target);
    boolean createInSml = booleanParameter(request, "createInSml", false);
    requireProductionOverride(createInSml, context, "createInSml");
    String scheme = AdapterSupport.parameter(
        request.parameters(), "participantScheme", DEFAULT_PARTICIPANT_SCHEME);
    String value = requiredParameter(request, "participantValue");
    URI resource = serviceGroupUri(target, scheme, value);
    ensureAbsent(target, resource, request.timeout());

    String payload = AdapterSupport.parameter(
        request.parameters(), "payload", serviceGroupXml(scheme, value));
    URI requestUri = query(resource, "create-in-sml=" + createInSml);
    HttpRequest.Builder builder = HttpRequest.newBuilder(requestUri)
        .timeout(request.timeout())
        .header("Content-Type", "application/xml")
        .PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
    authenticate(target, builder, true);
    AdapterResult result = AdapterSupport.send(client, builder.build());
    if (successful(result)) provisioned.add(new ProvisionedServiceGroup(target, resource));
    return result;
  }

  private AdapterResult getServiceGroup(TargetConfig target, AdapterRequest request)
      throws AdapterException {
    requirePublisherApi(target);
    URI resource = serviceGroupUri(
        target,
        AdapterSupport.parameter(
            request.parameters(), "participantScheme", DEFAULT_PARTICIPANT_SCHEME),
        requiredParameter(request, "participantValue"));
    HttpRequest.Builder builder = HttpRequest.newBuilder(resource)
        .timeout(request.timeout())
        .header("Accept", "application/xml")
        .GET();
    authenticate(target, builder, false);
    return AdapterSupport.send(client, builder.build());
  }

  private AdapterResult deleteServiceGroup(
      TargetConfig target, AdapterRequest request, AdapterContext context) throws AdapterException {
    requirePublisherApi(target);
    boolean deleteInSml = booleanParameter(request, "deleteInSml", false);
    requireProductionOverride(deleteInSml, context, "deleteInSml");
    URI resource = serviceGroupUri(
        target,
        AdapterSupport.parameter(
            request.parameters(), "participantScheme", DEFAULT_PARTICIPANT_SCHEME),
        requiredParameter(request, "participantValue"));
    AdapterResult result = delete(target, resource, deleteInSml, request.timeout());
    if (successful(result) || Integer.valueOf(404).equals(result.statusCode())) {
      provisioned.remove(new ProvisionedServiceGroup(target, resource));
    }
    return result;
  }

  private AdapterResult putServiceMetadata(TargetConfig target, AdapterRequest request)
      throws AdapterException {
    requirePublisherApi(target);
    String participantScheme = AdapterSupport.parameter(
        request.parameters(), "participantScheme", DEFAULT_PARTICIPANT_SCHEME);
    String participantValue = requiredParameter(request, "participantValue");
    String documentScheme = AdapterSupport.parameter(
        request.parameters(), "documentScheme", DEFAULT_DOCUMENT_SCHEME);
    String documentValue = requiredParameter(request, "documentValue");
    URI resource = serviceMetadataUri(
        target, participantScheme, participantValue, documentScheme, documentValue);
    ensureAbsent(target, resource, request.timeout(), "service metadata");

    String payload = AdapterSupport.parameter(request.parameters(), "payload", null);
    if (payload == null) {
      payload = serviceMetadataXml(
          request, participantScheme, participantValue, documentScheme, documentValue);
    }
    HttpRequest.Builder builder = HttpRequest.newBuilder(resource)
        .timeout(request.timeout())
        .header("Content-Type", "application/xml")
        .PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
    authenticate(target, builder, true);
    AdapterResult result = AdapterSupport.send(client, builder.build());
    if (successful(result)) {
      provisionedMetadata.add(new ProvisionedServiceMetadata(target, resource));
    }
    return result;
  }

  private AdapterResult getServiceMetadata(TargetConfig target, AdapterRequest request)
      throws AdapterException {
    requirePublisherApi(target);
    URI resource = serviceMetadataUri(target, request);
    HttpRequest.Builder builder = HttpRequest.newBuilder(resource)
        .timeout(request.timeout())
        .header("Accept", "application/xml")
        .GET();
    authenticate(target, builder, false);
    return AdapterSupport.send(client, builder.build());
  }

  private AdapterResult deleteServiceMetadata(TargetConfig target, AdapterRequest request)
      throws AdapterException {
    requirePublisherApi(target);
    URI resource = serviceMetadataUri(target, request);
    AdapterResult result = delete(target, resource, request.timeout());
    if (successful(result) || Integer.valueOf(404).equals(result.statusCode())) {
      provisionedMetadata.remove(new ProvisionedServiceMetadata(target, resource));
    }
    return result;
  }

  @Override
  public void cleanup(TargetConfig target, AdapterContext context) throws AdapterException {
    cleanup(target);
  }

  @Override
  public void close() throws AdapterException {
    Set<TargetConfig> targets = new LinkedHashSet<>();
    provisioned.forEach(resource -> targets.add(resource.target()));
    provisionedMetadata.forEach(resource -> targets.add(resource.target()));
    List<String> failures = new ArrayList<>();
    for (TargetConfig target : targets) {
      try {
        cleanup(target);
      } catch (AdapterException ex) {
        failures.add(ex.getMessage());
      }
    }
    if (!failures.isEmpty()) {
      throw new AdapterException(String.join("; ", failures), true);
    }
  }

  private void cleanup(TargetConfig target) throws AdapterException {
    List<String> failures = new ArrayList<>();
    for (ProvisionedServiceMetadata resource : List.copyOf(provisionedMetadata)) {
      if (!resource.target().equals(target)) continue;
      try {
        AdapterResult result = delete(target, resource.uri(), Duration.ofSeconds(10));
        if (successful(result) || Integer.valueOf(404).equals(result.statusCode())) {
          provisionedMetadata.remove(resource);
        } else {
          failures.add(resource.uri() + " returned HTTP " + result.statusCode());
        }
      } catch (AdapterException ex) {
        failures.add(resource.uri() + ": " + ex.getMessage());
      }
    }
    for (ProvisionedServiceGroup resource : List.copyOf(provisioned)) {
      if (!resource.target().equals(target)) continue;
      try {
        AdapterResult result = delete(target, resource.uri(), false, Duration.ofSeconds(10));
        if (successful(result) || Integer.valueOf(404).equals(result.statusCode())) {
          provisioned.remove(resource);
        } else {
          failures.add(resource.uri() + " returned HTTP " + result.statusCode());
        }
      } catch (AdapterException ex) {
        failures.add(resource.uri() + ": " + ex.getMessage());
      }
    }
    if (!failures.isEmpty()) {
      throw new AdapterException("Failed to clean up provisioned phoss SMP resources: "
          + String.join("; ", failures), true);
    }
  }

  private AdapterResult delete(
      TargetConfig target, URI resource, boolean deleteInSml, Duration timeout)
      throws AdapterException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(
        query(resource, "delete-in-sml=" + deleteInSml))
        .timeout(timeout)
        .DELETE();
    authenticate(target, builder, true);
    return AdapterSupport.send(client, builder.build());
  }

  private AdapterResult delete(TargetConfig target, URI resource, Duration timeout)
      throws AdapterException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(resource)
        .timeout(timeout)
        .DELETE();
    authenticate(target, builder, true);
    return AdapterSupport.send(client, builder.build());
  }

  private void ensureAbsent(TargetConfig target, URI resource, Duration timeout)
      throws AdapterException {
    ensureAbsent(target, resource, timeout, "service group");
  }

  private void ensureAbsent(
      TargetConfig target, URI resource, Duration timeout, String resourceType)
      throws AdapterException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(resource)
        .timeout(timeout)
        .header("Accept", "application/xml")
        .GET();
    authenticate(target, builder, false);
    AdapterResult result = AdapterSupport.send(client, builder.build());
    if (successful(result)) {
      throw new AdapterException(
          "Refusing to overwrite existing phoss SMP " + resourceType + " " + resource, false);
    }
    if (!Integer.valueOf(404).equals(result.statusCode())) {
      throw new AdapterException(
          "Cannot verify that phoss SMP " + resourceType + " is absent; HTTP "
              + result.statusCode(),
          true);
    }
  }

  private static URI serviceGroupUri(TargetConfig target, String scheme, String value)
      throws AdapterException {
    if (scheme == null || scheme.isBlank()) {
      throw new AdapterException("SMP participantScheme must not be blank", false);
    }
    return AdapterSupport.resolve(target, "/" + pathSegment(scheme + "::" + value));
  }

  private static URI serviceMetadataUri(TargetConfig target, AdapterRequest request)
      throws AdapterException {
    return serviceMetadataUri(
        target,
        AdapterSupport.parameter(
            request.parameters(), "participantScheme", DEFAULT_PARTICIPANT_SCHEME),
        requiredParameter(request, "participantValue"),
        AdapterSupport.parameter(
            request.parameters(), "documentScheme", DEFAULT_DOCUMENT_SCHEME),
        requiredParameter(request, "documentValue"));
  }

  private static URI serviceMetadataUri(
      TargetConfig target,
      String participantScheme,
      String participantValue,
      String documentScheme,
      String documentValue) throws AdapterException {
    if (documentScheme == null || documentScheme.isBlank()) {
      throw new AdapterException("SMP documentScheme must not be blank", false);
    }
    return URI.create(serviceGroupUri(target, participantScheme, participantValue)
        + "/services/" + pathSegment(documentScheme + "::" + documentValue));
  }

  private static String serviceGroupXml(String scheme, String value) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<smp:ServiceGroup xmlns:smp=\"http://busdox.org/serviceMetadata/publishing/1.0/\""
        + " xmlns:id=\"http://busdox.org/transport/identifiers/1.0/\">"
        + "<id:ParticipantIdentifier scheme=\"" + xml(scheme) + "\">" + xml(value)
        + "</id:ParticipantIdentifier><smp:ServiceMetadataReferenceCollection/>"
        + "</smp:ServiceGroup>";
  }

  private static String serviceMetadataXml(
      AdapterRequest request,
      String participantScheme,
      String participantValue,
      String documentScheme,
      String documentValue) throws AdapterException {
    String processScheme = AdapterSupport.parameter(
        request.parameters(), "processScheme", DEFAULT_PROCESS_SCHEME);
    String processValue = requiredParameter(request, "processValue");
    String transportProfile = AdapterSupport.parameter(
        request.parameters(), "transportProfile", DEFAULT_TRANSPORT_PROFILE);
    String endpointUrl = requiredParameter(request, "endpointUrl");
    String certificate = requiredParameter(request, "certificate");
    String serviceDescription = AdapterSupport.parameter(
        request.parameters(), "serviceDescription", "Peppol interoperability laboratory endpoint");
    String technicalContactUrl = AdapterSupport.parameter(
        request.parameters(), "technicalContactUrl",
        "https://github.com/vinit-thummar/peppol-interop-lab");
    boolean requireBusinessLevelSignature = booleanParameter(
        request, "requireBusinessLevelSignature", false);

    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<smp:ServiceMetadata xmlns:smp=\"http://busdox.org/serviceMetadata/publishing/1.0/\""
        + " xmlns:id=\"http://busdox.org/transport/identifiers/1.0/\""
        + " xmlns:wsa=\"http://www.w3.org/2005/08/addressing\">"
        + "<smp:ServiceInformation>"
        + "<id:ParticipantIdentifier scheme=\"" + xml(participantScheme) + "\">"
        + xml(participantValue) + "</id:ParticipantIdentifier>"
        + "<id:DocumentIdentifier scheme=\"" + xml(documentScheme) + "\">"
        + xml(documentValue) + "</id:DocumentIdentifier>"
        + "<smp:ProcessList><smp:Process>"
        + "<id:ProcessIdentifier scheme=\"" + xml(processScheme) + "\">"
        + xml(processValue) + "</id:ProcessIdentifier>"
        + "<smp:ServiceEndpointList><smp:Endpoint transportProfile=\""
        + xml(transportProfile) + "\">"
        + "<wsa:EndpointReference><wsa:Address>" + xml(endpointUrl)
        + "</wsa:Address></wsa:EndpointReference>"
        + "<smp:RequireBusinessLevelSignature>" + requireBusinessLevelSignature
        + "</smp:RequireBusinessLevelSignature>"
        + "<smp:Certificate>" + xml(certificate) + "</smp:Certificate>"
        + "<smp:ServiceDescription>" + xml(serviceDescription)
        + "</smp:ServiceDescription>"
        + "<smp:TechnicalContactUrl>" + xml(technicalContactUrl)
        + "</smp:TechnicalContactUrl>"
        + "</smp:Endpoint></smp:ServiceEndpointList>"
        + "</smp:Process></smp:ProcessList>"
        + "</smp:ServiceInformation></smp:ServiceMetadata>";
  }

  private static String requiredParameter(AdapterRequest request, String name)
      throws AdapterException {
    String value = AdapterSupport.parameter(request.parameters(), name, null);
    if (value == null || value.isBlank()) {
      throw new AdapterException("Missing required parameter '" + name + "'", false);
    }
    return value;
  }

  private static boolean booleanParameter(
      AdapterRequest request, String name, boolean fallback) throws AdapterException {
    Object value = request.parameters().get(name);
    if (value == null) return fallback;
    if (value instanceof Boolean bool) return bool;
    if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
    if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
    throw new AdapterException("Parameter '" + name + "' must be true or false", false);
  }

  private static void requireProductionOverride(
      boolean smlMutation, AdapterContext context, String parameter) throws AdapterException {
    if (smlMutation && !context.allowProduction()) {
      throw new AdapterException(
          parameter + "=true requires the explicit --allow-production override", false);
    }
  }

  private static void requirePublisherApi(TargetConfig target) throws AdapterException {
    if (!publisherApiEnabled(target)) {
      throw new AdapterException("phoss SMP publisher API is not enabled for this target", false);
    }
  }

  private static void requireApApi(TargetConfig target) throws AdapterException {
    if (!apApiEnabled(target)) {
      throw new AdapterException("phoss AP API is not enabled for this target", false);
    }
  }

  private static boolean publisherApiEnabled(TargetConfig target) {
    return Boolean.parseBoolean(target.options().getOrDefault("publisherApi", "false"));
  }

  private static boolean apApiEnabled(TargetConfig target) {
    String configured = target.options().get("apApi");
    return configured == null ? !publisherApiEnabled(target) : Boolean.parseBoolean(configured);
  }

  private static void authenticate(
      TargetConfig target, HttpRequest.Builder builder, boolean required) throws AdapterException {
    String bearer = target.options().get("bearerToken");
    String username = target.options().get("username");
    String password = target.options().get("password");
    String header = target.options().get("authHeader");
    String authValue = target.options().get("authValue");

    int modes = (bearer == null ? 0 : 1)
        + (username == null && password == null ? 0 : 1)
        + (header == null && authValue == null ? 0 : 1);
    if (modes > 1) {
      throw new AdapterException("Configure only one phoss authentication mode", true);
    }
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + AdapterSupport.secret(bearer));
      return;
    }
    if (username != null || password != null) {
      if (username == null || password == null) {
        throw new AdapterException("Both phoss username and password are required", true);
      }
      String resolvedUsername = username.startsWith("env:") || username.startsWith("file:")
          ? AdapterSupport.secret(username)
          : username;
      String credentials = resolvedUsername + ":" + AdapterSupport.secret(password);
      builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
          credentials.getBytes(StandardCharsets.UTF_8)));
      return;
    }
    if (header != null || authValue != null) {
      if (header == null || authValue == null) {
        throw new AdapterException("Both phoss authHeader and authValue are required", true);
      }
      builder.header(header, AdapterSupport.secret(authValue));
      return;
    }
    if (required) throw new AdapterException("phoss API authentication is required", true);
  }

  private static URI query(URI uri, String query) {
    return URI.create(uri + (uri.getRawQuery() == null ? "?" : "&") + query);
  }

  private static URI query(URI uri, String name, String value) {
    return query(uri, pathSegment(name) + "=" + pathSegment(value));
  }

  private static String pathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String xml(String value) {
    return value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  private static boolean successful(AdapterResult result) {
    return result.statusCode() != null
        && result.statusCode() >= 200
        && result.statusCode() < 300;
  }

  private record ProvisionedServiceGroup(TargetConfig target, URI uri) {}
  private record ProvisionedServiceMetadata(TargetConfig target, URI uri) {}
}
