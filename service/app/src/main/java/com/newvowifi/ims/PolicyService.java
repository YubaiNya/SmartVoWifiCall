package com.newvowifi.ims;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.telephony.*;
public final class PolicyService extends Service {
  @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
    writer.println(RuntimeController.get(this).diagnostics().toString());
  }
  private ConnectivityManager.NetworkCallback network;
  private SubscriptionManager.OnSubscriptionsChangedListener subscriptions;
  private void dismissForegroundNotification() {
    try {
      NotificationManager nm = getSystemService(NotificationManager.class);
      NotificationChannel ch =
          new NotificationChannel("service", "VoWiFi 后台服务", NotificationManager.IMPORTANCE_MIN);
      ch.setShowBadge(false);
      ch.setSound(null, null);
      ch.enableVibration(false);
      nm.createNotificationChannel(ch);
      startForeground(1,
          new Notification.Builder(this, "service")
              .setSmallIcon(android.R.drawable.stat_sys_phone_call)
              .build());
      stopForeground(STOP_FOREGROUND_REMOVE);
      nm.cancel(1);
      nm.cancelAll();
    } catch (Exception ignored) {
    }
  }
  @Override
  public void onCreate() {
    super.onCreate();
    dismissForegroundNotification();
    RuntimeController rt = RuntimeController.get(this);
    rt.start();
    network = new ConnectivityManager.NetworkCallback() {
      public void onAvailable(Network n) {
        rt.kick();
      }
      public void onLost(Network n) {
        rt.kick();
      }
      public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
        rt.kick();
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
    dismissForegroundNotification();
    RuntimeController.get(this).kick();
    return START_STICKY;
  }
  public IBinder onBind(Intent i) {
    return null;
  }
  public void onDestroy() {
    if (network != null)
      getSystemService(ConnectivityManager.class).unregisterNetworkCallback(network);
    if (subscriptions != null)
      getSystemService(SubscriptionManager.class)
          .removeOnSubscriptionsChangedListener(subscriptions);
    super.onDestroy();
  }
}
