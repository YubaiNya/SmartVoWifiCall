package com.newvowifi.ims.policy;

/** Changes only this service's MMTEL override. A usable interface alone is not IMS readiness. */
public final class RegistrationRouting {
  public static final String OWN_PACKAGE = "com.newvowifi.ims";
  public interface Backend {
    String current() throws Exception;
    void selectIndependent() throws Exception;
    void restoreSystem() throws Exception;
  }
  public static final class Result {
    public final boolean independent, deferred;
    private Result(boolean independent, boolean deferred) {
      this.independent = independent; this.deferred = deferred;
    }
  }
  private RegistrationRouting() { }

  public static Result synchronize(boolean registered, boolean frameworkCallActive,
      boolean engineCallActive, Backend backend) throws Exception {
    String current = backend.current();
    if (current == null) throw new IllegalStateException("IMS_CONFIG_UNKNOWN");
    boolean owned = OWN_PACKAGE.equals(current);
    if (!registered) {
      if (!owned) return new Result(false, false);
      // Wi-Fi Calling mode keeps ownership on registration loss. Explicit mode exit
      // restores the provider and only then releases the separate radio lease.
      return new Result(true, false);
    }
    if (owned) return new Result(true, false);
    if (!current.isEmpty()) throw new IllegalStateException("OTHER_CARRIER_OVERRIDE_PRESENT");
    if (frameworkCallActive) return new Result(false, true);
    // An incoming call already waiting in this candidate engine must be deliverable.
    // Its own ringing state cannot block attaching the feature to Android. The root
    // backend still rechecks real framework calls and the current SIM before binding.
    backend.selectIndependent();
    return new Result(true, false);
  }
}
