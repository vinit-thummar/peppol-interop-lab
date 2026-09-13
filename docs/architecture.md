# Architecture

```text
scenario YAML ──> validator ──> execution engine ──> TargetAdapter SPI
                                      │       ▲            ├─ DNS wire
                                      │       └─ outputs ───├─ standard SMP
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

Adapters may return immutable string outputs for later steps. References use the constrained form
`${steps.<preceding-id>.outputs.<name>}`; there is no expression evaluator or scripting runtime.
Validation rejects duplicate IDs, malformed expressions, and references to the current or a future
step. The engine resolves references immediately before execution and reapplies the production guard
to every resulting absolute HTTP(S) URI. An unavailable output is an infrastructure error, never an
empty value or implicit pass.

The bundled Route Proof demonstrates the intended boundary: DNS supplies the SMP base URL, SMP
supplies the AS4 endpoint and receiver certificate, and the direct AS4 adapter sends only to those
discovered values. The fixture publishes its actual random ports and per-run public certificate, so
the contract cannot pass through a separately hardcoded delivery route.

Scenarios tagged `route-proof` also produce a machine-readable `route-summary.json` and a compact
`route-summary.txt`. Each summary separates contract status from the observed protocol outcome. A
negative scenario therefore passes when the expected route failure is preserved, while still naming
the DNS, SMP, or AS4 hop where delivery stopped.

Adapters receive secrets by reference. They must not put authorization values, private keys, or full
business payloads in evidence. Built-in fixtures listen on `127.0.0.1` with operating-system-assigned
ports and exist only for the duration of a run. Generated PKCS#12 stores stay in an owner-only
temporary directory and are deleted at shutdown; public certificate fingerprints remain in evidence.

Scenario targets are cleaned after every scenario, including failed scenarios. Adapters that create
remote test data register it during successful provisioning and make cleanup idempotent. A cleanup
failure is reported as a laboratory infrastructure error rather than being hidden behind the original
contract result. The phoss adapter removes service metadata before service groups so cleanup respects
the publisher API's ownership hierarchy.

The phoss SMP reference environment is an orchestration layer, not a repackaged vendor server. It
uses the official pinned phoss SMP image, exposes the UI on loopback only, and connects the lab over
an internal Compose network. A short-lived initializer creates the response-signing key inside the
disposable volume. The launcher removes that private key, the XML data, and the runtime secret after
each execution while retaining only the lab reports.
