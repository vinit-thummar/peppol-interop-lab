package io.github.vinitthummar.peppollab.core;

import io.github.vinitthummar.peppollab.api.TargetAdapter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

public final class AdapterRegistry implements AutoCloseable {
  private final Map<String, TargetAdapter> adapters = new LinkedHashMap<>();

  public static AdapterRegistry load() {
    AdapterRegistry registry = new AdapterRegistry();
    ServiceLoader.load(TargetAdapter.class).forEach(registry::register);
    return registry;
  }

  public AdapterRegistry register(TargetAdapter adapter) {
    TargetAdapter previous = adapters.putIfAbsent(adapter.id(), adapter);
    if (previous != null) throw new IllegalArgumentException("Duplicate adapter id: " + adapter.id());
    return this;
  }

  public TargetAdapter require(String id) {
    TargetAdapter adapter = adapters.get(id);
    if (adapter == null) throw new IllegalArgumentException("No adapter named '" + id + "' is installed");
    return adapter;
  }

  public Collection<TargetAdapter> all() {
    return adapters.values();
  }

  @Override
  public void close() throws Exception {
    Exception first = null;
    for (TargetAdapter adapter : adapters.values()) {
      try {
        adapter.close();
      } catch (Exception ex) {
        if (first == null) first = ex;
      }
    }
    if (first != null) throw first;
  }
}
