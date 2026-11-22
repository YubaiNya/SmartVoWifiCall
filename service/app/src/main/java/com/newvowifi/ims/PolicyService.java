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
  @Override
  public void onCreate() {
    super.onCreate();
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("service", "VoWiFi 服务", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(
        this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    startForeground(1,
        new Notification.Builder(this, "service")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("NewVoWiFi")
            .setContentText("海外卡 IMS 服务 · 接口与注册状态")
            .setContentIntent(open)
            .build());
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
