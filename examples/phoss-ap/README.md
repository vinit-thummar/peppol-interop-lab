# phoss AP outbound example

This scenario exercises the public phoss AP raw-document submission and transaction-status APIs.
It is intentionally not part of the built-in fixture pack: phoss AP validates the configured AP
identity and Peppol trust chain before it sends.

Before running it:

1. Run a phoss AP instance on loopback with its own official Peppol test certificate and test-network
   configuration. The laboratory never supplies or stores that private key.
2. Replace the example sender and receiver participant identifiers in both the scenario and payload
   with dedicated identifiers registered in the Peppol test network.
3. Replace the example country code and, if necessary, the document and process identifiers.
4. Export the phoss AP API token and run from the repository root:

```bash
export PHOSS_AP_TOKEN='your-local-phoss-ap-token'
java -jar peppol-lab-cli/target/peppol-lab.jar run --no-fixtures \
  --config examples/phoss-ap/peppol-lab.yml \
  examples/phoss-ap/outbound-submit-status.yaml
```

The token is resolved only at execution time. It is not written to console, JSON, or JUnit evidence.
The payload file is sent as the HTTP request body, but its contents are not copied into evidence.
The checked-in invoice is synthetic and contains no real participant or business data.

Do not point this example at production. The CLI blocks known production Peppol domains unless the
explicit `--allow-production` flag is supplied, but a local phoss AP may itself be configured to use
the production network. Verify the AP configuration independently.
