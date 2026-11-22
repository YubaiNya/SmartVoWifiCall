package com.newvowifi.ims.policy;

import com.newvowifi.ims.network.InterfaceSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure decision logic; no Android calls, persistence, root commands, or ICCID logging. */
public final class PolicyEngine {
  public enum Kind { CLEAR, TAKEOVER }
  public enum Reason {
    ELIGIBLE,
    MAINLAND_HOME,
    SIM_NOT_READY,
    INVALID_HOME_PLMN,
    IDENTITY_UNAVAILABLE,
    INVALID_SUBSCRIPTION,
    INTERFACE_UNAVAILABLE
  }

  /** One currently active subscription. simKey is an opaque, stable ICCID-derived key. */
  public static final class Sim {
    public final String simKey, homePlmn;
    public final int subId, slotId;
    public final boolean ready;
    public Sim(String simKey, int subId, int slotId, boolean ready, String homePlmn) {
      this.simKey = simKey;
      this.subId = subId;
      this.slotId = slotId;
      this.ready = ready;
      this.homePlmn = homePlmn;
    }
  }

  /** Only overrides owned by this app, recorded after successful execution/verification. */
  public static final class AppliedOverride {
    public final String simKey, interfaceName;
    public final int subId, slotId;
    public AppliedOverride(String simKey, int subId, int slotId, String interfaceName) {
      this.simKey = simKey;
      this.subId = subId;
      this.slotId = slotId;
      this.interfaceName = interfaceName;
    }
    boolean sameAs(AppliedOverride other) {
      return other != null && subId == other.subId && slotId == other.slotId
          && Objects.equals(simKey, other.simKey)
          && Objects.equals(interfaceName, other.interfaceName);
    }
  }

  public static final class Action {
    public final Kind kind;
    public final String simKey, interfaceName;
    public final int subId, slotId;
    private Action(Kind kind, AppliedOverride target) {
      this.kind = kind;
      simKey = target.simKey;
      interfaceName = target.interfaceName;
      subId = target.subId;
      slotId = target.slotId;
    }
    public AppliedOverride asAppliedOverride() {
      return new AppliedOverride(simKey, subId, slotId, interfaceName);
    }
  }

  public static final class Decision {
    public final Sim sim;
    public final Reason reason;
    public final InterfaceSnapshot.Selection selection;
    /** Desired state after calls end; actions may currently be deferred. */
    public final boolean shouldTakeOver;
    private Decision(Sim sim, Reason reason, InterfaceSnapshot.Selection selection) {
      this.sim = sim;
      this.reason = reason;
      this.selection = selection;
      this.shouldTakeOver = reason == Reason.ELIGIBLE;
    }
  }

  public static final class Plan {
    /** All CLEARs precede TAKEOVERs. Stop on a failed CLEAR; re-evaluate after any failure. */
    public final List<Action> actions;
    public final List<Decision> decisions;
    public final boolean deferredForCall;
    private Plan(List<Action> actions, List<Decision> decisions, boolean deferredForCall) {
      this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
      this.decisions = Collections.unmodifiableList(new ArrayList<>(decisions));
      this.deferredForCall = deferredForCall;
    }
  }

  private PolicyEngine() {}

  /**
   * Call with a fresh active-subscription snapshot and known global call state. Treat unknown call
   * state as active. This method never replaces a missing selected interface with another one.
   * A selection default should be saved by the caller only when no selection has ever been made.
   */
  public static Plan evaluate(List<Sim> activeSims, Map<String, String> selectionBySimKey,
      InterfaceSnapshot inventory, Map<Integer, AppliedOverride> applied, boolean anyCallActive) {
    Objects.requireNonNull(activeSims, "activeSims");
    Objects.requireNonNull(selectionBySimKey, "selectionBySimKey");
    Objects.requireNonNull(inventory, "inventory");
    Objects.requireNonNull(applied, "applied");
    List<Sim> sorted = new ArrayList<>(activeSims);
    sorted.sort(Comparator.comparingInt((Sim s) -> s.slotId).thenComparingInt(s -> s.subId));
    Map<Integer, AppliedOverride> desired = new LinkedHashMap<>();
    List<Decision> decisions = new ArrayList<>();
    Map<Integer, Integer> slotCounts = new LinkedHashMap<>();
    for (Sim sim : sorted) slotCounts.put(sim.slotId, slotCounts.getOrDefault(sim.slotId, 0) + 1);
    for (Sim sim : sorted) {
      InterfaceSnapshot.Selection selection =
          inventory.selection(selectionBySimKey.get(sim.simKey));
      Reason reason = eligibility(sim);
      if (slotCounts.get(sim.slotId) != 1)
        reason = Reason.INVALID_SUBSCRIPTION;
      if (reason == Reason.ELIGIBLE && !selection.available())
        reason = Reason.INTERFACE_UNAVAILABLE;
      decisions.add(new Decision(sim, reason, selection));
      if (reason == Reason.ELIGIBLE)
        desired.put(sim.slotId,
            new AppliedOverride(sim.simKey, sim.subId, sim.slotId, selection.interfaceName));
    }
    List<Action> actions = new ArrayList<>();
    List<Integer> previousSlots = new ArrayList<>(applied.keySet());
    Collections.sort(previousSlots);
    for (int slot : previousSlots) {
      AppliedOverride previous = Objects.requireNonNull(applied.get(slot), "applied entry");
      if (previous.slotId != slot)
        throw new IllegalArgumentException("Applied slot key mismatch");
      if (!previous.sameAs(desired.get(slot)))
        actions.add(new Action(Kind.CLEAR, previous));
    }
    for (AppliedOverride next : desired.values()) {
      if (!next.sameAs(applied.get(next.slotId)))
        actions.add(new Action(Kind.TAKEOVER, next));
    }
    boolean deferred = anyCallActive && !actions.isEmpty();
    return new Plan(deferred ? Collections.emptyList() : actions, decisions, deferred);
  }

  public static Reason eligibility(Sim sim) {
    if (sim.slotId < 0 || sim.subId < 0)
      return Reason.INVALID_SUBSCRIPTION;
    if (!sim.ready)
      return Reason.SIM_NOT_READY;
    if (sim.simKey == null || sim.simKey.isEmpty())
      return Reason.IDENTITY_UNAVAILABLE;
    if (!validHomePlmn(sim.homePlmn))
      return Reason.INVALID_HOME_PLMN;
    String mcc = sim.homePlmn.substring(0, 3);
    return mcc.equals("460") || mcc.equals("461") ? Reason.MAINLAND_HOME : Reason.ELIGIBLE;
  }

  public static boolean validHomePlmn(String value) {
    if (value == null || (value.length() != 5 && value.length() != 6))
      return false;
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) < '0' || value.charAt(i) > '9')
        return false;
    return true;
  }
}
