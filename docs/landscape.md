# Interoperability testing landscape

Peppol Interop Lab does not claim to be the first scenario-based interoperability test system. Its
scope is deliberately smaller: fast, reproducible protocol preflight in a developer workstation or
ordinary CI job.

## Existing systems

- The [OpenPeppol Testbed](https://peppol.org/tools-support/testbed/) is the authoritative remote path
  for Peppol testing and accreditation. It requires the official test environment and credentials.
- The European Commission [Interoperability Test Bed](https://github.com/ISAITB/gitb) is a complete
  self-service conformance platform. Its XML-based
  [GITB Test Description Language](https://www.itb.ec.europa.eu/docs/tdl/latest/) supports executable
  scenarios, messaging, validation, control flow, extension services, and rich reporting.
- Public Docker examples such as
  [peppol-sandbox-network](https://github.com/0x01d/peppol-sandbox-network) and
  [peppol-sandbox-phase4](https://github.com/0x01d/peppol-sandbox-phase4) combine existing SMP and AP
  products to demonstrate a local Peppol topology. They are useful integration environments, but do
  not define a portable cross-vendor scenario/evidence contract or controlled resolver and trust
  failures.
- The [NLnet-funded reproducible AP/SMP project](https://nlnet.nl/project/Peppol-Reproducible-AP-SMP/)
  focuses on automated deployment and onboarding of a working network. This project focuses on
  executable failure contracts against products supplied by the user.

## This project's boundary

Peppol Interop Lab optimises for a different feedback loop:

- one runnable JAR or container, with no account, database, or web UI;
- versioned YAML contracts that run in seconds;
- deterministic DNS, SMP, trust, and AS4 failure injection;
- the same reports and exit codes for every adapter;
- explicit safety controls for secrets and production targets.

The long-term integration direction is to let successful laboratory scenarios graduate to GITB
rather than build a competing conformance portal. A GITB bridge will therefore preserve the original
scenario version and expected observations while mapping supported steps to TDL and extension
services.
