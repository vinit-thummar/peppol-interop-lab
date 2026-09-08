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

## Stage-one capabilities

- Versioned YAML scenario contract and machine-readable JSON Schema.
- `init`, `doctor`, `validate`, `list`, and `run` CLI commands.
- Public Java adapter SPI discovered through `ServiceLoader`.
- Standard SMP HTTP, direct AS4 wire, and phoss control-plane adapters.
- Loopback-only deterministic SMP and AS4 HTTP fixtures.
- Positive, negative, HTTP failure, and timeout starter scenarios.
- Console, JSON, and JUnit XML results with stable exit codes.
- Production-domain guard and environment/file-only secret references.

The direct AS4 adapter currently creates a Peppol-profiled multipart diagnostic envelope using
phase4's profile constants. It does **not yet** sign or encrypt AS4 messages, so the bundled AS4
scenarios are transport/wire checks—not cryptographic conformance claims. Signed AS4, complete phoss
SMP provisioning, and certified phoss AP flows remain upcoming work described in
[RFC-0001](docs/RFC-0001.md).

## Stage two in progress

The first stage-two slice adds a real loopback authoritative DNS service with NAPTR/A answers,
NXDOMAIN, SERVFAIL, delay, and timeout injection. Every fixture-backed run also creates a private
temporary CA and sender/receiver PKCS#12 identities. Private keys are deleted during cleanup;
retained evidence contains public certificates and SHA-256 fingerprints only.

The next slice will use these identities to replace the diagnostic AS4 envelope with phase4-signed
and encrypted messages and verified receipts. See the [worldwide testing landscape](docs/landscape.md)
for how this lightweight workflow complements GITB and the official OpenPeppol Testbed.

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
