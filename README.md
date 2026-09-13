# Peppol Interop Lab

Vendor-neutral, executable Peppol interoperability preflight scenarios for local development and CI.

Peppol Interop Lab is not an Access Point, SMP server, accreditation suite, or replacement for the
[OpenPeppol Testbed](https://peppol.org/tools-support/testbed/). It provides deterministic contracts,
controlled failures, and portable evidence before an implementation is tested on the official network.

The initial specification baseline is:

- Peppol SMP 1.4.0
- Peppol AS4 Profile 2.0.3
- Peppol Business Message Envelope (SBDH) 2.0.2

See the [official eDelivery specification catalogue](https://docs.peppol.eu/edelivery/).

## Current capabilities

- Versioned YAML scenario contract and machine-readable JSON Schema.
- `init`, `doctor`, `validate`, `list`, and `run` CLI commands.
- Public Java adapter SPI discovered through `ServiceLoader`.
- Standard SMP HTTP, direct phase4 AS4, and phoss control-plane adapters.
- Safe dataflow between scenario steps through validated structured-output references.
- Loopback-only deterministic SMP, DNS, and phase4 AS4 fixtures.
- Six deterministic SMP contracts covering discovery, identifier encoding, missing resources,
  endpoint metadata, and malformed metadata.
- Seven AS4 contracts covering delivery, receiver-observed routing identifiers, trust rejection,
  payload integrity, duplicate identifiers, and controlled peer failure.
- Bounded SMP, DNS, and AS4 timeout/resilience scenarios.
- A chained Route Proof that discovers the SMP through DNS and delivers to the AS4 endpoint and
  receiver certificate published by that SMP.
- Console, JSON, and JUnit XML results with stable exit codes.
- Production-domain guard and environment/file-only secret references.

The direct AS4 adapter uses phase4 to sign and encrypt Peppol-profiled user messages. Its loopback
peer decrypts and verifies the message, records the recovered payload, and returns a signed receipt.
The sender rejects receipts whose cryptographic references are invalid or whose `RefToMessageId`
does not match the transmitted message. The bundled AS4 contracts also verify that a reused
`MessageId` produces an explicit ebMS duplicate error and cannot replace the originally accepted
payload. A controlled in-transit bit flip after signing and encryption also verifies that altered
ciphertext is rejected before delivery, while a separate ephemeral CA proves that an untrusted
sender certificate cannot reach payload processing. These are deterministic preflight contracts,
not a claim of OpenPeppol conformance or accreditation.

## Peppol Route Proof

The `peppol-route-proof` scenario exercises one connected delivery path:

```text
DNS NAPTR -> SMP service metadata -> discovered AS4 endpoint/certificate -> signed receipt
```

The DNS adapter exports the discovered SMP URL. The SMP adapter then exports the selected endpoint,
transport profile, participant, document, process, certificate fingerprint, and receiver
certificate. The AS4 step consumes those outputs rather than a separately configured delivery
address or receiver identity. The final result correlates the transmitted message identifier,
receipt identifier, payload digest, and receiver observation.

Scenario parameters can reference only an earlier step using this non-executable syntax:

```yaml
endpointUrl: ${steps.retrieve-metadata.outputs.endpointUrl}
receiverCertificateBase64: ${steps.retrieve-metadata.outputs.receiverCertificateBase64}
```

Duplicate step IDs, malformed expressions, self-references, and forward references fail validation.
Missing runtime outputs fail the scenario as a laboratory error. Dynamically resolved HTTP targets
are checked by the production-domain guard immediately before adapter execution. Runtime-only
certificate values are withheld from the structured outputs written to reports; their SHA-256
fingerprints remain available as evidence.

## Stage two complete

The first stage-two slice adds a real loopback authoritative DNS service with NAPTR/A answers,
NXDOMAIN, SERVFAIL, delay, and timeout injection. Every fixture-backed run also creates a private
temporary CA and sender/receiver PKCS#12 identities. Private keys are deleted during cleanup;
retained evidence contains public certificates and SHA-256 fingerprints only.

The generated identities are used by phase4's in-memory WSS4J crypto implementation. The bridge
loads sender and receiver PKCS#12 identities, installs the per-run CA as the explicit trust anchor,
and erases its owned password when closed. The current AS4 slices perform a complete signed,
encrypted loopback exchange with strict receipt verification, reject untrusted senders, and exercise
duplicate detection and payload-integrity mutation with preserved ebMS error evidence. The bundled
certificate-rejection scenario uses an independently generated CA and retains only the rejected
identity's public certificates and fingerprints. See the [worldwide testing landscape](docs/landscape.md)
for how this lightweight workflow complements GITB and the official OpenPeppol Testbed.

## Stage three in progress

The phoss SMP adapter covers service-group and service-metadata lifecycles over the publisher
REST API. It checks that each resource does not already exist, creates it, reads it back, and removes
it. Resources still present after a failed scenario are deleted in dependency order: service
metadata first, then its service group. SML mutation remains disabled by default; enabling either
service-group SML flag requires the explicit `--allow-production` override.

The phoss adapter also implements the current phoss AP raw-document submission contract. It builds
the identifier-based `/api/outbound/submit/...` route, forwards supported SBDH and diagnostic query
parameters, and queries `/api/outbound/status/{sbdhInstanceID}` with optional archive lookup. Payloads
may be inline or loaded from a file; neither request payloads nor referenced API tokens are copied
into evidence. See [`examples/phoss-ap`](examples/phoss-ap/) for the credentials-required reference
flow.

Run the example against a local phoss SMP instance after setting its writable REST credentials:

```bash
export PHOSS_SMP_USERNAME='your-rest-user'
export PHOSS_SMP_PASSWORD='your-rest-password'
java -jar peppol-lab-cli/target/peppol-lab.jar run --no-fixtures \
  --config examples/phoss-smp/peppol-lab.yml \
  examples/phoss-smp/service-metadata-lifecycle.yaml
```

The example participant and document type must be dedicated to the laboratory. The example
certificate is an inert base64 laboratory marker, suitable only for metadata lifecycle testing; use
a generated or user-supplied test certificate before attempting delivery. Provisioning refuses to
overwrite existing service groups or service metadata.

### One-command phoss SMP laboratory

The repository includes a disposable phoss SMP 8.4.3 XML environment that runs both publisher
lifecycle scenarios and preserves their evidence locally:

```bash
./examples/phoss-smp/run-local.sh
```

The launcher selects the official amd64 or arm64 phoss SMP image for the host, creates an ephemeral
response-signing key, waits for the backend-aware `/smp-ready` endpoint, runs the lab on an internal
Docker network, and then removes the containers, private key, XML data volume, and temporary password
file. The management UI is bound only to
`http://127.0.0.1:8080`; set `PHOSS_SMP_PORT` to use a different host port. Reports remain under
`examples/phoss-smp/reports/`.

This disposable environment uses phoss SMP's documented initial administrator account solely on
the isolated local network. It must never be exposed or reused as a production deployment. The
phoss AP example remains opt-in because the AP intentionally requires a correctly configured
official Peppol test identity and trust chain; the laboratory never bundles those credentials.

## Stage four foundation: connected route evidence

The first stage-four slice adds structured adapter outputs and deterministic step-to-step dataflow.
The bundled Route Proof now performs a real local DNS-to-SMP-to-AS4 traversal, with the SMP fixture
publishing its runtime AS4 port and per-run receiver certificate. Future slices will add hop-specific
negative route contracts and a compact route summary suitable for debugging multi-vendor deployments.

## Build and run

Requirements: Java 21+ and Maven 3.9+.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean verify
java -jar peppol-lab-cli/target/peppol-lab.jar list
java -jar peppol-lab-cli/target/peppol-lab.jar run
```

Before running scenarios against an implementation, inspect every configured target with the
read-only preflight:

```bash
java -jar peppol-lab-cli/target/peppol-lab.jar doctor \
  --config my-lab/peppol-lab.yml --no-fixtures
```

The doctor resolves referenced secret files and environment variables without printing or sending
their values, validates direct-AS4 key material and certificates, and checks HTTP/DNS connectivity.
Known production Peppol targets remain blocked unless `--allow-production` is supplied explicitly.

Release tags produce a versioned runnable JAR, the standalone scenario JSON Schema, and a
`SHA256SUMS` file on GitHub Releases. The same tag publishes
`ghcr.io/vinit-thummar/peppol-interop-lab:<version>` and updates `latest`. See the
[release procedure](docs/releasing.md) for the guarded tag workflow.

The built-in run starts isolated loopback fixtures on random ports and writes:

- `reports/results.json`
- `reports/junit.xml`
- `reports/pki/certificates.json` and public PEM certificates
- `reports/pki/untrusted/certificates.json` and rejected identity public PEM certificates

Generate editable starter files:

```bash
java -jar peppol-lab-cli/target/peppol-lab.jar init --directory my-lab
java -jar peppol-lab-cli/target/peppol-lab.jar validate my-lab/example-scenario.yml
java -jar peppol-lab-cli/target/peppol-lab.jar run \
  --config my-lab/peppol-lab.yml my-lab/example-scenario.yml
```

## Exit codes

| Code | Meaning |
|---:|---|
| 0 | All applicable scenarios passed |
| 1 | One or more contract expectations failed |
| 2 | Invalid CLI input, configuration, or scenario |
| 3 | Laboratory infrastructure failure |

Unsupported adapter capabilities are reported as `SKIPPED`; they are never counted as passes.

## Safe target configuration

```yaml
targets:
  local-smp:
    adapter: standard-smp
    baseUrl: http://127.0.0.1:8080
  direct-as4:
    adapter: direct-as4
    baseUrl: http://127.0.0.1:8081
    options:
      senderKeyStore: file:/mounted/sender.p12
      senderKeyPassword: env:AS4_KEY_PASSWORD
      senderKeyAlias: sender
      trustCertificate: file:/mounted/ca.pem
      receiverCertificate: file:/mounted/receiver.pem
  phoss-smp:
    adapter: phoss
    baseUrl: http://127.0.0.1:8080
    options:
      publisherApi: "true"
      username: env:PHOSS_SMP_USERNAME
      password: env:PHOSS_SMP_PASSWORD
  phoss-ap:
    adapter: phoss
    baseUrl: http://127.0.0.1:8090
    options:
      apApi: "true"
      authHeader: X-Token
      authValue: env:PHOSS_AP_TOKEN
```

Passwords and tokens must use `env:NAME` or `file:/mounted/path` references. Production Peppol
domains are blocked unless `--allow-production` is supplied explicitly.

## Project structure

- `peppol-lab-api`: stable scenario and adapter contracts.
- `peppol-lab-core`: loading, validation, execution, safety, fixtures, and reports.
- `peppol-lab-adapters`: standard SMP, phase4-profiled AS4, and phoss adapters.
- `peppol-lab-cli`: CLI, bundled scenario pack, JSON Schema, and runnable JAR.

The project is independent and vendor-neutral. phax components are the first reference integration,
not a special case in the scenario model.

## License and trademark notice

Licensed under Apache License 2.0. Peppol is a trademark of OpenPeppol AISBL. This independent project
is not endorsed or certified by OpenPeppol AISBL or Philip Helger/phax.
