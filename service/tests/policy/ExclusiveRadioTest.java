import com.newvowifi.ims.policy.ExclusiveRadio;
import java.util.*;

/** Modem responses and durable lease are fixtures; no device or radio command is executed. */
public final class ExclusiveRadioTest {
  static int checks;
  static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
  static final class Modem implements ExclusiveRadio.Backend {
    int state = 1;
    boolean lease, restore, async, failSave, failPower;
    final List<String> events = new ArrayList<>();
    public int state() { return state; }
    public boolean hasLease() { return lease; }
    public void saveLease(boolean on) throws Exception {
      if (failSave) throw new Exception("storage failure");
      events.add("save:" + on); lease = true; restore = on;
    }
    public boolean restoreOn() { return restore; }
    public void power(boolean on) throws Exception {
      events.add("power:" + on);
      if (failPower) throw new Exception("modem failure");
      if (!async) state = on ? 1 : 0;
    }
    public void forget() { events.add("forget"); lease = false; }
  }
  public static void main(String[] args) throws Exception {
    Modem target = new Modem(), mainland = new Modem();
    check(ExclusiveRadio.hold(target), "actual RF OFF permits readiness");
    check(target.events.equals(Arrays.asList("save:true", "power:false")), "persist recovery before RF command");
    check(mainland.state == 1 && mainland.events.isEmpty(), "separate card is untouched");
    check(ExclusiveRadio.hold(target) && target.events.size() == 2, "OFF polling is idempotent");
    check(target.restore, "retry does not replace original ON with observed OFF");
    check(ExclusiveRadio.release(target, false), "explicit mode exit restores");
    check(target.state == 1 && !target.lease, "lease removed only after confirmed recovery");
    check(target.events.equals(Arrays.asList("save:true", "power:false", "power:true", "forget")), "ordered release");
    int size = target.events.size();
    check(ExclusiveRadio.release(target, false) && target.events.size() == size, "repeated cleanup no mutation");

    target = new Modem(); target.state = 0;
    check(ExclusiveRadio.hold(target), "pre-existing OFF accepted");
    check(!target.restore, "pre-existing OFF remembered");
    check(ExclusiveRadio.release(target, false), "pre-existing OFF cleanup completes");
    check(target.state == 0 && target.events.equals(Arrays.asList("save:false", "forget")), "never turns on a previously disabled card");

    target = new Modem(); target.async = true;
    check(!ExclusiveRadio.hold(target), "accepted command is not RF OFF evidence");
    check(target.lease && target.restore && target.state == 1, "pending closure retains durable intent");
    target.state = 0;
    check(ExclusiveRadio.hold(target), "later RF OFF completes hold");
    check(!ExclusiveRadio.release(target, false) && target.lease, "async restoration waits for actual ON");
    target.state = 1;
    check(ExclusiveRadio.release(target, false) && !target.lease, "actual ON finishes restoration");

    target = new Modem(); ExclusiveRadio.hold(target); size = target.events.size();
    check(!ExclusiveRadio.release(target, true), "later airplane mode prevents forced enable");
    check(target.state == 0 && target.lease && target.events.size() == size, "airplane mode retains recovery without RF writes");
    check(ExclusiveRadio.release(target, false), "recovery can resume after airplane mode ends");

    for (int unknown : new int[] {2, -1, 9}) {
      target = new Modem(); target.state = unknown;
      try { ExclusiveRadio.hold(target); throw new AssertionError("unknown RF accepted"); }
      catch (IllegalStateException expected) { check(target.events.isEmpty(), "unknown RF does not write"); }
    }
    target = new Modem(); target.failSave = true;
    try { ExclusiveRadio.hold(target); throw new AssertionError("failed journal ignored"); }
    catch (Exception expected) { check(target.state == 1 && target.events.isEmpty(), "journal failure precedes any RF command"); }
    target = new Modem(); target.failPower = true;
    try { ExclusiveRadio.hold(target); throw new AssertionError("failed power command ignored"); }
    catch (Exception expected) { check(target.lease && target.restore, "power failure keeps recovery intent"); }
    target.failPower = false;
    check(ExclusiveRadio.hold(target), "failed command can be retried");
    target.state = 1;
    check(ExclusiveRadio.hold(target) && target.restore, "unexpected modem re-enable is closed again without losing original state");
    System.out.println("PASS " + checks + " exclusive radio assertions; no live modem or call.");
  }
}
