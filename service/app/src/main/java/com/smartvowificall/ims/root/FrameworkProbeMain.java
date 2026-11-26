package com.smartvowificall.ims.root;

import android.os.Looper;
import android.os.Process;
import android.telephony.ims.ImsMmTelManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Read-only framework observation, independent of RuntimeController's own status. */
public final class FrameworkProbeMain {
  public static void main(String[] args) {
    int exit = 1;
    try {
      if (Process.myUid() != 0 || args.length != 1) throw new IllegalArgumentException();
      int subId = Integer.parseInt(args[0]);
      if (subId < 0) throw new IllegalArgumentException();
      Looper.prepareMainLooper();
      Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
      // app_process does not run ActivityThread's application bind initialization.
      Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
      if (initializer.getMethod("getTelephonyServiceManager").invoke(null) == null) {
        Class<?> serviceManager = Class.forName("android.os.TelephonyServiceManager");
        initializer.getMethod("setTelephonyServiceManager", serviceManager)
            .invoke(null, serviceManager.getConstructor().newInstance());
      }
      ImsMmTelManager manager = ImsMmTelManager.createForSubscriptionId(subId);
      AtomicInteger registration = new AtomicInteger(-99), transport = new AtomicInteger(-99),
                    feature = new AtomicInteger(-99);
      CountDownLatch done = new CountDownLatch(3);
      manager.getRegistrationState(Runnable::run, value -> { registration.set(value); done.countDown(); });
      manager.getRegistrationTransportType(Runnable::run, value -> { transport.set(value); done.countDown(); });
      manager.getFeatureState(Runnable::run, value -> { feature.set(value); done.countDown(); });
      boolean complete = done.await(12, TimeUnit.SECONDS);
      System.out.println(new JSONObject().put("ok",complete).put("subId",subId)
          .put("registrationState",registration.get()).put("transportType",transport.get())
          .put("featureState",feature.get()));
      if (complete) exit = 0;
    } catch (Throwable error) {
      System.out.println("{\"ok\":false,\"error\":\"" + error.getClass().getSimpleName() + "\"}");
    }
    System.exit(exit);
  }
}
