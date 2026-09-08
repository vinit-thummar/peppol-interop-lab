# Architecture

```text
scenario YAML ──> validator ──> execution engine ──> TargetAdapter SPI
                                      │                    ├─ DNS wire
                                      │                    ├─ standard SMP
                                      │                    ├─ direct AS4
                                      │                    └─ phoss
                                      │
                                      ├─ loopback DNS/SMP/AS4 fixtures
                                      ├─ per-run ephemeral PKI
                                      └─ console + JSON + JUnit evidence
```

The core knows no vendor APIs. A scenario names a target from `peppol-lab.yml`; that target selects an
adapter. The engine checks declared capabilities before executing steps, applies the production guard,
and evaluates only observable results.

Adapters receive secrets by reference. They must not put authorization values, private keys, or full
business payloads in evidence. Built-in fixtures listen on `127.0.0.1` with operating-system-assigned
ports and exist only for the duration of a run. Generated PKCS#12 stores stay in an owner-only
temporary directory and are deleted at shutdown; public certificate fingerprints remain in evidence.
