package com.newvowifi.ims.policy;

/** A persisted per-SIM radio lease. OFF/ON are actual modem states, not IMS service state. */
public final class ExclusiveRadio {
  public static final int OFF = 0, ON = 1;
  public interface Backend {
    int state() throws Exception;
    boolean hasLease() throws Exception;
    void saveLease(boolean restoreOn) throws Exception;
    boolean restoreOn() throws Exception;
    void power(boolean on) throws Exception;
    void forget() throws Exception;
  }
  private ExclusiveRadio() { }
  private static int known(Backend b) throws Exception {
    int value = b.state();
    if (value != OFF && value != ON) throw new IllegalStateException("RADIO_STATE_UNKNOWN");
    return value;
  }
  public static boolean hold(Backend b) throws Exception {
    int state = known(b);
    // Record the original state before issuing a command; never overwrite it on retry/restart.
    if (!b.hasLease()) b.saveLease(state == ON);
    if (state == OFF) return true;
    b.power(false);
    return known(b) == OFF;
  }
  public static boolean release(Backend b, boolean airplaneMode) throws Exception {
    if (!b.hasLease()) return true;
    if (b.restoreOn()) {
      // A later user airplane-mode setting takes priority. Retain recovery intent for later.
      if (airplaneMode) return false;
      if (known(b) != ON) {
        b.power(true);
        if (known(b) != ON) return false;
      }
    }
    b.forget();
    return true;
  }
}
