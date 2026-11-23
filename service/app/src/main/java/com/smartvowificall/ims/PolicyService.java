package com.smartvowificall.ims;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import android.telephony.*;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
public final class PolicyService extends Service {
  private static final Object AUDIO_LOCK = new Object();
  private static final Set<AudioLease> audioLeases = new HashSet<>();
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static volatile Context applicationContext;
  private static PolicyService running;
  private static final String AUDIO_START = "com.smartvowificall.ims.START_CALL_AUDIO";
  private boolean microphoneForeground;

  static Context audioContext() {
    return applicationContext;
  }
  static final class AudioLease implements AutoCloseable {
    final CompletableFuture<Void> ready = new CompletableFuture<>();
    final Consumer<Throwable> lost;
    private boolean closed;
    AudioLease(Consumer<Throwable> lost) { this.lost = lost; }
    void awaitReady() throws Exception {
      try {
        ready.get(5, TimeUnit.SECONDS);
      } catch (ExecutionException e) {
        throw new IOException("AUDIO_FOREGROUND_UNAVAILABLE", e.getCause());
      } catch (TimeoutException e) {
        close();
        throw new IOException("AUDIO_FOREGROUND_TIMEOUT", e);
      } catch (InterruptedException e) {
        close();
        Thread.currentThread().interrupt();
        throw e;
      }
    }
    public void close() {
      synchronized (AUDIO_LOCK) {
        if (closed) return;
        closed = true;
        audioLeases.remove(this);
        ready.completeExceptionally(new CancellationException("AUDIO_CLOSED"));
      }
      main.post(() -> {
        synchronized (AUDIO_LOCK) {
          if (running != null && audioLeases.isEmpty()) running.releaseMicrophoneForeground();
        }
      });
    }
  }
  static AudioLease acquireAudio(Context context, Consumer<Throwable> lost) throws IOException {
    if (context == null) throw new IOException("AUDIO_CONTEXT_UNAVAILABLE");
    AudioLease lease = new AudioLease(lost);
    synchronized (AUDIO_LOCK) { audioLeases.add(lease); }
    try {
      context.startForegroundService(new Intent(context, PolicyService.class).setAction(AUDIO_START));
    } catch (RuntimeException e) {
      lease.close();
      throw new IOException("AUDIO_FOREGROUND_UNAVAILABLE", e);
    }
    return lease;
  }
  @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
    writer.println(RuntimeController.get(this).diagnostics().toString());
    if (args != null && java.util.Arrays.asList(args).contains("threads"))
      for (java.util.Map.Entry<Thread, StackTraceElement[]> entry
          : Thread.getAllStackTraces().entrySet()) {
        writer.println(entry.getKey().getName() + " " + entry.getKey().getState());
        for (StackTraceElement frame : entry.getValue()) writer.println("  at " + frame);
      }
  }
  private ConnectivityManager.NetworkCallback network;
  private SubscriptionManager.OnSubscriptionsChangedListener subscriptions;
  private BroadcastReceiver phoneState;
  private Notification notification(boolean microphone) {
    NotificationManager nm = getSystemService(NotificationManager.class);
    NotificationChannel ch =
        new NotificationChannel("call_audio", "VoWiFi 通话", NotificationManager.IMPORTANCE_LOW);
    ch.setShowBadge(false);
    ch.setSound(null, null);
    ch.enableVibration(false);
    nm.createNotificationChannel(ch);
    return new Notification.Builder(this, "call_audio")
        .setSmallIcon(android.R.drawable.stat_sys_phone_call)
        .setContentTitle(microphone ? "VoWiFi 通话中" : "VoWiFi 服务")
        .setContentText(microphone ? "正在使用麦克风" : "正在更新服务状态")
        .setOngoing(true)
        .build();
  }
  private void releaseMicrophoneForeground() {
    stopForeground(STOP_FOREGROUND_REMOVE);
    microphoneForeground = false;
  }
  private void updateForeground() {
    synchronized (AUDIO_LOCK) {
      if (!audioLeases.isEmpty()) {
        try {
          if (!microphoneForeground) {
            startForeground(1, notification(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            microphoneForeground = true;
          }
          for (AudioLease lease : audioLeases) lease.ready.complete(null);
          return;
        } catch (RuntimeException e) {
          // Permission/background-start denial must reach the media worker, not become silent PCM.
          for (AudioLease lease : audioLeases) lease.ready.completeExceptionally(e);
          audioLeases.clear();
        }
      }
      // A boot/registration-only start satisfies the FGS deadline without holding microphone access.
      int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
      startForeground(1, notification(false), type);
      releaseMicrophoneForeground();
    }
  }
  @Override
  public void onCreate() {
    super.onCreate();
    applicationContext = getApplicationContext();
    synchronized (AUDIO_LOCK) { running = this; }
    RuntimeController rt = RuntimeController.get(this);
    rt.start();
    phoneState = new BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())
            && TelephonyManager.EXTRA_STATE_IDLE.equals(
                intent.getStringExtra(TelephonyManager.EXTRA_STATE)))
          rt.kick();
      }
    };
    registerReceiver(phoneState, new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
        Context.RECEIVER_EXPORTED);
    network = new ConnectivityManager.NetworkCallback() {
      public void onAvailable(Network n) {
        if (rt.enabled()) rt.kick();
      }
      public void onLost(Network n) {
        if (rt.enabled()) rt.kick();
      }
      public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
        if (rt.enabled()) rt.kick();
      }
    };
    getSystemService(ConnectivityManager.class)
        .registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().build(), network);
    subscriptions = new SubscriptionManager.OnSubscriptionsChangedListener() {
      public void onSubscriptionsChanged() {
        rt.kick();
      }
    };
    try {
      getSystemService(SubscriptionManager.class)
          .addOnSubscriptionsChangedListener(getMainExecutor(), subscriptions);
    } catch (SecurityException ignored) {
    }
  }
  public int onStartCommand(Intent i, int flags, int id) {
    updateForeground();
    RuntimeController.get(this).start();
    return START_STICKY;
  }
  public IBinder onBind(Intent i) {
    return null;
  }
  public void onDestroy() {
    Set<AudioLease> lost;
    synchronized (AUDIO_LOCK) {
      if (running == this) running = null;
      lost = new HashSet<>(audioLeases);
      audioLeases.clear();
      releaseMicrophoneForeground();
    }
    IOException error = new IOException("AUDIO_FOREGROUND_STOPPED");
    for (AudioLease lease : lost) {
      if (!lease.ready.completeExceptionally(error)) lease.lost.accept(error);
    }
    if (phoneState != null)
      unregisterReceiver(phoneState);
    if (network != null)
      getSystemService(ConnectivityManager.class).unregisterNetworkCallback(network);
    if (subscriptions != null)
      getSystemService(SubscriptionManager.class)
          .removeOnSubscriptionsChangedListener(subscriptions);
    super.onDestroy();
  }
}
