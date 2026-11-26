import com.smartvowificall.ims.policy.FallbackPolicy;
import com.smartvowificall.ims.policy.FallbackPolicy.Egress;
import java.util.concurrent.TimeUnit;

public final class FallbackPolicyTest {
  static int passed;
  static void eq(Object expected, Object actual, String name) {
    if (expected == null ? actual != null : !expected.equals(actual))
      throw new AssertionError(name + ": expected " + expected + " got " + actual);
    passed++;
  }
  static long s(long seconds) { return TimeUnit.SECONDS.toNanos(seconds); }

  public static void main(String[] args) {
    // Healthy path never blocks.
    FallbackPolicy p = new FallbackPolicy();
    eq(null, p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, true, s(0)), "healthy");
    eq(null, p.evaluate(1, false, false, true, Egress.AVAILABLE_TUN, false, false, s(0)), "healthy start");

    // The reported bug: switching to a non-TUN interface (dummy0) must hand cellular back at once.
    eq(FallbackPolicy.EGRESS_NOT_TUN,
        p.evaluate(1, false, true, true, Egress.AVAILABLE_NOT_TUN, true, true, s(1)), "non-tun while held");
    eq(FallbackPolicy.EGRESS_NOT_TUN,
        p.evaluate(1, false, false, true, Egress.AVAILABLE_NOT_TUN, false, false, s(2)), "non-tun not held");
    // No selection at all.
    eq(FallbackPolicy.NO_EGRESS_SELECTED,
        p.evaluate(1, false, true, false, Egress.UNKNOWN, true, true, s(3)), "no selection");

    // Never alter anything during a call.
    eq(null, p.evaluate(1, true, true, true, Egress.AVAILABLE_NOT_TUN, true, false, s(4)), "call busy");

