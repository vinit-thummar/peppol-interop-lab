package io.github.vinitthummar.peppollab.adapters;

import com.helger.servlet.mock.MockServletContext;
import com.helger.web.scope.mgr.WebScopeManager;

/** Reference-counted ownership of phase4's process-wide web scope. */
final class Phase4GlobalScope implements AutoCloseable {
  private static int references;
  private static boolean ownsScope;
  private boolean closed;

  private Phase4GlobalScope() {}

  static synchronized Phase4GlobalScope open() {
    if (references == 0 && !WebScopeManager.isGlobalScopePresent()) {
      WebScopeManager.onGlobalBegin(MockServletContext.create());
      ownsScope = true;
    }
    references++;
    return new Phase4GlobalScope();
  }

  @Override
  public void close() {
    synchronized (Phase4GlobalScope.class) {
      if (closed) return;
      closed = true;
      references--;
      if (references == 0 && ownsScope) {
        ownsScope = false;
        WebScopeManager.onGlobalEnd();
      }
    }
  }
}
