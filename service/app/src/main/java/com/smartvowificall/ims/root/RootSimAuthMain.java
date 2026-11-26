package com.smartvowificall.ims.root;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import android.telephony.SubscriptionInfo;
import com.smartvowificall.ims.policy.ChipsetStrategy;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.json.JSONObject;

/**
 * Cross-baseband Root SIM AKA daemon serving a protected Unix Domain Socket (0600, root-only).
 * Works across isolated network namespaces (filesystem-backed socket) so vowifi-engine running
 * inside wfctun0 netns can execute standard ITelephony.getIccAuthentication (with APDU logical
 * channel fallback) on Qualcomm, MediaTek, Google Tensor, and Samsung Exynos devices.
 */
public final class RootSimAuthMain {
  public static final String DEFAULT_SOCKET_PATH = "/data/adb/smartvowificall_ims/run/sim-aka.sock";
  private static final String USIM_AID = "A0000000871002";
  private static final String ISIM_AID = "A0000000871004";
  private static final String ENGINE_BIN = "/data/adb/modules/smartvowificall/bin/vowifi-engine-arm64";

  private RootSimAuthMain() {}

  public static void main(String[] args) {
    try {
      if (Process.myUid() != 0) throw new SecurityException("ROOT_REQUIRED");
      if (Looper.myLooper() == null) Looper.prepareMainLooper();
      String socketPath = args.length > 0 && !args[0].isEmpty() ? args[0] : DEFAULT_SOCKET_PATH;
      serve(socketPath);
    } catch (Throwable t) {
      System.err.println("RootSimAuthMain fatal: " + t.getClass().getSimpleName());
      System.exit(1);
    }
  }

