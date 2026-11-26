package com.smartvowificall.ims;

import java.util.*;
import java.util.concurrent.*;

final class NewMmTelFeature {
    final int slot = 0;
    final RuntimeController rt;
    NewMmTelFeature(RuntimeController rt) { this.rt = rt; }
    RuntimeController.Binding binding() { return rt.binding; }
}

final class RuntimeController {
    final ManualExecutor worker = new ManualExecutor();
    final ManualExecutor io = new ManualExecutor();
    final Executor binderExecutor = Runnable::run;
    volatile boolean allowed = true;
    Binding binding = new Binding("SIM-A");
    boolean billable() { return allowed; }
    boolean billable(int slot) { return slot == 0 && allowed; }
    static final class Binding {
        final Sim sim;
        final Engine engine = new Engine();
        boolean registered = true;
        Binding(String key) { sim = new Sim(key); }
    }
    static final class Sim { final Policy policy; Sim(String key) { policy = new Policy(key); } }
    static final class Policy { final String simKey; Policy(String key) { simKey = key; } }
    static final class Engine {
        int calls;
        String number, text;
        boolean reject;
        RuntimeException failure;
        Map<String, Object> sendSmsText(String number, String text) {
            calls++; this.number = number; this.text = text;
            if (failure != null) throw failure;
            Map<String, Object> response = new HashMap<>();
            response.put("statusCode", reject ? 500 : 202);
            return response;
        }
    }
}

/** Deterministic state/IO queues; tests choose when RPC and timers are allowed to run. */
final class ManualExecutor extends AbstractExecutorService implements ScheduledExecutorService {
    final Queue<Runnable> queue = new ArrayDeque<>();
    final List<ManualFuture> timers = new ArrayList<>();
    boolean stopped;
    @Override public void execute(Runnable task) { if(stopped) throw new RejectedExecutionException(); queue.add(task); }
    void runReady() { int limit=10000; while(!queue.isEmpty()) { if(--limit==0)throw new AssertionError("queue loop"); queue.remove().run(); } }
    void expireTimers() { List<ManualFuture> copy=new ArrayList<>(timers);timers.clear();for(ManualFuture f:copy)if(!f.cancelled)f.task.run();runReady(); }
    @Override public ScheduledFuture<?> schedule(Runnable task,long delay,TimeUnit unit) { ManualFuture f=new ManualFuture(task);timers.add(f);return f; }
    @Override public <V> ScheduledFuture<V> schedule(Callable<V> c,long d,TimeUnit u) { throw new UnsupportedOperationException(); }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable r,long a,long b,TimeUnit u) { throw new UnsupportedOperationException(); }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r,long a,long b,TimeUnit u) { throw new UnsupportedOperationException(); }
    @Override public void shutdown() { stopped=true; }
    @Override public List<Runnable> shutdownNow() { stopped=true;return new ArrayList<>(queue); }
    @Override public boolean isShutdown(){return stopped;}
    @Override public boolean isTerminated(){return stopped&&queue.isEmpty();}
    @Override public boolean awaitTermination(long n,TimeUnit u){return isTerminated();}
    static final class ManualFuture implements ScheduledFuture<Object> {
        final Runnable task;boolean cancelled;
        ManualFuture(Runnable task){this.task=task;}
        public boolean cancel(boolean interrupt){cancelled=true;return true;}
        public boolean isCancelled(){return cancelled;}
        public boolean isDone(){return cancelled;}
        public Object get(){throw new UnsupportedOperationException();}
        public Object get(long n,TimeUnit u){throw new UnsupportedOperationException();}
        public long getDelay(TimeUnit u){return 0;}
        public int compareTo(Delayed other){return 0;}
    }
}
