package io.github.vinitthummar.peppollab.core;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** Service-provider boundary that keeps vendor-specific AS4 code outside the core module. */
public interface As4FixtureProvider {
  As4Fixture start(EphemeralPki pki) throws IOException, GeneralSecurityException;
}
