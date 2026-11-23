import com.smartvowificall.ims.policy.PolicyLoop;
import com.smartvowificall.ims.policy.ServiceLifecycle;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class PowerLifecycleTest {
  private static int checks;
  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }
  private static void await(BooleanSupplier condition, String message) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
    check(condition.getAsBoolean(), message);
  }
  private static final class Shutdown implements ServiceLifecycle.Shutdown {
    final List<String> steps = new ArrayList<>();
    String fail = "";
    void step(String name) throws Exception {
      steps.add(name);
      if (name.equals(fail)) throw new Exception("RETRY");
    }
    public void clearSessions() throws Exception { step("sessions"); }
    public void restoreIms() throws Exception { step("ims"); }
    public void releaseRadios() throws Exception { step("radio"); }
    public void stopProxy() throws Exception { step("proxy"); }
  }
  private static final class Work implements PolicyLoop.Work {
    final AtomicInteger full = new AtomicInteger(), light = new AtomicInteger();
    volatile boolean quiet = true, stable = true, failNext;
    volatile CountDownLatch entered, release;
    public void refresh() {
      full.incrementAndGet();
      CountDownLatch in = entered, out = release;
      if (in != null) {
        in.countDown();
        try { out.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      }
      if (failNext) { failNext = false; throw new IllegalStateException("RETRY"); }
    }
    public void poll() { light.incrementAndGet(); }
    public boolean stable() { return stable; }
    public boolean quiescent() { return quiet; }
  }
  public static void main(String[] args) throws Exception {
    Shutdown backend = new Shutdown();
    check(!ServiceLifecycle.stopWhenIdle(true, backend) && backend.steps.isEmpty(),
        "active or unknown call keeps every dependency alive");
    check(ServiceLifecycle.stopWhenIdle(false, backend), "idle shutdown completes");
    check(backend.steps.equals(Arrays.asList("sessions", "ims", "radio", "proxy")),
        "proxy stops only after engines, IMS and radio restoration");
    for (String fail : Arrays.asList("sessions", "ims", "radio")) {
      backend = new Shutdown(); backend.fail = fail;
      try { ServiceLifecycle.stopWhenIdle(false, backend); throw new AssertionError("failure swallowed"); }
      catch (Exception expected) { check(!backend.steps.contains("proxy"), "failed " + fail + " preserves proxy for recovery"); }
      backend.fail = ""; backend.steps.clear();
      check(ServiceLifecycle.stopWhenIdle(false, backend), "pending shutdown can retry");
    }
    check(ServiceLifecycle.shouldStartProxy(true,true,true,false,false,false), "enable starts configured proxy first");
    check(!ServiceLifecycle.shouldStartProxy(false,true,true,false,false,true), "saved/reloaded configuration cannot launch proxy while off");
    check(!ServiceLifecycle.shouldStartProxy(true,true,true,true,false,true), "active call defers proxy restart");
    check(!ServiceLifecycle.shouldStartProxy(true,true,true,false,true,false), "healthy proxy not restarted by steady polling");
    check(ServiceLifecycle.shouldStartProxy(true,true,true,false,true,true), "explicit restart applies when idle");
    check(!ServiceLifecycle.shouldStartProxy(true,false,true,false,false,false), "disabled proxy does not launch");
    check(!ServiceLifecycle.shouldStartProxy(true,true,false,false,false,false), "missing configuration does not launch");

    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    Work work = new Work();
    try (PolicyLoop loop = new PolicyLoop(executor, work, 25, 500, 30)) {
      loop.start();
      await(() -> work.full.get() == 1, "initial recovery runs once");
      Thread.sleep(100);
      check(work.full.get() == 1 && work.light.get() == 0, "fully stopped service has no periodic work");
      for (int i = 0; i < 100; i++) loop.kick();
      await(() -> work.full.get() == 2, "event burst coalesces into one refresh");
      Thread.sleep(100);
      check(work.full.get() == 2, "coalesced events do not create duplicate timer chains");
      work.quiet = false;
      loop.kick();
      await(() -> work.light.get() >= 2, "registered service uses lightweight polls");
      check(work.full.get() == 3, "lightweight polls avoid full root inventory");
      await(() -> work.full.get() >= 4, "periodic full reconciliation remains enabled");
      work.quiet = true;
      work.entered = new CountDownLatch(1); work.release = new CountDownLatch(1);
      loop.kick();
      check(work.entered.await(2, TimeUnit.SECONDS), "refresh in progress");
      int before = work.full.get();
      loop.kick();
      CountDownLatch release = work.release;
      work.entered = null; work.release = null; release.countDown();
      final int eventCount = before + 1;
      await(() -> work.full.get() == eventCount, "event during refresh is not lost");
      work.failNext = true;
      before = work.full.get(); final int retryCount = before + 2;
      loop.kick();
      await(() -> work.full.get() == retryCount, "refresh failure retries instead of sleeping forever");
      loop.close();
      before = work.full.get(); loop.kick(); Thread.sleep(100);
      check(work.full.get() == before, "closed loop cannot restart");
    } finally {
      executor.shutdownNow();
      check(executor.awaitTermination(2, TimeUnit.SECONDS), "test scheduler cleaned up");
    }
    System.out.println("PASS " + checks + " power/lifecycle checks; no device or network.");
  }
}