  private static void serve(String socketPath) throws Exception {
    File sockFile = new File(socketPath);
    File parent = sockFile.getParentFile();
    if (parent != null && !parent.isDirectory()) {
      parent.mkdirs();
      Os.chmod(parent.getAbsolutePath(), 0700);
    }
    if (sockFile.exists()) sockFile.delete();

    LocalSocket bindSocket = new LocalSocket();
    bindSocket.bind(new LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM));
    Os.chmod(socketPath, 0600);
    Os.listen(bindSocket.getFileDescriptor(), 16);
    LocalServerSocket server = new LocalServerSocket(bindSocket.getFileDescriptor());
    try {
      while (true) {
        LocalSocket client = server.accept();
        handleClient(client);
      }
    } finally {
      server.close();
      bindSocket.close();
      sockFile.delete();
    }
  }

  private static void handleClient(LocalSocket client) {
    try (LocalSocket c = client) {
      // Only allow root (uid 0) engine process to request SIM AKA challenges.
      if (c.getPeerCredentials().getUid() != 0) {
        writeReply(c.getOutputStream(), new JSONObject().put("ok", false).put("code", "UNAUTHORIZED_UID"));
        return;
      }
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
      String line = reader.readLine();
      if (line == null || line.trim().isEmpty()) return;
      JSONObject req = new JSONObject(line);
      String op = req.optString("op", "");
      if ("probe".equals(op)) {
        int slot = req.optInt("slot", 0);
        writeReply(c.getOutputStream(), probe(slot));
        return;
      }
      if (!"authenticate".equals(op)) {
        writeReply(c.getOutputStream(), new JSONObject().put("ok", false).put("code", "UNSUPPORTED_OP"));
        return;
      }
      int slot = req.optInt("slot", -1);
      int appType = req.optInt("appType", 2);
      int authType = req.optInt("authType", 129);
      String challengeB64 = req.optString("challengeBase64", "");
      if (slot < 0 || slot > 7 || challengeB64.isEmpty()) {
        writeReply(c.getOutputStream(), new JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT"));
        return;
      }
      JSONObject reply = authenticate(slot, appType, authType, challengeB64);
      writeReply(c.getOutputStream(), reply);
    } catch (Throwable t) {
      try {
        writeReply(client.getOutputStream(),
            new JSONObject().put("ok", false).put("code", t.getClass().getSimpleName()));
      } catch (Throwable ignored) {
      }
    }
  }

  private static JSONObject probe(int slot) throws Exception {
    ChipsetStrategy.Family family = ChipsetStrategy.detect(
        Build.HARDWARE, Build.BOARD, sysProp("ro.board.platform"), sysProp("ro.soc.manufacturer"));
    int subId = -1;
    try { subId = activeSubIdForSlot(slot); } catch (Throwable ignored) {}
    return new JSONObject()
        .put("ok", true)
        .put("chipset", family.id)
        .put("akaOrder", family.akaOrder)
        .put("qrtrSupported", family.qrtrSupported)
        .put("slot", slot)
        .put("subId", subId)
        .put("apiLevel", Build.VERSION.SDK_INT);
  }

  private static String sysProp(String key) {
    try {
      Object v = Class.forName("android.os.SystemProperties")
          .getMethod("get", String.class, String.class).invoke(null, key, "");
      return v instanceof String ? (String) v : "";
    } catch (Throwable ignored) {
      return "";
    }
  }

  private static JSONObject authenticate(int slot, int appType, int authType, String challengeB64)
      throws Exception {
    int subId = activeSubIdForSlot(slot);
    if (subId < 0) {
      return new JSONObject().put("ok", false).put("code", "SUBSCRIPTION_NOT_FOUND");
    }
    int[] appOrder = appType == 5 ? new int[] {5, 2} : new int[] {appType};
    for (int candidateApp : appOrder) {
      // Primary path: standard Android ITelephony.getIccAuthentication (MTK/Tensor/Exynos/QCOM)
      String resp = callGetIccAuthentication(subId, candidateApp, authType, challengeB64);
      if (resp != null && !resp.isEmpty()) {
        return new JSONObject().put("ok", true).put("method", "getIccAuthentication")
            .put("appType", candidateApp)
            .put("responseBase64", resp);
      }
      // Fallback path: direct logical channel AUTHENTICATE APDU (00 88 00 81 + 61xx GET RESPONSE)
      String apduResp = callAuthenticateViaApdu(subId, candidateApp, challengeB64);
      if (apduResp != null && !apduResp.isEmpty()) {
        return new JSONObject().put("ok", true).put("method", "apduLogicalChannel")
            .put("appType", candidateApp)
            .put("responseBase64", apduResp);
      }
    }
    // Qualcomm DSDS root-netns QRTR fallback (works even when enableModemForSlot=false turns off RIL)
    String qrtrResp = callAuthenticateViaQrtrHelper(slot, challengeB64);
    if (qrtrResp != null && !qrtrResp.isEmpty()) {
      return new JSONObject().put("ok", true).put("method", "qrtrRootNetns")
          .put("appType", appType)
          .put("responseBase64", qrtrResp);
    }
    return new JSONObject().put("ok", false).put("code", "ICC_AUTH_UNAVAILABLE");
  }

  private static String callAuthenticateViaQrtrHelper(int slot, String challengeB64) {
    try {
      if (!new File(ENGINE_BIN).canExecute()) return null;
      java.lang.Process p = new ProcessBuilder(
          ENGINE_BIN, "qrtr-auth", "--slot=" + slot, "--challenge=" + challengeB64)
          .redirectErrorStream(true).start();
      if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return null;
      }
      byte[] out = p.getInputStream().readAllBytes();
      JSONObject j = new JSONObject(new String(out, StandardCharsets.UTF_8).trim());
      if (j.optBoolean("ok", false)) {
        return j.optString("responseBase64", "");
      }
    } catch (Throwable ignored) {}
    return null;
  }

  private static String callGetIccAuthentication(int subId, int appType, int authType, String data) {
    // Primary AOSP service: IPhoneSubInfo.getIccAuthentication (Android 11-17)
    try {
      Object val = binderCall("iphonesubinfo", "com.android.internal.telephony.IPhoneSubInfo",
          "getIccAuthentication",
          new Class<?>[] {int.class, String.class, String.class, int.class, int.class, String.class},
          new Object[] {subId, "root", null, appType, authType, data});
      if (val instanceof String && !((String) val).isEmpty()) return (String) val;
    } catch (Throwable ignored) {
    }
    try {
      Object val = binderCall("iphonesubinfo", "com.android.internal.telephony.IPhoneSubInfo",
          "getIccAuthentication",
          new Class<?>[] {int.class, String.class, int.class, int.class, String.class},
          new Object[] {subId, "root", appType, authType, data});
      if (val instanceof String && !((String) val).isEmpty()) return (String) val;
    } catch (Throwable ignored) {
    }
    // OEM fallback: ITelephony.getIccAuthentication
    try {
      Object val = binderCall("phone", "com.android.internal.telephony.ITelephony",
          "getIccAuthentication",
          new Class<?>[] {int.class, String.class, String.class, int.class, int.class, String.class},
          new Object[] {subId, "root", null, appType, authType, data});
      if (val instanceof String && !((String) val).isEmpty()) return (String) val;
    } catch (Throwable ignored) {
    }
    try {
      Object val = binderCall("phone", "com.android.internal.telephony.ITelephony",
          "getIccAuthentication",
          new Class<?>[] {int.class, String.class, int.class, int.class, String.class},
          new Object[] {subId, "root", appType, authType, data});
      if (val instanceof String && !((String) val).isEmpty()) return (String) val;
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static String callAuthenticateViaApdu(int subId, int appType, String challengeB64) {
    byte[] challenge;
    try {
      challenge = Base64.getDecoder().decode(challengeB64);
    } catch (IllegalArgumentException e) {
      return null;
    }
    String aid = appType == 5 ? ISIM_AID : USIM_AID;
    int channel = -1;
    try {
      Object openResp;
      try {
        openResp = binderCall("phone", "com.android.internal.telephony.ITelephony",
            "iccOpenLogicalChannel",
            new Class<?>[] {int.class, String.class, String.class, int.class},
            new Object[] {subId, "root", aid, 0});
      } catch (NoSuchMethodException ignored) {
        openResp = binderCall("phone", "com.android.internal.telephony.ITelephony",
            "iccOpenLogicalChannel",
            new Class<?>[] {int.class, String.class, int.class},
            new Object[] {subId, aid, 0});
      }
      if (openResp == null) return null;
      channel = (Integer) openResp.getClass().getMethod("getChannel").invoke(openResp);
      if (channel <= 0) return null;
      String hexData = toHex(challenge);
      String hex = transmitLogicalApdu(subId, channel, 0x00, 0x88, 0x00, 0x81, challenge.length, hexData);
      if (hex == null || hex.length() < 4) return null;
      // Handle ISO 7816-4 SW1=0x61 (more data available -> GET RESPONSE 00 C0 00 00 SW2)
      String sw = hex.substring(hex.length() - 4).toUpperCase(java.util.Locale.ROOT);
      if (sw.startsWith("61")) {
        int le = Integer.parseInt(sw.substring(2), 16);
        hex = transmitLogicalApdu(subId, channel, 0x00, 0xC0, 0x00, 0x00, le, "");
        if (hex == null || hex.length() < 4) return null;
        sw = hex.substring(hex.length() - 4).toUpperCase(java.util.Locale.ROOT);
      }
      if (!"9000".equals(sw)) return null;
      byte[] raw = fromHex(hex.substring(0, hex.length() - 4));
      return Base64.getEncoder().encodeToString(raw);
    } catch (Throwable ignored) {
      return null;
    } finally {
      if (channel > 0) {
        try {
          binderCall("phone", "com.android.internal.telephony.ITelephony",
              "iccCloseLogicalChannel",
              new Class<?>[] {int.class, String.class, int.class},
              new Object[] {subId, "root", channel});
        } catch (Throwable ignored) {
          try {
            binderCall("phone", "com.android.internal.telephony.ITelephony",
                "iccCloseLogicalChannel",
                new Class<?>[] {int.class, int.class},
                new Object[] {subId, channel});
          } catch (Throwable ignored2) {}
        }
      }
    }
  }

  private static String transmitLogicalApdu(
      int subId, int channel, int cla, int ins, int p1, int p2, int p3, String hexData) {
    try {
      Object tx = binderCall("phone", "com.android.internal.telephony.ITelephony",
          "iccTransmitApduLogicalChannel",
          new Class<?>[] {int.class, int.class, int.class, int.class, int.class, int.class, int.class, String.class},
          new Object[] {subId, channel, cla, ins, p1, p2, p3, hexData});
      if (tx instanceof String) return ((String) tx).trim();
    } catch (NoSuchMethodException ignored) {
      try {
        Object tx = binderCall("phone", "com.android.internal.telephony.ITelephony",
            "iccTransmitApduLogicalChannel",
            new Class<?>[] {int.class, String.class, int.class, int.class, int.class, int.class, int.class, int.class, String.class},
            new Object[] {subId, "root", channel, cla, ins, p1, p2, p3, hexData});
        if (tx instanceof String) return ((String) tx).trim();
      } catch (Throwable ignored2) {}
    } catch (Throwable ignored) {}
    return null;
  }

  @SuppressWarnings("unchecked")
  private static int activeSubIdForSlot(int slot) throws Exception {
    List<SubscriptionInfo> active;
    try {
      active = (List<SubscriptionInfo>) binderCall("isub",
          "com.android.internal.telephony.ISub", "getActiveSubscriptionInfoList",
          new Class<?>[] {String.class, String.class, boolean.class},
          new Object[] {"root", null, true});
    } catch (NoSuchMethodException ignored) {
      active = (List<SubscriptionInfo>) binderCall("isub",
          "com.android.internal.telephony.ISub", "getActiveSubscriptionInfoList",
          new Class<?>[] {String.class, String.class},
          new Object[] {"root", null});
    }
    if (active != null) {
      for (SubscriptionInfo info : active) {
        if (info.getSimSlotIndex() == slot) return info.getSubscriptionId();
      }
    }
    return -1;
  }

  private static Object binderCall(String service, String interfaceName, String methodName,
      Class<?>[] types, Object[] arguments) throws Exception {
    IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
        .getMethod("getService", String.class)
        .invoke(null, service);
    if (binder == null || !binder.isBinderAlive()) throw new IllegalStateException("SERVICE_UNAVAILABLE");
    Object proxy = Class.forName(interfaceName + "$Stub")
        .getMethod("asInterface", IBinder.class)
        .invoke(null, binder);
    return Class.forName(interfaceName).getMethod(methodName, types).invoke(proxy, arguments);
  }

  private static void writeReply(OutputStream out, JSONObject obj) throws Exception {
    out.write((obj.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static String toHex(byte[] data) {
    StringBuilder sb = new StringBuilder(data.length * 2);
    for (byte b : data) sb.append(String.format("%02X", b & 0xFF));
    return sb.toString();
  }

  private static byte[] fromHex(String hex) {
    int n = hex.length() / 2;
    byte[] out = new byte[n];
    for (int i = 0; i < n; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
