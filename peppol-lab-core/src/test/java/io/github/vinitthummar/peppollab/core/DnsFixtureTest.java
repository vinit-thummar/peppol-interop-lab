package io.github.vinitthummar.peppollab.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

class DnsFixtureTest {
  @Test
  void servesAuthoritativeNaptrOverUdpAndTcp() throws Exception {
    try (DnsFixture fixture = DnsFixture.start(URI.create("http://127.0.0.1:18080"))) {
      Message udp = query(fixture, "ok.sml.test", Type.NAPTR, false);
      assertThat(udp.getRcode()).isEqualTo(Rcode.NOERROR);
      assertThat(udp.getHeader().getFlag(Flags.AA)).isTrue();
      assertThat(udp.getSection(Section.ANSWER)).singleElement().satisfies(answer -> {
        assertThat(answer.getType()).isEqualTo(Type.NAPTR);
        assertThat(answer.toString()).contains("Meta:SMP", "http://127.0.0.1:18080");
      });

      Message tcp = query(fixture, "ok.sml.test", Type.A, true);
      assertThat(tcp.getRcode()).isEqualTo(Rcode.NOERROR);
      assertThat(tcp.getSection(Section.ANSWER)).singleElement()
          .satisfies(answer -> assertThat(answer.toString()).contains("127.0.0.1"));
    }
  }

  @Test
  void producesDistinctNxdomainAndServfailResponses() throws Exception {
    try (DnsFixture fixture = DnsFixture.start(URI.create("http://127.0.0.1:18080"))) {
      Message missing = query(fixture, "nxdomain.sml.test", Type.NAPTR, false);
      assertThat(missing.getRcode()).isEqualTo(Rcode.NXDOMAIN);
      assertThat(missing.getHeader().getFlag(Flags.AA)).isTrue();
      assertThat(missing.getSection(Section.AUTHORITY)).singleElement()
          .satisfies(answer -> assertThat(answer.getType()).isEqualTo(Type.SOA));

      Message failed = query(fixture, "servfail.sml.test", Type.NAPTR, false);
      assertThat(failed.getRcode()).isEqualTo(Rcode.SERVFAIL);
      assertThat(failed.getSection(Section.ANSWER)).isEmpty();
    }
  }

  @Test
  void keepsTcpTimeoutSilentUntilTheResolverDeadline() throws Exception {
    try (DnsFixture fixture = DnsFixture.start(URI.create("http://127.0.0.1:18080"))) {
      URI endpoint = fixture.endpoint();
      SimpleResolver resolver = new SimpleResolver(
          new InetSocketAddress(endpoint.getHost(), endpoint.getPort()));
      resolver.setTCP(true);
      resolver.setTimeout(Duration.ofMillis(75));
      Message query = Message.newQuery(
          Record.newRecord(Name.fromString("timeout.sml.test."), Type.NAPTR, DClass.IN));

      assertThatThrownBy(() -> resolver.send(query))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("Timed out while trying to resolve");
    }
  }

  private static Message query(DnsFixture fixture, String name, int type, boolean tcp) throws Exception {
    URI endpoint = fixture.endpoint();
    SimpleResolver resolver = new SimpleResolver(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()));
    resolver.setTimeout(Duration.ofSeconds(1));
    resolver.setTCP(tcp);
    return resolver.send(Message.newQuery(Record.newRecord(Name.fromString(name + "."), type, DClass.IN)));
  }
}
