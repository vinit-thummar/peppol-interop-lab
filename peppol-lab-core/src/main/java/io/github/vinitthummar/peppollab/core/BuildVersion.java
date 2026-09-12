package io.github.vinitthummar.peppollab.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Version embedded by Maven in every distribution. */
public final class BuildVersion {
  private static final String VERSION = load();

  private BuildVersion() {}

  public static String current() {
    return VERSION;
  }

  private static String load() {
    try (InputStream input = BuildVersion.class.getResourceAsStream("build.properties")) {
      if (input == null) return "development";
      Properties properties = new Properties();
      properties.load(input);
      return properties.getProperty("version", "development");
    } catch (IOException ex) {
      return "development";
    }
  }
}
