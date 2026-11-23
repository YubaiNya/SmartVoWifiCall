package com.smartvowificall.ims.policy;

/** Keep the proxy alive until calls, engine sessions and radio restoration have finished. */
public final class ServiceLifecycle {
  public interface Shutdown {
    void clearSessions() throws Exception;
    void restoreIms() throws Exception;
    void releaseRadios() throws Exception;
    void stopProxy() throws Exception;
  }
  private ServiceLifecycle() { }

  public static boolean stopWhenIdle(boolean callsActiveOrUnknown, Shutdown backend)
      throws Exception {
    if (callsActiveOrUnknown) return false;
    backend.clearSessions();
    backend.restoreIms();
    backend.releaseRadios();
    backend.stopProxy();
    return true;
  }

  public static boolean shouldStartProxy(boolean enabled, boolean proxyEnabled,
      boolean hasProfile, boolean busy, boolean ready, boolean restartPending) {
    return enabled && proxyEnabled && hasProfile && !busy && (!ready || restartPending);
  }
}
