package com.smartvowificall.ims.policy;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Coalesces invalidations, polls live sessions cheaply, and sleeps completely after shutdown. */
public final class PolicyLoop implements AutoCloseable {
  public interface Work {
    void refresh();
    void poll();
    boolean stable();
    boolean quiescent();
  }
  private final ScheduledExecutorService executor;
  private final Work work;
  private final long pollMs, refreshMs, debounceMs;
  private ScheduledFuture<?> pending;
  private boolean started, running, dirty = true, closed;
  private long generation, nextRefresh;

  public PolicyLoop(ScheduledExecutorService executor, Work work) {
    this(executor, work, 5000, 30000, 200);
  }
  public PolicyLoop(ScheduledExecutorService executor, Work work,
      long pollMs, long refreshMs, long debounceMs) {
    if (pollMs <= 0 || refreshMs < pollMs || debounceMs < 0)
      throw new IllegalArgumentException("INVALID_POLL_INTERVAL");
    this.executor = executor;
    this.work = work;
    this.pollMs = pollMs;
    this.refreshMs = refreshMs;
    this.debounceMs = debounceMs;
  }
  public synchronized void start() {
    if (started || closed) return;
    started = true;
    schedule(0);
  }
  public synchronized void kick() {
    if (closed) return;
    boolean alreadyQueued = dirty && pending != null;
    dirty = true;
    if (!started) {
      start();
    } else if (!running && !alreadyQueued) {
      if (pending != null) pending.cancel(false);
      schedule(debounceMs);
    }
  }
  private void schedule(long delayMs) {
    long token = ++generation;
    pending = executor.schedule(() -> run(token), delayMs, TimeUnit.MILLISECONDS);
  }
  private void run(long token) {
    boolean refresh;
    synchronized (this) {
      if (closed || token != generation) return;
      pending = null;
      running = true;
      refresh = dirty || System.nanoTime() >= nextRefresh;
      dirty = false;
    }
    boolean success = false;
    try {
      if (refresh) work.refresh();
      else work.poll();
      success = true;
    } finally {
      synchronized (this) {
        running = false;
        if (!closed) {
          if (!success) dirty = true;
          if (refresh)
            nextRefresh = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(success && work.stable() ? refreshMs : pollMs);
          if (dirty) schedule(success ? debounceMs : pollMs);
          else if (!work.quiescent()) schedule(pollMs);
        }
      }
    }
  }
  @Override public synchronized void close() {
    closed = true;
    generation++;
    if (pending != null) pending.cancel(false);
  }
}
