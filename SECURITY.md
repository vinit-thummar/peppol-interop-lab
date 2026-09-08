# Security policy

Do not report vulnerabilities through a public issue. Contact the maintainer using the security
reporting mechanism on the GitHub repository.

Never attach Peppol AP/SMP private keys, certificate passwords, access tokens, production documents,
or unredacted business identifiers. Reproduce issues with generated fixture data where possible.

The CLI blocks known production Peppol domains by default. `--allow-production` is an explicit safety
override, not a statement that a scenario is safe to execute against production.
