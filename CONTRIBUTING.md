# Contributing

Contributions should improve a portable contract, fixture, adapter, or report without weakening
existing expectations for a single implementation.

1. Use Java 21 and run `mvn clean verify`.
2. Add tests for new scenario behavior and both positive and negative paths.
3. Keep vendor-specific behavior inside an adapter.
4. Do not commit real Peppol certificates, tokens, payloads, or participant data.
5. Describe whether a scenario expresses a normative specification requirement or a diagnostic check.

Bug reports must include redacted output and the exact scenario/specification version.