    // Missing egress: refuse to cut the radio, tolerate a held radio for 60 s only.
    p = new FallbackPolicy();
    eq(FallbackPolicy.EGRESS_UNAVAILABLE,
        p.evaluate(1, false, false, true, Egress.UNAVAILABLE, false, false, s(0)), "unavailable not held");
    eq(null, p.evaluate(1, false, true, true, Egress.UNAVAILABLE, true, true, s(10)), "unavailable grace start");
    eq(null, p.evaluate(1, false, true, true, Egress.UNAVAILABLE, true, true, s(69)), "unavailable inside grace");
    eq(FallbackPolicy.EGRESS_UNAVAILABLE,
        p.evaluate(1, false, true, true, Egress.UNAVAILABLE, true, true, s(70)), "unavailable after grace");
    // A recovered egress resets the grace timer.
    p = new FallbackPolicy();
    eq(null, p.evaluate(1, false, true, true, Egress.UNAVAILABLE, true, true, s(0)), "grace1");
    eq(null, p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, true, s(50)), "recovered");
    eq(null, p.evaluate(1, false, true, true, Egress.UNAVAILABLE, true, true, s(100)), "grace restarts");
    eq(null, p.evaluate(1, false, true, true, Egress.UNAVAILABLE, true, true, s(159)), "grace restarts inside");

    // Registration timeout + exponential backoff (5, 10, 20, 30, 30 minutes).
    p = new FallbackPolicy();
    eq(null, p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(0)), "reg start");
    eq(null, p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(119)), "reg inside");
    eq(FallbackPolicy.REGISTRATION_TIMEOUT,
        p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(120)), "reg timeout");
    eq(FallbackPolicy.REGISTRATION_BACKOFF,
        p.evaluate(1, false, false, true, Egress.AVAILABLE_TUN, false, false, s(121)), "backoff active");
    eq(FallbackPolicy.REGISTRATION_BACKOFF,
        p.evaluate(1, false, false, true, Egress.AVAILABLE_TUN, false, false, s(120 + 299)), "backoff before 5m");
    eq(null, p.evaluate(1, false, false, true, Egress.AVAILABLE_TUN, false, false, s(120 + 300)), "retry after 5m");
    long t = 120 + 300;
    long[] minutes = {10, 20, 30, 30};
    for (long m : minutes) {
      eq(null, p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(t)), "retry registering");
      t += 120;
      eq(FallbackPolicy.REGISTRATION_TIMEOUT,
          p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(t)), "timeout again " + m);
      eq(FallbackPolicy.REGISTRATION_BACKOFF,
          p.evaluate(1, false, false, true, Egress.AVAILABLE_TUN, false, false, s(t + m * 60 - 1)), "backoff " + m);
      t += m * 60;
      eq(null, p.evaluate(1, false, false, true, Egress.AVAILABLE_TUN, false, false, s(t)), "released " + m);
    }

    // Success clears the backoff ladder; a user action clears everything.
    p = new FallbackPolicy();
    p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(0));
    eq(FallbackPolicy.REGISTRATION_TIMEOUT,
        p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(120)), "ladder step 1");
    p.reset();
    eq(null, p.evaluate(1, false, false, true, Egress.AVAILABLE_TUN, false, false, s(121)), "reset clears backoff");
    eq(null, p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, true, s(122)), "registered");
    p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(200));
    eq(FallbackPolicy.REGISTRATION_TIMEOUT,
        p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(320)), "ladder restarts at 5m");
    eq(FallbackPolicy.REGISTRATION_BACKOFF,
        p.evaluate(1, false, false, true, Egress.AVAILABLE_TUN, false, false, s(320 + 299)), "5m after success");

    // Slots are independent, and resetSlot clears only the switched slot.
    p = new FallbackPolicy();
    p.evaluate(0, false, true, true, Egress.AVAILABLE_TUN, true, false, s(0));
    eq(FallbackPolicy.REGISTRATION_TIMEOUT,
        p.evaluate(0, false, true, true, Egress.AVAILABLE_TUN, true, false, s(120)), "slot0 timeout");
    eq(null, p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, true, s(121)), "slot1 unaffected");
    p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(122));
    eq(FallbackPolicy.REGISTRATION_TIMEOUT,
        p.evaluate(1, false, true, true, Egress.AVAILABLE_TUN, true, false, s(242)), "slot1 timeout");
    p.resetSlot(1);
    eq(null, p.evaluate(1, false, false, true, Egress.AVAILABLE_TUN, false, false, s(243)), "slot1 cleared after eSIM switch");
    eq(FallbackPolicy.REGISTRATION_BACKOFF,
        p.evaluate(0, false, false, true, Egress.AVAILABLE_TUN, false, false, s(243)), "slot0 still in backoff");

    p = new FallbackPolicy();
    p.deferEngineRetry(0, true, 60000, s(0));
    eq(null, p.evaluate(0, true, true, true, Egress.AVAILABLE_TUN, true, false, s(1)), "never release during call");
    eq(FallbackPolicy.ENGINE_MANUAL_REQUIRED,
        p.evaluate(0, false, false, true, Egress.AVAILABLE_TUN, false, false, s(61)), "engine hard failure survives teardown");
    eq(null, p.evaluate(1, false, false, true, Egress.AVAILABLE_TUN, false, false, s(2)), "other SIM unaffected");
    p.resetSlot(0);
    eq(FallbackPolicy.REGISTRATION_BACKOFF,
        p.evaluate(0, false, false, true, Egress.AVAILABLE_TUN, false, false, s(2)), "manual retry preserves cooldown");
    eq(null, p.evaluate(0, false, false, true, Egress.AVAILABLE_TUN, false, false, s(61)), "explicit retry after cooldown");
    p.deferEngineRetry(0, false, 3600000, s(62));
    p.reset();
    eq(FallbackPolicy.REGISTRATION_BACKOFF,
        p.evaluate(0, false, false, true, Egress.AVAILABLE_TUN, false, false, s(63)), "refresh cannot bypass carrier delay");
    p.subscriptionChanged(0);
    eq(null, p.evaluate(0, false, false, true, Egress.AVAILABLE_TUN, false, false, s(64)), "new profile does not inherit delay");
    p.observeSubscription(0, "fixture-a");
    p.deferEngineRetry(0, true, true, 0, s(65));
    p.reset(); p.resetSlot(0); p.observeSubscription(0, "fixture-a");
    eq(FallbackPolicy.ENGINE_RETRY_DISABLED,
        p.evaluate(0, false, false, true, Egress.AVAILABLE_TUN, false, false, s(10000)), "deactivated timer cannot be manually bypassed");
    p.observeSubscription(0, "fixture-b");
    eq(null, p.evaluate(0, false, false, true, Egress.AVAILABLE_TUN, false, false, s(10001)), "real profile replacement clears server stop");
    eq("连接不支持网络通话", FallbackPolicy.describe(FallbackPolicy.EGRESS_NOT_TUN), "describe");
    System.out.println("PASS " + passed + " fallback policy assertions; no device, radio or network used.");
  }
}
