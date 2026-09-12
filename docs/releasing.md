# Releasing

Releases are created only from reviewed commits on `main`. A semantic-version tag drives the
entire process; do not upload binaries or publish the container manually.

## Pre-release checklist

1. Confirm the `Build` workflow is green on Java 21 and Java 25.
2. Run `mvn -Drevision=0.1.0 clean verify` locally with Java 21.
3. Confirm `java -jar peppol-lab-cli/target/peppol-lab.jar --version` prints the release version.
4. Review the bundled scenario and specification versions with `peppol-lab list`.
5. Confirm no private keys, passwords, tokens, or business payloads appear in generated reports.
6. Create and push an annotated tag, for example:

   ```bash
   git tag -a v0.1.0 -m "Peppol Interop Lab 0.1.0"
   git push origin v0.1.0
   ```

The `Release` workflow validates the tag, runs the full suite, verifies that a second build produces
an identical runnable JAR, publishes the versioned JAR and JSON Schema with SHA-256 checksums,
publishes versioned and `latest` OCI image tags, and finally creates the GitHub release.

If any step fails, do not move or reuse the tag. Fix the cause on `main` and create a new semantic
pre-release or patch version.
