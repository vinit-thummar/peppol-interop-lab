package io.github.vinitthummar.peppollab.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vinitthummar.peppollab.api.AdapterContext;
import io.github.vinitthummar.peppollab.api.AdapterRequest;
import io.github.vinitthummar.peppollab.api.TargetConfig;
import io.github.vinitthummar.peppollab.core.EphemeralPki;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase4As4RoundTripTest {
  @TempDir Path output;

  @Test
  void sendsEncryptedPayloadAndStrictlyVerifiesSignedReceipt() throws Exception {
    String messageId = "roundtrip-001@interop-lab";
    String payload =
        "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"/>";

    try (EphemeralPki pki = EphemeralPki.create();
         Phase4As4Fixture fixture = Phase4As4Fixture.start(pki)) {
      var result = new DirectAs4Adapter().execute(
          new TargetConfig("direct-as4", fixture.endpoint(), Map.of()),
          new AdapterRequest(
              "as4.send",
              Map.of("messageId", messageId, "payload", payload),
              Duration.ofSeconds(10)),
          new AdapterContext(output, false, pki.runtimeValues()));

      assertThat(result.outcome()).withFailMessage(result.body()).isEqualTo("SUCCESS");
      assertThat(result.statusCode()).isEqualTo(200);
      assertThat(result.body())
          .contains("SignalMessage", "Receipt", "cryptographically-verified", messageId);
      assertThat(result.evidence()).singleElement().satisfies(evidence ->
          assertThat(evidence.attributes())
              .containsEntry("signed", true)
              .containsEntry("encrypted", true)
              .containsEntry("receiptReferencesVerified", true));
      assertThat(fixture.receivedPayload(messageId))
          .isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void rejectsSenderOutsideTheReceiversTrustAnchor() throws Exception {
    String messageId = "untrusted-sender-001@interop-lab";

    try (EphemeralPki receiverPki = EphemeralPki.create();
         EphemeralPki untrustedSenderPki = EphemeralPki.create();
         Phase4As4Fixture fixture = Phase4As4Fixture.start(receiverPki)) {
      Map<String, String> senderRuntime = untrustedSenderPki.runtimeValues();
      Map<String, String> receiverRuntime = receiverPki.runtimeValues();
      var result = new DirectAs4Adapter().execute(
          new TargetConfig("direct-as4", fixture.endpoint(), Map.of(
              "senderKeyStore", senderRuntime.get("fixture:pki-sender"),
              "senderKeyPassword", senderRuntime.get("fixture:pki-password"),
              "trustCertificate", receiverRuntime.get("fixture:pki-ca"),
              "receiverCertificate", receiverRuntime.get("fixture:pki-receiver-cert"))),
          new AdapterRequest(
              "as4.send",
              Map.of("messageId", messageId),
              Duration.ofSeconds(10)),
          new AdapterContext(output, false, receiverRuntime));

      assertThat(result.outcome()).isEqualTo("AS4_ERROR");
      assertThat(result.evidence()).singleElement().satisfies(evidence ->
          assertThat(evidence.attributes())
              .containsEntry("receiptReferencesVerified", false));
      assertThat(fixture.receivedPayload(messageId)).isNull();
    }
  }

  @Test
  void rejectsDuplicateMessageIdWithoutReplacingTheAcceptedPayload() throws Exception {
    String messageId = "duplicate-001@interop-lab";
    String acceptedPayload =
        "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"><ID>first</ID></Invoice>";
    String duplicatePayload =
        "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"><ID>second</ID></Invoice>";

    try (EphemeralPki pki = EphemeralPki.create();
         Phase4As4Fixture fixture = Phase4As4Fixture.start(pki)) {
      DirectAs4Adapter adapter = new DirectAs4Adapter();
      TargetConfig target = new TargetConfig("direct-as4", fixture.endpoint(), Map.of());
      AdapterContext context = new AdapterContext(output, false, pki.runtimeValues());

      var accepted = adapter.execute(
          target,
          new AdapterRequest(
              "as4.send",
              Map.of("messageId", messageId, "payload", acceptedPayload),
              Duration.ofSeconds(10)),
          context);
      var duplicate = adapter.execute(
          target,
          new AdapterRequest(
              "as4.send",
              Map.of("messageId", messageId, "payload", duplicatePayload),
              Duration.ofSeconds(10)),
          context);

      assertThat(accepted.outcome()).isEqualTo("SUCCESS");
      assertThat(duplicate.outcome()).isEqualTo("AS4_ERROR");
      assertThat(duplicate.body()).contains("EBMS:4001").containsIgnoringCase("duplicate");
      assertThat(duplicate.evidence()).singleElement().satisfies(evidence ->
          assertThat(evidence.attributes().get("ebmsErrors").toString())
              .contains("EBMS:4001")
              .containsIgnoringCase("duplicate"));
      assertThat(fixture.receivedPayload(messageId))
          .isEqualTo(acceptedPayload.getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void rejectsPayloadMutatedAfterSigningAndEncryption() throws Exception {
    String messageId = "tampered-payload-001@interop-lab";

    try (EphemeralPki pki = EphemeralPki.create();
         Phase4As4Fixture fixture = Phase4As4Fixture.start(pki)) {
      var result = new DirectAs4Adapter().execute(
          new TargetConfig("direct-as4", fixture.endpoint(), Map.of()),
          new AdapterRequest(
              "as4.send",
              Map.of(
                  "messageId", messageId,
                  "path", "/as4/accept?mode=tamper-payload"),
              Duration.ofSeconds(10)),
          new AdapterContext(output, false, pki.runtimeValues()));

      assertThat(result.outcome()).isEqualTo("AS4_ERROR");
      assertThat(result.body()).contains("EBMS:0102", "FailedDecryption");
      assertThat(result.evidence()).singleElement().satisfies(evidence ->
          assertThat(evidence.attributes().get("ebmsErrors").toString())
              .contains("EBMS:0102", "FailedDecryption"));
      assertThat(fixture.receivedPayload(messageId)).isNull();
    }
  }
}
