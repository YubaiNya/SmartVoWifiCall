package com.smartvowificall.ims.policy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Pure decision logic: should Wi-Fi Calling mode give the cellular radio back to the system?
 *
 * <p>While Wi-Fi Calling mode holds a SIM's cellular radio off, that SIM can only make or receive
 * calls through our IMS registration. If the VoWiFi path is unusable (no valid egress interface, or
 * IMS cannot register) the SIM would be left with no service at all and a stale VoWiFi indicator.
 * This policy returns a block code in those cases so the caller releases the radio and restores
 * the system IMS provider, then retries later with exponential backoff.
 *
 * <p>No Android calls, no persistence. Never changes anything while a call is active.
 */
public final class FallbackPolicy {
  public enum Egress { UNKNOWN, AVAILABLE_TUN, AVAILABLE_NOT_TUN, UNAVAILABLE }

  public static final String NO_EGRESS_SELECTED = "NO_EGRESS_SELECTED";
  public static final String EGRESS_NOT_TUN = "EGRESS_NOT_TUN";
  public static final String EGRESS_UNAVAILABLE = "EGRESS_UNAVAILABLE";
  public static final String REGISTRATION_TIMEOUT = "REGISTRATION_TIMEOUT";
  public static final String REGISTRATION_BACKOFF = "REGISTRATION_BACKOFF";

  /** An already-held radio tolerates a briefly missing egress (e.g. Clash restarting). */
  public static final long EGRESS_GRACE_NANOS = TimeUnit.SECONDS.toNanos(60);
  /** A held radio whose IMS never registers for this long is given back to the system. */
  public static final long REGISTRATION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(120);
  public static final long FIRST_BACKOFF_MINUTES = 5;
  public static final long MAX_BACKOFF_MINUTES = 30;

  private final Map<Integer, Long> unavailableSince = new ConcurrentHashMap<>();
  private final Map<Integer, Long> unregisteredSince = new ConcurrentHashMap<>();
  private final Map<Integer, Long> retryNotBefore = new ConcurrentHashMap<>();
  private final Map<Integer, Integer> backoffStep = new ConcurrentHashMap<>();

  /**
   * @return null when Wi-Fi Calling may hold/keep the radio off; otherwise one of the block codes.
   */
  public String evaluate(int slot, boolean callBusy, boolean radioHeld, boolean hasSelection,
      Egress egress, boolean hasBinding, boolean registered, long nowNanos) {
    if (callBusy) return null;
    Long retry = retryNotBefore.get(slot);
    if (retry != null) {
      if (nowNanos - retry < 0) return REGISTRATION_BACKOFF;
      retryNotBefore.remove(slot);
    }
    if (!hasSelection) return NO_EGRESS_SELECTED;
    switch (egress) {
      case AVAILABLE_NOT_TUN:
        return EGRESS_NOT_TUN;
      case UNAVAILABLE:
        if (!radioHeld) {
          // Never cut the cellular radio for a path that does not exist right now.
          unavailableSince.remove(slot);
          return EGRESS_UNAVAILABLE;
        }
        Long since = unavailableSince.computeIfAbsent(slot, k -> nowNanos);
        if (nowNanos - since >= EGRESS_GRACE_NANOS) return EGRESS_UNAVAILABLE;
        break;
      case AVAILABLE_TUN:
        unavailableSince.remove(slot);
        break;
      default:
        break;
    }
    if (radioHeld && hasBinding) {
      if (registered) {
        unregisteredSince.remove(slot);
        backoffStep.remove(slot);
      } else {
        Long since = unregisteredSince.computeIfAbsent(slot, k -> nowNanos);
        if (nowNanos - since >= REGISTRATION_TIMEOUT_NANOS) {
          int step = backoffStep.getOrDefault(slot, 0);
          long minutes = Math.min(MAX_BACKOFF_MINUTES, FIRST_BACKOFF_MINUTES << Math.min(step, 8));
          retryNotBefore.put(slot, nowNanos + TimeUnit.MINUTES.toNanos(minutes));
          backoffStep.put(slot, step + 1);
          unregisteredSince.remove(slot);
          return REGISTRATION_TIMEOUT;
        }
      }
    } else {
      unregisteredSince.remove(slot);
    }
    return null;
  }

  /** A user action (enable, select egress, switch node, refresh) clears every timer and backoff. */
  public void reset() {
    unavailableSince.clear();
    unregisteredSince.clear();
    retryNotBefore.clear();
    backoffStep.clear();
  }

  /** Switching the eSIM profile on one slot must not inherit the old profile's timers or backoff. */
  public void resetSlot(int slot) {
    unavailableSince.remove(slot);
    unregisteredSince.remove(slot);
    retryNotBefore.remove(slot);
    backoffStep.remove(slot);
  }

  public static String describe(String code) {
    if (NO_EGRESS_SELECTED.equals(code)) return "未选择连接";
    if (EGRESS_NOT_TUN.equals(code)) return "连接不支持网络通话";
    if (EGRESS_UNAVAILABLE.equals(code)) return "连接不可用";
    if (REGISTRATION_TIMEOUT.equals(code)) return "注册超时，稍后自动重试";
    if (REGISTRATION_BACKOFF.equals(code)) return "等待重试";
    return code == null ? "" : code;
  }
}
