package io.github.vinitthummar.peppollab.core;

import java.io.IOException;
import java.net.URI;

/** Runtime boundary for an AS4 fixture supplied by an integration module. */
public interface As4Fixture extends AutoCloseable {
  URI endpoint();

  @Override
  void close() throws IOException;
}
