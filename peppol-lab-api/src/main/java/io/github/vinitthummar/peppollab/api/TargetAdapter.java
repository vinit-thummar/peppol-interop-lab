package io.github.vinitthummar.peppollab.api;

import java.util.List;
import java.util.Set;

/**
 * Extension point for target-specific control planes. The wire-level SMP and AS4 adapters are
 * included in the distribution; other implementations can be added as ServiceLoader providers.
 */
public interface TargetAdapter extends AutoCloseable {
  String id();

  Set<Capability> capabilities(TargetConfig target);

  default List<DoctorCheck> doctor(TargetConfig target, AdapterContext context) {
    return List.of();
  }

  AdapterResult execute(TargetConfig target, AdapterRequest request, AdapterContext context)
      throws AdapterException;

  default void cleanup(TargetConfig target, AdapterContext context) throws AdapterException {}

  @Override
  default void close() throws AdapterException {}
}
