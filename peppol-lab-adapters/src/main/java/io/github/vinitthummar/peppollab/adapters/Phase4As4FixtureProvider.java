package io.github.vinitthummar.peppollab.adapters;

import io.github.vinitthummar.peppollab.core.As4Fixture;
import io.github.vinitthummar.peppollab.core.As4FixtureProvider;
import io.github.vinitthummar.peppollab.core.EphemeralPki;
import java.io.IOException;
import java.security.GeneralSecurityException;

public final class Phase4As4FixtureProvider implements As4FixtureProvider {
  @Override
  public As4Fixture start(EphemeralPki pki) throws IOException, GeneralSecurityException {
    return Phase4As4Fixture.start(pki);
  }
}
