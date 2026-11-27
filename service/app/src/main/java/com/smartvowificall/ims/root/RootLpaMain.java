package com.smartvowificall.ims.root;

import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.os.IBinder;
import android.telephony.UiccCardInfo;
import android.telephony.UiccPortInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Root inventory and call/radio exclusion lease only. Card APDUs use the app's authorized OMAPI. */
public final class RootLpaMain implements AutoCloseable {
  private final Context context;
  private final Object phone;
  private final Class<?> phoneApi;
  private LpaSlotLock lease;
  private int physicalSlot = -1, portIndex = -1, logicalSlot = -1, cardId = -1;
  private volatile long lastRequest = android.os.SystemClock.elapsedRealtime();

  private RootLpaMain() throws Exception {
    if (Process.myUid() != 0) throw new SecurityException("ROOT_REQUIRED");
    if (Looper.myLooper() == null) Looper.prepareMainLooper();
    context = RootIdentityMain.rootContext();
    IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
        .getMethod("getService", String.class).invoke(null, "phone");
    if (binder == null || !binder.isBinderAlive()) throw new IllegalStateException("LPA_TELEPHONY_UNAVAILABLE");
    phoneApi = Class.forName("com.android.internal.telephony.ITelephony");
    phone = Class.forName("com.android.internal.telephony.ITelephony$Stub")
        .getMethod("asInterface", IBinder.class).invoke(null, binder);
  }

  public static void main(String[] args) {
    try (RootLpaMain bridge = new RootLpaMain()) {
      // Process exit releases the per-slot OS lock; APDU channels belong to the Android app.
      Thread watchdog = new Thread(() -> {
        try {
          while (true) {
            Thread.sleep(10000);
            if (android.os.SystemClock.elapsedRealtime() - bridge.lastRequest > 120000) System.exit(1);
          }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
      }, "lpa-idle-timeout");
      watchdog.setDaemon(true); watchdog.start();
      BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      String line;
      while ((line = boundedLine(input)) != null) {
        bridge.lastRequest = android.os.SystemClock.elapsedRealtime();
        JSONObject result;
        try { result = bridge.execute(new JSONObject(line)); }
        catch (Exception e) {
          Throwable cause = e instanceof InvocationTargetException ? ((InvocationTargetException) e).getTargetException() : e;
          String code = cause.getMessage();
          if (code == null || !code.matches("(?:LPA_|CALL_|EMERGENCY_|ROOT_)[A-Z0-9_]{1,70}"))
            code = cause instanceof SecurityException ? "LPA_PERMISSION_DENIED" : "LPA_CARD_ACCESS_FAILED";
          result = new JSONObject().put("ok", false).put("code", code);
        }
        System.out.println(result.toString()); System.out.flush();
      }
    } catch (Exception ignored) {
      System.out.println("{\"ok\":false,\"code\":\"LPA_HELPER_UNAVAILABLE\"}");
    }
    System.exit(0);
  }

  private static String boundedLine(BufferedReader input) throws Exception {
    StringBuilder line = new StringBuilder();
    int c;
    while ((c = input.read()) != -1 && c != '\n') {
      if (line.length() >= 2048) throw new IllegalArgumentException("LPA_REQUEST_TOO_LARGE");
      line.append((char) c);
    }
    return c == -1 && line.length() == 0 ? null : line.toString();
  }

  private JSONObject execute(JSONObject request) throws Exception {
    switch (request.getString("action")) {
      case "cards": {
        JSONArray cards = new JSONArray();
        for (UiccCardInfo card : cards()) {
          if (!card.isRemovable()) continue;
          for (UiccPortInfo port : card.getPorts()) {
            if (port.getLogicalSlotIndex() < 0) continue;
            cards.put(new JSONObject().put("physicalSlot", card.getPhysicalSlotIndex())
                .put("port", port.getPortIndex()).put("logicalSlot", port.getLogicalSlotIndex())
                .put("active", port.isActive()));
          }
        }
        return new JSONObject().put("ok", true).put("cards", cards);
      }
      case "acquire": {
        if (lease != null) throw new IllegalStateException("LPA_SESSION_ALREADY_OPEN");
        physicalSlot = request.getInt("physicalSlot"); portIndex = request.getInt("port");
        UiccCardInfo card = findCard();
        UiccPortInfo port = card.getPorts().stream().filter(p -> p.getPortIndex() == portIndex)
            .findFirst().orElseThrow(() -> new IllegalStateException("LPA_PORT_UNAVAILABLE"));
        logicalSlot = port.getLogicalSlotIndex(); cardId = card.getCardId();
        lease = new LpaSlotLock(logicalSlot);
        try {
          RootIdentityMain.requireLpaAccess(context, logicalSlot);
          return new JSONObject().put("ok", true).put("logicalSlot", logicalSlot);
        } catch (Exception e) { close(); throw e; }
      }
      case "check": {
        if (lease == null) throw new IllegalStateException("LPA_SESSION_INVALID");
        if (findCard().getCardId() != cardId) throw new IllegalStateException("LPA_CARD_CHANGED");
        RootIdentityMain.requireLpaAccess(context, logicalSlot);
        return new JSONObject().put("ok", true);
      }
      case "close": close(); return new JSONObject().put("ok", true);
      default: throw new IllegalArgumentException("LPA_ACTION_INVALID");
    }
  }

  @SuppressWarnings("unchecked")
  private List<UiccCardInfo> cards() throws Exception {
    Object result = phoneApi.getMethod("getUiccCardsInfo", String.class).invoke(phone, "root");
    if (!(result instanceof List)) throw new IllegalStateException("LPA_CARD_INVENTORY_UNAVAILABLE");
    return (List<UiccCardInfo>) result;
  }

  private UiccCardInfo findCard() throws Exception {
    for (UiccCardInfo card : cards()) {
      if (card.getPhysicalSlotIndex() == physicalSlot && card.isRemovable()) return card;
    }
    throw new IllegalStateException("LPA_REMOVABLE_CARD_UNAVAILABLE");
  }

  @Override public void close() {
    if (lease != null) { try { lease.close(); } catch (Exception ignored) {} lease = null; }
  }
}
