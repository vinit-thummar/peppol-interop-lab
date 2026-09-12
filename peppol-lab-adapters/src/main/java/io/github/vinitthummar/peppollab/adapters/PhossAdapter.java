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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Adapter for phoss SMP publishing and phoss AP outbound submission/status APIs. */
public final class PhossAdapter implements TargetAdapter {
  private static final String DEFAULT_PARTICIPANT_SCHEME = "iso6523-actorid-upis";
  private final HttpClient client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
  private final Set<ProvisionedServiceGroup> provisioned = ConcurrentHashMap.newKeySet();

  @Override public String id() { return "phoss"; }

  @Override public Set<Capability> capabilities(TargetConfig target) {
    Set<Capability> result = new LinkedHashSet<>(Set.of(
        Capability.AP_SUBMIT, Capability.TRANSACTION_STATUS, Capability.EVIDENCE));
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
      default -> AdapterResult.skipped("Unsupported phoss action: " + request.action());
    };
  }

  private AdapterResult submit(TargetConfig target, AdapterRequest request) throws AdapterException {
    String path = AdapterSupport.parameter(request.parameters(), "path", "/api/outbound/submit");
    String payload = AdapterSupport.parameter(request.parameters(), "payload", "");
    HttpRequest.Builder builder = HttpRequest.newBuilder(AdapterSupport.resolve(target, path))
        .timeout(request.timeout()).header("Content-Type", "application/xml")
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
    authenticate(target, builder, false);
    return AdapterSupport.send(client, builder.build());
  }

  private AdapterResult status(TargetConfig target, AdapterRequest request) throws AdapterException {
    String instanceId = requiredParameter(request, "instanceId");
    HttpRequest.Builder builder = HttpRequest.newBuilder(
        AdapterSupport.resolve(target, "/api/outbound/status/" + pathSegment(instanceId)))
        .timeout(request.timeout()).GET();
    authenticate(target, builder, false);
    return AdapterSupport.send(client, builder.build());
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

  @Override
  public void cleanup(TargetConfig target, AdapterContext context) throws AdapterException {
    cleanup(target);
  }

  @Override
  public void close() throws AdapterException {
    Set<TargetConfig> targets = new LinkedHashSet<>();
    provisioned.forEach(resource -> targets.add(resource.target()));
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
      throw new AdapterException(
          "Failed to clean up provisioned phoss SMP service groups: " + String.join("; ", failures),
          true);
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

  private void ensureAbsent(TargetConfig target, URI resource, Duration timeout)
      throws AdapterException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(resource)
        .timeout(timeout)
        .header("Accept", "application/xml")
        .GET();
    authenticate(target, builder, false);
    AdapterResult result = AdapterSupport.send(client, builder.build());
    if (successful(result)) {
      throw new AdapterException(
          "Refusing to overwrite existing phoss SMP service group " + resource, false);
    }
    if (!Integer.valueOf(404).equals(result.statusCode())) {
      throw new AdapterException(
          "Cannot verify that phoss SMP service group is absent; HTTP " + result.statusCode(),
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

  private static String serviceGroupXml(String scheme, String value) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<ServiceGroup xmlns=\"http://busdox.org/serviceMetadata/publishing/1.0/\">"
        + "<ParticipantIdentifier scheme=\"" + xml(scheme) + "\">" + xml(value)
        + "</ParticipantIdentifier><ServiceMetadataReferenceCollection/>"
        + "</ServiceGroup>";
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

  private static boolean publisherApiEnabled(TargetConfig target) {
    return Boolean.parseBoolean(target.options().getOrDefault("publisherApi", "false"));
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
    if (required) throw new AdapterException("phoss publisher authentication is required", true);
  }

  private static URI query(URI uri, String query) {
    return URI.create(uri + (uri.getRawQuery() == null ? "?" : "&") + query);
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
}
