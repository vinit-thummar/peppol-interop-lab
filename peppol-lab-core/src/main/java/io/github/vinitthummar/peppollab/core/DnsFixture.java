package io.github.vinitthummar.peppollab.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.NAPTRRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

/** Loopback-only authoritative DNS fixture with deterministic failure modes. */
public final class DnsFixture implements AutoCloseable {
  private static final InetAddress LOOPBACK = ipv4Loopback();
  private static final Name ZONE = absolute("sml.test");
  private static final Name NAME_SERVER = absolute("ns.sml.test");
  private static final Name HOSTMASTER = absolute("hostmaster.sml.test");

  private final DatagramSocket udpSocket;
  private final ServerSocket tcpSocket;
  private final ExecutorService executor;
  private final URI smpEndpoint;
  private volatile boolean closed;

  private DnsFixture(
      DatagramSocket udpSocket, ServerSocket tcpSocket, ExecutorService executor, URI smpEndpoint) {
    this.udpSocket = udpSocket;
    this.tcpSocket = tcpSocket;
    this.executor = executor;
    this.smpEndpoint = smpEndpoint;
  }

  public static DnsFixture start(URI smpEndpoint) throws IOException {
    DatagramSocket udp = new DatagramSocket(null);
    ServerSocket tcp = new ServerSocket();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      udp.bind(new InetSocketAddress(LOOPBACK, 0));
      tcp.bind(new InetSocketAddress(LOOPBACK, udp.getLocalPort()));
      DnsFixture fixture = new DnsFixture(udp, tcp, executor, smpEndpoint);
      executor.execute(fixture::serveUdp);
      executor.execute(fixture::serveTcp);
      return fixture;
    } catch (IOException | RuntimeException ex) {
      udp.close();
      try {
        tcp.close();
      } catch (IOException ignored) {
        // Preserve the original startup failure.
      }
      executor.shutdownNow();
      throw ex;
    }
  }

  public URI endpoint() {
    return URI.create("dns://127.0.0.1:" + udpSocket.getLocalPort());
  }

  private void serveUdp() {
    byte[] buffer = new byte[Message.MAXLENGTH];
    while (!closed) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        udpSocket.receive(packet);
        byte[] query = Arrays.copyOf(packet.getData(), packet.getLength());
        executor.execute(() -> answerUdp(query, packet.getSocketAddress()));
      } catch (SocketException ex) {
        if (!closed) throw new IllegalStateException("DNS UDP fixture stopped unexpectedly", ex);
      } catch (IOException ex) {
        if (!closed) throw new IllegalStateException("DNS UDP fixture failed", ex);
      }
    }
  }

  private void answerUdp(byte[] query, java.net.SocketAddress remote) {
    try {
      byte[] response = responseFor(query);
      if (response != null && !closed) {
        udpSocket.send(new DatagramPacket(response, response.length, remote));
      }
    } catch (Exception ignored) {
      // A malformed laboratory query is dropped like a malformed DNS datagram.
    }
  }

  private void serveTcp() {
    while (!closed) {
      try {
        Socket socket = tcpSocket.accept();
        executor.execute(() -> answerTcp(socket));
      } catch (SocketException ex) {
        if (!closed) throw new IllegalStateException("DNS TCP fixture stopped unexpectedly", ex);
      } catch (IOException ex) {
        if (!closed) throw new IllegalStateException("DNS TCP fixture failed", ex);
      }
    }
  }

  private void answerTcp(Socket socket) {
    try (socket;
        DataInputStream input = new DataInputStream(socket.getInputStream());
        DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
      int length = input.readUnsignedShort();
      byte[] response = responseFor(input.readNBytes(length));
      if (response != null) {
        output.writeShort(response.length);
        output.write(response);
      } else {
        // Keep the connection silent so TCP resolvers observe a real deadline, not an early EOF.
        pause(30_000);
      }
    } catch (IOException ignored) {
      // The resolver may close a deliberately delayed or timed-out connection.
    }
  }

  private byte[] responseFor(byte[] wireQuery) throws IOException {
    Message query = new Message(wireQuery);
    Record question = query.getQuestion();
    if (question == null) return errorResponse(query, Rcode.FORMERR).toWire();

    String queriedName = question.getName().toString().toLowerCase(Locale.ROOT);
    if (queriedName.startsWith("timeout.")) return null;
    if (queriedName.startsWith("delay.")) pause(300);
    if (queriedName.startsWith("servfail.")) return errorResponse(query, Rcode.SERVFAIL).toWire();
    if (queriedName.startsWith("nxdomain.") || !queriedName.endsWith(".sml.test.")) {
      Message response = errorResponse(query, Rcode.NXDOMAIN);
      response.addRecord(soa(), Section.AUTHORITY);
      return response.toWire();
    }

    Message response = baseResponse(query, Rcode.NOERROR);
    if (question.getType() == Type.A || question.getType() == Type.ANY) {
      response.addRecord(new ARecord(question.getName(), DClass.IN, 30, LOOPBACK), Section.ANSWER);
    }
    if (question.getType() == Type.NAPTR || question.getType() == Type.ANY) {
      response.addRecord(
          new NAPTRRecord(
              question.getName(),
              DClass.IN,
              30,
              100,
              10,
              "U",
              "Meta:SMP",
              "!^.*$!" + smpEndpoint + "!",
              Name.root),
          Section.ANSWER);
    }
    return response.toWire();
  }

  private static Message errorResponse(Message query, int rcode) {
    return baseResponse(query, rcode);
  }

  private static Message baseResponse(Message query, int rcode) {
    Header header = new Header(query.getHeader().getID());
    header.setFlag(Flags.QR);
    header.setFlag(Flags.AA);
    if (query.getHeader().getFlag(Flags.RD)) header.setFlag(Flags.RD);
    header.setRcode(rcode);
    Message response = new Message();
    response.setHeader(header);
    if (query.getQuestion() != null) response.addRecord(query.getQuestion(), Section.QUESTION);
    return response;
  }

  private static SOARecord soa() {
    return new SOARecord(ZONE, DClass.IN, 30, NAME_SERVER, HOSTMASTER, 1, 60, 60, 60, 30);
  }

  private static Name absolute(String value) {
    try {
      return Name.fromString(value.endsWith(".") ? value : value + ".");
    } catch (IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private static InetAddress ipv4Loopback() {
    try {
      return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    } catch (java.net.UnknownHostException ex) {
      throw new ExceptionInInitializerError(ex);
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
  public void close() {
    closed = true;
    udpSocket.close();
    try {
      tcpSocket.close();
    } catch (IOException ignored) {
      // Already closing.
    }
    executor.shutdownNow();
  }
}
