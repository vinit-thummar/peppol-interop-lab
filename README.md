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
- Loopback-only deterministic SMP, DNS, and phase4 AS4 fixtures.
- Positive, negative, HTTP failure, and timeout starter scenarios.
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

The phoss SMP adapter now covers service-group and service-metadata lifecycles over the publisher
REST API. It checks that each resource does not already exist, creates it, reads it back, and removes
it. Resources still present after a failed scenario are deleted in dependency order: service
metadata first, then its service group. SML mutation remains disabled by default; enabling either
service-group SML flag requires the explicit `--allow-production` override.

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
overwrite existing service groups or service metadata. The next stage-three slices add a reusable
phoss SMP container environment and bring-your-own-test-certificate phoss AP flows.

## Build and run

Requirements: Java 21+ and Maven 3.9+.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean verify
java -jar peppol-lab-cli/target/peppol-lab.jar list
java -jar peppol-lab-cli/target/peppol-lab.jar run
```

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
