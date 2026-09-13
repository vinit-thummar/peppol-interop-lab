package io.github.vinitthummar.peppollab.adapters;

import io.github.vinitthummar.peppollab.api.AdapterContext;
import io.github.vinitthummar.peppollab.api.AdapterException;
import io.github.vinitthummar.peppollab.api.AdapterRequest;
import io.github.vinitthummar.peppollab.api.AdapterResult;
import io.github.vinitthummar.peppollab.api.Capability;
import io.github.vinitthummar.peppollab.api.DoctorCheck;
import io.github.vinitthummar.peppollab.api.Evidence;
import io.github.vinitthummar.peppollab.api.TargetAdapter;
import io.github.vinitthummar.peppollab.api.TargetConfig;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.NAPTRRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/** DNS wire adapter used for dynamic-discovery and resolver failure scenarios. */
public final class DnsAdapter implements TargetAdapter {
  private static final Set<String> PRODUCTION_SUFFIXES =
      Set.of("prod.tech.peppol.org", "edelivery.tech.ec.europa.eu");

  @Override
  public String id() {
    return "dns";
  }

  @Override
  public Set<Capability> capabilities(TargetConfig target) {
    return Set.of(Capability.DNS_LOOKUP, Capability.FAULT_INJECTION, Capability.EVIDENCE);
  }

  @Override
  public List<DoctorCheck> doctor(TargetConfig target, AdapterContext context) {
    URI endpoint = target.baseUrl();
    boolean valid = endpoint != null && endpoint.getHost() != null;
    DoctorCheck address = new DoctorCheck(
        "DNS resolver",
        valid,
        valid ? endpoint.toString() : "DNS resolver must include a host");
    if (!valid) return List.of(address);

    try {
      AdapterResult result = execute(
          target,
          new AdapterRequest(
              "dns.lookup", Map.of("name", "ok.sml.test", "type", "NAPTR"),
              Duration.ofSeconds(3)),
          context);
      boolean responded = result.statusCode() != null;
      return List.of(
          address,
          new DoctorCheck(
              "DNS connectivity",
              responded,
              responded ? "resolver responded with " + result.outcome() : result.body()));
    } catch (AdapterException ex) {
      return List.of(address, new DoctorCheck("DNS connectivity", false, ex.getMessage()));
    }
  }

  @Override
  public AdapterResult execute(TargetConfig target, AdapterRequest request, AdapterContext context)
      throws AdapterException {
    if (!"dns.lookup".equals(request.action())) {
      return AdapterResult.skipped("Unsupported DNS action: " + request.action());
    }

    URI endpoint = target.baseUrl();
    if (endpoint == null || endpoint.getHost() == null) {
      throw new AdapterException("DNS target must declare a resolver host", false);
    }
    String queryName = AdapterSupport.parameter(request.parameters(), "name", "ok.sml.test");
    checkQueryName(queryName, context.allowProduction());
    String typeName = AdapterSupport.parameter(request.parameters(), "type", "NAPTR").toUpperCase(Locale.ROOT);
    int type = Type.value(typeName);
    if (type < 0) throw new AdapterException("Unknown DNS record type: " + typeName, false);

    Instant started = Instant.now();
    InetSocketAddress resolverAddress = new InetSocketAddress(
        endpoint.getHost(), endpoint.getPort() > 0 ? endpoint.getPort() : 53);
    try {
      SimpleResolver resolver = new SimpleResolver(resolverAddress);
      resolver.setTimeout(request.timeout());
      resolver.setTCP(Boolean.parseBoolean(
          AdapterSupport.parameter(request.parameters(), "tcp", "false")));
      Name absoluteName = Name.fromString(queryName.endsWith(".") ? queryName : queryName + ".");
      Message query = Message.newQuery(Record.newRecord(absoluteName, type, DClass.IN));
      Message response = resolver.send(query);
      Duration duration = Duration.between(started, Instant.now());
      int rcode = response.getRcode();
      String outcome = switch (rcode) {
        case Rcode.NOERROR -> "SUCCESS";
        case Rcode.NXDOMAIN -> "NXDOMAIN";
        case Rcode.SERVFAIL -> "SERVFAIL";
        default -> "DNS_ERROR";
      };
      String body = response.getSection(Section.ANSWER).stream()
          .map(Record::toString)
          .collect(Collectors.joining("\n"));
      Map<String, String> outputs = new LinkedHashMap<>();
      outputs.put("queryName", absoluteName.toString());
      outputs.put("recordType", typeName);
      outputs.put("rcode", Rcode.string(rcode));
      response.getSection(Section.ANSWER).stream()
          .filter(NAPTRRecord.class::isInstance)
          .map(NAPTRRecord.class::cast)
          .filter(record -> "Meta:SMP".equalsIgnoreCase(record.getService().toString()))
          .map(NAPTRRecord::getRegexp)
          .map(DnsAdapter::naptrReplacement)
          .filter(value -> value != null && !value.isBlank())
          .findFirst()
          .ifPresent(value -> outputs.put("smpBaseUrl", value));
      Evidence evidence = new Evidence(
          "dns.exchange",
          Instant.now(),
          Map.of(
              "queryName", absoluteName.toString(),
              "recordType", typeName,
              "resolver", resolverAddress.toString(),
              "authoritative", response.getHeader().getFlag(org.xbill.DNS.Flags.AA),
              "responseCode", Rcode.string(rcode),
              "answerCount", response.getSection(Section.ANSWER).size()));
      return new AdapterResult(
          outcome,
          rcode,
          body,
          Map.of("DNS-Rcode", Rcode.string(rcode)),
          duration,
          List.of(evidence),
          outputs);
    } catch (IOException ex) {
      if (ex instanceof InterruptedIOException && Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
      }
      if (isTimeout(ex)) {
        return new AdapterResult(
            "TIMEOUT",
            null,
            "DNS query timed out",
            Map.of(),
            Duration.between(started, Instant.now()),
            List.of(new Evidence(
                "dns.timeout",
                Instant.now(),
                Map.of("queryName", queryName, "recordType", typeName, "resolver", resolverAddress.toString()))));
      }
      throw new AdapterException("DNS lookup failed: " + ex.getMessage(), ex, true);
    } catch (Exception ex) {
      throw new AdapterException("DNS lookup failed: " + ex.getMessage(), ex, true);
    }
  }

  private static String naptrReplacement(String regexp) {
    if (regexp == null || regexp.length() < 4) return null;
    char separator = regexp.charAt(0);
    int patternEnd = regexp.indexOf(separator, 1);
    if (patternEnd < 0) return null;
    int replacementEnd = regexp.indexOf(separator, patternEnd + 1);
    if (replacementEnd < 0) return null;
    return regexp.substring(patternEnd + 1, replacementEnd);
  }

  private static boolean isTimeout(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof java.net.SocketTimeoutException
          || current instanceof java.util.concurrent.TimeoutException) return true;
    }
    return failure.getMessage() != null
        && failure.getMessage().startsWith("Timed out while trying to resolve");
  }

  private static void checkQueryName(String queryName, boolean allowProduction) throws AdapterException {
    if (allowProduction) return;
    String normalized = queryName.toLowerCase(Locale.ROOT);
    if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
    for (String suffix : PRODUCTION_SUFFIXES) {
      if (normalized.equals(suffix) || normalized.endsWith("." + suffix)) {
        throw new AdapterException(
            "Production DNS name '" + queryName + "' is blocked; pass --allow-production explicitly",
            false);
      }
    }
  }
}
