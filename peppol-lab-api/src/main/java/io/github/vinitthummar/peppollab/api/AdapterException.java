package io.github.vinitthummar.peppollab.api;

public class AdapterException extends Exception {
  private final boolean infrastructureFailure;

  public AdapterException(String message, boolean infrastructureFailure) {
    super(message);
    this.infrastructureFailure = infrastructureFailure;
  }

  public AdapterException(String message, Throwable cause, boolean infrastructureFailure) {
    super(message, cause);
    this.infrastructureFailure = infrastructureFailure;
  }

  public boolean isInfrastructureFailure() {
    return infrastructureFailure;
  }
}
