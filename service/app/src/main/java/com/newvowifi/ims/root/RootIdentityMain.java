package com.newvowifi.ims.root;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import com.newvowifi.ims.network.InterfaceSnapshot;
import com.newvowifi.ims.network.TunEgress;
import com.newvowifi.ims.policy.PolicyEngine;
import com.newvowifi.ims.policy.ExclusiveRadio;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Root app_process entry point. stdout is a private IPC channel, never a user-facing log. */
public final class RootIdentityMain {
  private static final String CONFIG_DIR = "/data/adb/newvowifi_ims/config";
  private static final String IMS_DIR = "/data/adb/newvowifi_ims/ims";
  private static final String RADIO_DIR = "/data/adb/newvowifi_ims/radio";
  private static final String IMS_PACKAGE = "com.newvowifi.ims";
  private static final String ENGINE = "/data/adb/modules/newvowifi/bin/vowifi-engine-arm64";
  private static final int OUTPUT_LIMIT = 262144;
  private RootIdentityMain() {}

  public static void main(String[] args) {
    int exitCode = 0;
    JSONObject output;
    try {
      if (Process.myUid() != 0)
        throw new Failure("ROOT_REQUIRED");
      String mode = args.length == 0 ? "snapshot" : args[0];
      if (mode.equals("snapshot") && args.length <= 1)
        output = snapshot(rootContext());
      else if (mode.equals("install-check") && args.length == 1) {
        JSONObject current = snapshot(rootContext());
        boolean idle = current.optBoolean("callStateKnown", false)
            && !current.optBoolean("callActive", true);
        // Installation sees only this decision, never the private SIM snapshot.
        output = new JSONObject().put("ok", idle).put("code", idle ? "INSTALL_IDLE" : "INSTALL_WAIT_CALL_IDLE");
      }
      else if ((mode.equals("radio-hold") || mode.equals("radio-check")) && args.length == 5) {
        try (ImsLock lock = new ImsLock()) {
          output = radioHold(rootContext(), nonNegativeInt(args[1]), nonNegativeInt(args[2]),
              args[3], args[4], mode.equals("radio-check"));
        }
      } else if (mode.equals("radio-release-all") && args.length == 1) {
        try (ImsLock lock = new ImsLock()) {
          Context context = rootContext();
          requireIdle(context);
          boolean released = true;
          for (int slot = 0; slot < 8; slot++) released &= releaseRadio(context, slot);
          output = new JSONObject().put("ok", released).put("released", released)
              .put("code", released ? "RADIO_RELEASED" : "RADIO_RESTORE_PENDING");
          if (!released) exitCode = 1;
        }
      } else if (mode.equals("check-egress") && args.length == 2) {
        if (!validOutputPath(args[1])) throw new Failure("INVALID_OUTPUT_PATH");
        JSONObject config = new JSONObject(new String(Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8));
        String iface = TunEgress.interfaceFromSelector(config.optString("transport", ""));
        requireTun(iface);
        Context context = rootContext();
        int slot = config.getInt("slot");
        Identity identity = requireCurrentIdentity(context, currentSubForSlot(context, slot), slot);
        if (!identity.iccid.equals(config.optString("iccid"))) throw new Failure("SUBSCRIPTION_CHANGED");
        requireRadioOff(identity);
        output = new JSONObject().put("ok", true).put("code", "TUN_EGRESS_CONFIRMED");
      }
      else if (mode.equals("ims-bind") && args.length == 5) {
        try (ImsLock lock = new ImsLock()) {
          output = bindIms(
              rootContext(), nonNegativeInt(args[1]), nonNegativeInt(args[2]), args[3], args[4]);
        }
      } else if (mode.equals("ims-restore") && args.length == 2) {
        try (ImsLock lock = new ImsLock()) {
          output = restoreIms(rootContext(), nonNegativeInt(args[1]));
        }
      } else if (mode.equals("ims-restore-all") && args.length == 1) {
        try (ImsLock lock = new ImsLock()) {
          Context context = rootContext();
          int slots = telephony(context).getActiveModemCount();
          if (slots < 1 || slots > 8)
            throw new Failure("MODEM_COUNT_UNKNOWN");
          for (int slot = 0; slot < slots; slot++) restoreIms(context, slot);
          output = new JSONObject().put("ok", true).put("code", "IMS_RESTORED");
        }
      } else if (mode.equals("prepare") && args.length == 5) {
        int subId = nonNegativeInt(args[1]);
        int slotId = nonNegativeInt(args[2]);
        if (slotId > 1 || !InterfaceSnapshot.validName(args[3]) || !validOutputPath(args[4]))
          throw new Failure("INVALID_ARGUMENT");
        output = prepare(rootContext(), subId, slotId, args[3], args[4]);
      } else
        throw new Failure("INVALID_ARGUMENT");
    } catch (Throwable error) {
      exitCode = 1;
      String code = error instanceof Failure ? ((Failure) error).code : "IDENTITY_UNAVAILABLE";
      output = new JSONObject();
      try {
        output.put("ok", false).put("code", code).put("callActive", true);
      } catch (Exception ignored) {
      }
    }
    System.out.println(output.toString());
    System.out.flush();
    // ActivityThread may create binder or service threads; this is a one-shot helper.
    System.exit(exitCode);
  }

  private static Context rootContext() throws Exception {
    if (Looper.myLooper() == null)
      Looper.prepareMainLooper();
    Class<?> activityThread = Class.forName("android.app.ActivityThread");
    Object thread = activityThread.getMethod("systemMain").invoke(null);
    Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    // This process really runs as uid 0. Keep its attribution consistent with that identity.
    // SoftVoWiFi's existing identity helper likewise uses the actual root caller package.
    return new ContextWrapper(system) {
      private final AttributionSource attribution = new AttributionSource.Builder(Process.myUid())
                                                        .setPackageName("root")
                                                        .setPid(Process.myPid())
                                                        .build();
      @Override
      public Context getApplicationContext() {
        return this;
      }
      @Override
      public String getPackageName() {
        return "root";
      }
      @Override
      public String getOpPackageName() {
        return "root";
      }
      @Override
      public String getAttributionTag() {
        return null;
      }
      @Override
      public AttributionSource getAttributionSource() {
        return attribution;
      }
    };
  }

  private static TelephonyManager telephony(Context context) throws Exception {
    Constructor<TelephonyManager> constructor =
        TelephonyManager.class.getDeclaredConstructor(Context.class);
    constructor.setAccessible(true);
    return constructor.newInstance(context);
  }

  @SuppressWarnings("unchecked")
  private static List<SubscriptionInfo> activeSubscriptions(Context context) throws Exception {
    // Same ISub call as SubscriptionManager, without its failure-to-empty-list conversion or
    // UI visibility filter. An unavailable phone service must not look like all SIMs vanished.
    List<SubscriptionInfo> active = (List<SubscriptionInfo>) binderCall("isub",
        "com.android.internal.telephony.ISub", "getActiveSubscriptionInfoList",
        new Class<?>[] {String.class, String.class, boolean.class},
        new Object[] {"root", null, true});
    if (active == null)
      throw new Failure("SUBSCRIPTIONS_UNAVAILABLE");
    return active;
  }

  private static Object binderCall(String service, String interfaceName, String methodName,
      Class<?>[] types, Object[] arguments) throws Exception {
    IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                         .getMethod("getService", String.class)
                         .invoke(null, service);
    if (binder == null || !binder.isBinderAlive())
      throw new Failure("SERVICE_UNAVAILABLE");
    Object proxy = Class.forName(interfaceName + "$Stub")
                       .getMethod("asInterface", IBinder.class)
                       .invoke(null, binder);
    if (proxy == null)
      throw new Failure("SERVICE_UNAVAILABLE");
    return Class.forName(interfaceName).getMethod(methodName, types).invoke(proxy, arguments);
  }

  private static int subscriptionCallState(int subId) throws Exception {
    // TelephonyManager otherwise converts a dead Binder/RemoteException to IDLE.
    return (Integer) binderCall("phone", "com.android.internal.telephony.ITelephony",
        "getCallStateForSubscription", new Class<?>[] {int.class, String.class, String.class},
        new Object[] {subId, "root", null});
  }

  private static int globalCallState() throws Exception {
    return (Integer) binderCall("telecom", "com.android.internal.telecom.ITelecomService",
        "getCallStateUsingPackage", new Class<?>[] {String.class, String.class},
        new Object[] {"root", null});
  }

  private static String imsPackage(int slot, int feature) throws Exception {
    Object value = binderCall("phone", "com.android.internal.telephony.ITelephony",
        "getBoundImsServicePackage", new Class<?>[] {int.class, boolean.class, int.class},
        new Object[] {slot, true, feature});
    if (!(value instanceof String))
      throw new Failure("IMS_CONFIG_UNKNOWN");
    String result = (String) value;
    if (!result.isEmpty() && !result.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
      throw new Failure("IMS_CONFIG_UNKNOWN");
    return result;
  }

  private static void setMmTel(int slot, String packageName) throws Exception {
    Object accepted = binderCall("phone", "com.android.internal.telephony.ITelephony",
        "setBoundImsServiceOverride",
        new Class<?>[] {int.class, int.class, boolean.class, int[].class, String.class},
        new Object[] {slot, -10000, true, new int[] {1}, packageName});
    if (!Boolean.TRUE.equals(accepted))
      throw new Failure("IMS_SET_REJECTED");
    // Queue a getter after the setter on the resolver Handler; this is a configuration barrier,
    // not proof that a MmTelFeature has already connected or registered.
    imsPackage(slot, 1);
  }

  private static int currentSubForSlot(Context context, int slot) throws Exception {
    int result = -1;
    for (SubscriptionInfo info : activeSubscriptions(context))
      if (info.getSimSlotIndex() == slot) {
        if (result != -1)
          throw new Failure("SUBSCRIPTION_AMBIGUOUS");
        result = info.getSubscriptionId();
      }
    return result;
  }

  private static void requireIdle(Context context) throws Exception {
    if (snapshot(context).optBoolean("callActive", true))
      throw new Failure("CALL_ACTIVE_OR_UNKNOWN");
  }

  private static void refreshSlotConfiguration(Context context, int slot, int subId)
      throws Exception {
    // The OEM resolver's test override recalculates all carrier feature entries from EMPTY_MAP.
    // Re-read the *existing* CarrierConfig for this slot. We do not write feature 0/2 override
    // entries, so their original manual-vs-carrier provenance remains in the resolver untouched.
    // app_process's synthetic ActivityThread is not an AMS-registered application thread.
    // The root shell broadcast uses a null caller thread and targets only the phone package.
    run(new ProcessBuilder("/system/bin/am", "broadcast", "--user", "0", "-a",
            "android.telephony.action.CARRIER_CONFIG_CHANGED", "-p", "com.android.phone",
            "--receiver-registered-only", "--receiver-foreground", "--ei",
            "android.telephony.extra.SLOT_INDEX", Integer.toString(slot), "--ei",
            "android.telephony.extra.SUBSCRIPTION_INDEX", Integer.toString(subId)),
        new byte[0], 10000, "IMS_CONFIG_REFRESH_FAILED");
  }

  private static File imsJournal(int slot) throws Exception {
    if (slot < 0 || slot > 7)
      throw new Failure("INVALID_SLOT");
    File dir = new File(IMS_DIR), file = new File(dir, "slot-" + slot + ".json");
    if (!dir.getCanonicalPath().equals(IMS_DIR)
        || !file.getCanonicalPath().equals(file.getAbsolutePath()))
      throw new Failure("INVALID_JOURNAL_PATH");
    return file;
  }

  /** Serializes app recovery, module disable and uninstall across separate app_process calls. */
  private static final class ImsLock implements AutoCloseable {
    final RandomAccessFile file;
    final FileLock lock;
    ImsLock() throws Exception {
      File dir = new File(IMS_DIR), path = new File(dir, ".transaction.lock");
      if (!dir.getCanonicalPath().equals(IMS_DIR)
          || !path.getCanonicalPath().equals(path.getAbsolutePath()))
        throw new Failure("INVALID_JOURNAL_PATH");
      if (!dir.isDirectory() && !dir.mkdirs())
        throw new Failure("JOURNAL_WRITE_FAILED");
      Os.chmod(IMS_DIR, 0700);
      file = new RandomAccessFile(path, "rw");
      FileLock acquired = null;
      try {
        Os.chmod(path.getAbsolutePath(), 0600);
        acquired = file.getChannel().tryLock();
        if (acquired == null)
          throw new Failure("IMS_TRANSACTION_BUSY");
      } catch (Exception e) {
        file.close();
        throw e;
      }
      lock = acquired;
    }
    @Override
    public void close() throws Exception {
      try {
        lock.release();
      } finally {
        file.close();
      }
    }
  }

  private static void verifyFeatures(
      Context context, int slot, int subId, String f0, String f1, String f2) throws Exception {
    // Broadcast delivery and resolver feature queries are asynchronous. Poll the same slot only.
    for (int attempt = 0; attempt < 20; attempt++) {
      if (currentSubForSlot(context, slot) != subId)
        throw new Failure("SUBSCRIPTION_CHANGED");
      if (f0.equals(imsPackage(slot, 0)) && f1.equals(imsPackage(slot, 1))
          && f2.equals(imsPackage(slot, 2)))
        return;
      Thread.sleep(100);
    }
    throw new Failure("IMS_RESTORE_VERIFY_FAILED");
  }

  private static JSONObject bindIms(Context context, int subId, int slot, String key, String home)
      throws Exception {
    if (new File("/data/adb/modules/newvowifi/disable").exists()
        || new File("/data/adb/modules/newvowifi/remove").exists()
        || new File("/data/adb/newvowifi_ims/rollback-pending").exists())
      throw new Failure("MODULE_DISABLED_OR_ROLLBACK");
    File journalFile = imsJournal(slot);
    String f0 = imsPackage(slot, 0), previous = imsPackage(slot, 1), f2 = imsPackage(slot, 2);
    if (!previous.isEmpty() && !previous.equals(IMS_PACKAGE))
      throw new Failure("OTHER_CARRIER_OVERRIDE_PRESENT");
    requireIdle(context);
    Identity before = requireCurrentIdentity(context, subId, slot);
    if (!sha256(before.iccid).equals(key) || !before.homePlmn.equals(home))
      throw new Failure("SUBSCRIPTION_CHANGED");
    requireRadioOff(before);
    if (!previous.equals(IMS_PACKAGE)) {
      JSONObject journal =
          new JSONObject()
              .put("owner", IMS_PACKAGE)
              .put("slot", slot)
              .put("subId", subId)
              .put("simKey", key)
              .put("homePlmn", home)
              .put("previous1", "")
              .put("feature0", f0)
              .put("feature2", f2)
              .put("provenance", "only-feature1-written;feature0-and-2-manual-map-never-modified");
      writePrivateFile(journalFile, journal.toString().getBytes(StandardCharsets.UTF_8), IMS_DIR);
    } else if (!journalFile.isFile())
      throw new Failure("IMS_JOURNAL_MISSING");
    try {
      setMmTel(slot, IMS_PACKAGE);
      refreshSlotConfiguration(context, slot, subId);
      verifyFeatures(context, slot, subId, f0, IMS_PACKAGE, f2);
      Identity after = requireCurrentIdentity(context, subId, slot);
      if (!before.sameIdentity(after))
        throw new Failure("SUBSCRIPTION_CHANGED");
    } catch (Exception failure) {
      try {
        restoreIms(context, slot);
      } catch (Exception ignored) { /* Journal remains for recovery. */
      }
      throw failure;
    }
    return new JSONObject().put("ok", true).put("code", "IMS_CONFIG_SELECTED");
  }

  private static JSONObject restoreIms(Context context, int slot) throws Exception {
    File journalFile = imsJournal(slot);
    boolean selected = IMS_PACKAGE.equals(imsPackage(slot, 1));
    if (!selected && !journalFile.isFile()) {
      return new JSONObject().put("ok", true).put("code", "IMS_ALREADY_RELEASED");
    }
    requireIdle(context);
    int subId = currentSubForSlot(context, slot);
    String f0 = imsPackage(slot, 0), f2 = imsPackage(slot, 2);
    String previous = "";
    if (journalFile.isFile()) {
      JSONObject journal = new JSONObject(
          new String(Files.readAllBytes(journalFile.toPath()), StandardCharsets.UTF_8));
      if (!IMS_PACKAGE.equals(journal.optString("owner")) || journal.optInt("slot", -1) != slot
          || !journal.optString("previous1", "invalid").isEmpty())
        throw new Failure("IMS_JOURNAL_INVALID");
      if (journal.optInt("subId", -2) == subId) {
        f0 = journal.getString("feature0");
        f2 = journal.getString("feature2");
      }
    }
    // Do not replay an old subscription's carrier package when a new SIM occupies this slot.
    if (selected)
      setMmTel(slot, previous);
    refreshSlotConfiguration(context, slot, subId);
    for (int attempt = 0; attempt < 20; attempt++) {
      if (currentSubForSlot(context, slot) != subId)
        throw new Failure("SUBSCRIPTION_CHANGED");
      if (f0.equals(imsPackage(slot, 0)) && !IMS_PACKAGE.equals(imsPackage(slot, 1))
          && f2.equals(imsPackage(slot, 2))) {
        Files.deleteIfExists(journalFile.toPath());
        return new JSONObject().put("ok", true).put("code", "IMS_RESTORED");
      }
      Thread.sleep(100);
    }
    throw new Failure("IMS_RESTORE_VERIFY_FAILED");
  }

  private static final class Identity {
    int subId, slotId, simState, rawSimState, phoneId = -1, callState = -1, carrierId = -1;
    String iccid = "", homePlmn = "", label = "";
    String iccidSource = "UNAVAILABLE";
    boolean activeRecordIccidAvailable;
    final List<String> identityIssues = new ArrayList<>();
    boolean ready, callStateKnown;
    String identityCode() { return identityIssues.isEmpty() ? "IDENTITY_READY" : identityIssues.get(0); }
    boolean sameIdentity(Identity other) {
      return other != null && subId == other.subId && slotId == other.slotId
          && iccid.equals(other.iccid) && homePlmn.equals(other.homePlmn);
    }
  }

  private static File radioJournal(int slot) throws Exception {
    if (slot < 0 || slot > 7) throw new Failure("INVALID_SLOT");
    File result = new File(RADIO_DIR, slot + ".json");
    if (!result.getCanonicalPath().equals(result.getAbsolutePath()))
      throw new Failure("RADIO_PATH_INVALID");
    return result;
  }
  private static int radioState(Identity identity) throws Exception {
    if (identity.phoneId < 0) throw new Failure("RADIO_STATE_UNKNOWN");
    // Per-slot modem stack status (PhoneConfigurationManager / RIL_REQUEST_ENABLE_MODEM).
    // On Qualcomm DSDS, setRadioForSubscriber triggers shared LPM across both SIMs;
    // enableModemForSlot disables only the target slot's cellular stack while preserving
    // the mainland SIM and UICC QRTR AKA authentication.
    Object enabled = binderCall("phone", "com.android.internal.telephony.ITelephony", "isModemEnabledForSlot",
        new Class<?>[] {int.class, String.class, String.class}, new Object[] {identity.phoneId, "root", null});
    if (!(enabled instanceof Boolean)) throw new Failure("RADIO_STATE_UNKNOWN");
    return ((Boolean) enabled) ? ExclusiveRadio.ON : ExclusiveRadio.OFF;
  }
  private static boolean airplaneModeOn() throws Exception {
    // ActivityThread.systemMain() is not an AMS-registered app process, so ContentResolver
    // rejects SettingsProvider queries; read global setting through /system/bin/settings.
    String value = run(new ProcessBuilder("/system/bin/settings", "get", "global", "airplane_mode_on"),
        new byte[0], 5000, "AIRPLANE_STATE_UNKNOWN");
    if ("0".equals(value)) return false;
    if ("1".equals(value)) return true;
    throw new Failure("AIRPLANE_STATE_UNKNOWN");
  }
  private static JSONObject readRadioLease(Identity identity) throws Exception {
    File file = radioJournal(identity.slotId);
    if (!file.isFile()) return null;
    JSONObject value = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    if (!IMS_PACKAGE.equals(value.optString("owner")) || value.optInt("slot", -1) != identity.slotId
        || value.optInt("subId", -1) != identity.subId
        || !sha256(identity.iccid).equals(value.optString("simKey"))
        || !identity.homePlmn.equals(value.optString("homePlmn")) || !value.has("restoreOn"))
      throw new Failure("RADIO_LEASE_IDENTITY_CHANGED");
    return value;
  }
  private static void requireRadioOff(Identity identity) throws Exception {
    if (readRadioLease(identity) == null || radioState(identity) != ExclusiveRadio.OFF)
      throw new Failure("CELLULAR_RADIO_NOT_OFF");
  }
  private static ExclusiveRadio.Backend radioBackend(Context context, Identity identity) {
    return new ExclusiveRadio.Backend() {
      public int state() throws Exception { return radioState(identity); }
      public boolean hasLease() throws Exception { return readRadioLease(identity) != null; }
      public boolean restoreOn() throws Exception { return readRadioLease(identity).getBoolean("restoreOn"); }
      public void saveLease(boolean restoreOn) throws Exception {
        requireIdle(context);
        JSONObject value = new JSONObject().put("owner", IMS_PACKAGE).put("slot", identity.slotId)
            .put("subId", identity.subId).put("simKey", sha256(identity.iccid))
            .put("homePlmn", identity.homePlmn).put("restoreOn", restoreOn);
        writePrivateFile(radioJournal(identity.slotId), value.toString().getBytes(StandardCharsets.UTF_8), RADIO_DIR);
      }
      public void power(boolean on) throws Exception {
        requireIdle(context);
        // Emergency callback mode can remain active after Telecom becomes idle.
        for (SubscriptionInfo info : activeSubscriptions(context)) {
          Object ecbm = binderCall("phone", "com.android.internal.telephony.ITelephony",
              "getEmergencyCallbackMode", new Class<?>[] {int.class},
              new Object[] {info.getSubscriptionId()});
          if (Boolean.TRUE.equals(ecbm))
            throw new Failure("EMERGENCY_CALLBACK_ACTIVE");
        }
        Identity fresh = requireCurrentIdentity(context, identity.subId, identity.slotId);
        if (!identity.sameIdentity(fresh)) throw new Failure("SUBSCRIPTION_CHANGED");
        // Both requestRadioPowerOffForReason and setRadioForSubscriber power off both SIMs
        // on this Qualcomm DSDS modem. enableModemForSlot sends ENABLE_MODEM to phoneId only.
        Object ok = binderCall("phone", "com.android.internal.telephony.ITelephony", "enableModemForSlot",
            new Class<?>[] {int.class, boolean.class}, new Object[] {identity.phoneId, on});
        if (!Boolean.TRUE.equals(ok)) throw new Failure("RADIO_COMMAND_REJECTED");
      }
      public void forget() throws Exception { Files.deleteIfExists(radioJournal(identity.slotId).toPath()); }
    };
  }
  private static JSONObject radioHold(Context context, int sub, int slot, String key, String home,
      boolean checkOnly) throws Exception {
    if (!checkOnly && (new File("/data/adb/modules/newvowifi/disable").exists()
        || new File("/data/adb/modules/newvowifi/remove").exists()
        || new File("/data/adb/newvowifi_ims/rollback-pending").exists()))
      throw new Failure("MODULE_DISABLED_OR_ROLLBACK");
    Identity identity = requireCurrentIdentity(context, sub, slot);
    if (!sha256(identity.iccid).equals(key) || !identity.homePlmn.equals(home))
      throw new Failure("SUBSCRIPTION_CHANGED");
    boolean off;
    if (checkOnly) { requireRadioOff(identity); off = true; }
    else off = ExclusiveRadio.hold(radioBackend(context, identity));
    return new JSONObject().put("ok", true).put("radioOff", off)
        .put("code", off ? "CELLULAR_RADIO_OFF" : "RADIO_OFF_PENDING");
  }
  private static boolean releaseRadio(Context context, int slot) throws Exception {
    File file = radioJournal(slot);
    if (!file.isFile()) return true;
    JSONObject lease = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    if (!IMS_PACKAGE.equals(lease.optString("owner")) || lease.optInt("slot", -1) != slot)
      throw new Failure("RADIO_LEASE_INVALID");
    // Do not apply an old SIM's radio preference to a replacement card.
    int sub = currentSubForSlot(context, slot);
    if (sub != lease.optInt("subId", -1)) throw new Failure("RADIO_LEASE_IDENTITY_CHANGED");
    Identity identity = requireCurrentIdentity(context, sub, slot);
    readRadioLease(identity);
    if (IMS_PACKAGE.equals(imsPackage(slot, 1))
        || new File("/data/adb/newvowifi_ims/run/" + identity.iccid + ".pid").exists())
      throw new Failure("RADIO_WAIT_IMS_STOP");
    return ExclusiveRadio.release(radioBackend(context, identity), airplaneModeOn());
  }

  private static int subscriptionMapping(String method, int subId) throws Exception {
    return (Integer) binderCall("isub", "com.android.internal.telephony.ISub", method,
        new Class<?>[] {int.class}, new Object[] {subId});
  }

  private static String currentIccid(int subId) throws Exception {
    Object value = binderCall("iphonesubinfo", "com.android.internal.telephony.IPhoneSubInfo",
        "getIccSerialNumberForSubscriber", new Class<?>[] {int.class, String.class, String.class},
        new Object[] {subId, "root", null});
    return value instanceof String ? normalizeIccid((String) value) : "";
  }

  private static int currentRawSimState(int phoneId) throws Exception {
    return (Integer) binderCall("phone", "com.android.internal.telephony.ITelephony",
        "getSimStateForSlotIndex", new Class<?>[] {int.class}, new Object[] {phoneId});
  }

  private static boolean mainlandHome(String homePlmn) {
    return PolicyEngine.validHomePlmn(homePlmn)
        && (homePlmn.startsWith("460") || homePlmn.startsWith("461"));
  }

  private static String currentMainlandRecordIccid(Context context, Identity expected) throws Exception {
    // This is a fresh ISub record, not EF_ICCID. Permit it only for excluded mainland SIMs;
    // foreign SIM provisioning and takeover still require the direct PhoneSubInfo identity.
    if (!mainlandHome(expected.homePlmn)) return "";
    SubscriptionInfo found = null;
    for (SubscriptionInfo current : activeSubscriptions(context)) {
      if (current.getSubscriptionId() != expected.subId) continue;
      if (found != null || current.getSimSlotIndex() != expected.slotId) throw new Failure("SUBSCRIPTION_CHANGED");
      found = current;
    }
    if (found == null) throw new Failure("SUBSCRIPTION_CHANGED");
    String operator = emptyIfNull(found.getMccString()) + emptyIfNull(found.getMncString());
    if (!expected.homePlmn.equals(operator)) throw new Failure("SUBSCRIPTION_CHANGED");
    return normalizeIccid(found.getIccId());
  }

  private static String currentHomePlmn(int phoneId) throws Exception {
    // This is the live SIM/HPLMN property read by AOSP getSimOperatorNumericForPhone. Do not use
    // gsm.operator.numeric (visited network) or a previously saved SubscriptionInfo/ICCID value.
    String values = (String) Class.forName("android.os.SystemProperties")
                        .getMethod("get", String.class, String.class)
                        .invoke(null, "gsm.sim.operator.numeric", "");
    String[] perPhone = values.split(",", -1);
    return phoneId >= 0 && phoneId < perPhone.length ? perPhone[phoneId] : "";
  }

  private static String readFailureCode(String field, Throwable error) {
    while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
    if (error instanceof SecurityException) return field + "_DENIED";
    if (error instanceof NoSuchMethodException || error instanceof ClassNotFoundException)
      return field + "_API_UNAVAILABLE";
    if (error instanceof android.os.RemoteException) return field + "_REMOTE_FAILURE";
    if (error instanceof Failure && ((Failure) error).code.equals("SERVICE_UNAVAILABLE"))
      return field + "_SERVICE_UNAVAILABLE";
    return field + "_READ_FAILED";
  }

  private static Identity readIdentity(Context context, SubscriptionInfo info) throws Exception {
    Identity row = new Identity();
    row.subId = info.getSubscriptionId();
    row.slotId = info.getSimSlotIndex();
    row.carrierId = info.getCarrierId();
    CharSequence label = info.getCarrierName();
    row.label = label == null ? "" : label.toString();
    try {
      row.phoneId = subscriptionMapping("getPhoneId", row.subId);
      int currentSlot = subscriptionMapping("getSlotIndex", row.subId);
      if (row.phoneId < 0 || currentSlot != row.slotId) row.identityIssues.add("SUBSCRIPTION_MAPPING_CHANGED");
    } catch (Exception error) {
      row.identityIssues.add(readFailureCode("SUBSCRIPTION_MAPPING", error));
    }
    try {
      if (row.phoneId >= 0) {
        row.rawSimState = currentRawSimState(row.phoneId);
        // ITelephony returns the internal LOADED state (10); public SIM_STATE_READY is 5.
        row.simState = row.rawSimState == 10 ? TelephonyManager.SIM_STATE_READY : row.rawSimState;
      }
      if (row.simState != TelephonyManager.SIM_STATE_READY) row.identityIssues.add("SIM_NOT_READY");
    } catch (Exception error) {
      row.identityIssues.add(readFailureCode("SIM_STATE", error));
    }
    try {
      row.homePlmn = currentHomePlmn(row.phoneId);
      if (!PolicyEngine.validHomePlmn(row.homePlmn)) row.identityIssues.add("HOME_PLMN_UNAVAILABLE");
      String mcc = emptyIfNull(info.getMccString()), mnc = emptyIfNull(info.getMncString());
      // Fresh ISub metadata is a consistency check, never a fallback source of identity.
      if (!mcc.isEmpty() && !mnc.isEmpty() && !(mcc + mnc).equals(row.homePlmn))
        row.identityIssues.add("HOME_PLMN_SUBSCRIPTION_MISMATCH");
    } catch (Exception error) {
      row.identityIssues.add(readFailureCode("HOME_PLMN", error));
    }
    try {
      String recordIccid = normalizeIccid(info.getIccId());
      row.activeRecordIccidAvailable = !recordIccid.isEmpty();
      row.iccid = currentIccid(row.subId);
      if (!row.iccid.isEmpty()) row.iccidSource = "PHONE_SUB_INFO";
      if (row.iccid.isEmpty() && mainlandHome(row.homePlmn) && !recordIccid.isEmpty()) {
        String currentRecord = currentMainlandRecordIccid(context, row);
        if (recordIccid.equals(currentRecord)) {
          row.iccid = currentRecord;
          row.iccidSource = "ACTIVE_SUBSCRIPTION_RECORD";
        } else row.identityIssues.add("ICCID_SUBSCRIPTION_MISMATCH");
      }
      if (row.iccid.isEmpty()) row.identityIssues.add("ICCID_UNAVAILABLE");
      if (!recordIccid.isEmpty() && !recordIccid.equals(row.iccid)) row.identityIssues.add("ICCID_SUBSCRIPTION_MISMATCH");
    } catch (Exception error) {
      row.identityIssues.add(readFailureCode("ICCID", error));
    }
    try {
      String serialAgain = currentIccid(row.subId);
      boolean serialMatches = serialAgain.equals(row.iccid);
      if (!serialMatches && serialAgain.isEmpty() && row.iccidSource.equals("ACTIVE_SUBSCRIPTION_RECORD"))
        serialMatches = row.iccid.equals(currentMainlandRecordIccid(context, row));
      int stateAgain = row.phoneId < 0 ? 0 : currentRawSimState(row.phoneId);
      if (subscriptionMapping("getPhoneId", row.subId) != row.phoneId
          || subscriptionMapping("getSlotIndex", row.subId) != row.slotId
          || !serialMatches || !currentHomePlmn(row.phoneId).equals(row.homePlmn)
          || (stateAgain != TelephonyManager.SIM_STATE_READY && stateAgain != 10))
        row.identityIssues.add("SUBSCRIPTION_CHANGED_DURING_READ");
    } catch (Exception error) {
      row.identityIssues.add(readFailureCode("IDENTITY_RECHECK", error));
    }
    // An empty/redacted/changed identity is never replaced with an old saved ICCID.
    row.ready = row.identityIssues.isEmpty() && row.simState == TelephonyManager.SIM_STATE_READY
        && !row.iccid.isEmpty() && PolicyEngine.validHomePlmn(row.homePlmn);
    try {
      row.callState = subscriptionCallState(row.subId);
      row.callStateKnown = row.callState == TelephonyManager.CALL_STATE_IDLE
          || row.callState == TelephonyManager.CALL_STATE_RINGING
          || row.callState == TelephonyManager.CALL_STATE_OFFHOOK;
    } catch (Exception ignored) {
      row.callState = -1;
    }
    return row;
  }

  private static JSONObject snapshot(Context context) throws Exception {
    JSONArray sims = new JSONArray();
    boolean callActive = false, callKnown = true;
    for (SubscriptionInfo info : activeSubscriptions(context)) {
      Identity row = readIdentity(context, info);
      JSONObject sim = new JSONObject()
                           .put("subId", row.subId)
                           .put("slotId", row.slotId)
                           .put("iccid", row.iccid)
                            .put("iccidSource", row.iccidSource)
                            .put("activeRecordIccidAvailable", row.activeRecordIccidAvailable)
                           .put("simKey", row.iccid.isEmpty() ? "" : sha256(row.iccid))
                           .put("homePlmn", row.homePlmn)
                           .put("ready", row.ready)
                           .put("simState", row.simState)
                            .put("rawSimState", row.rawSimState)
                            .put("phoneId", row.phoneId)
                            .put("carrierId", row.carrierId)
                            .put("identityCode", row.identityCode())
                            .put("identityIssues", new JSONArray(row.identityIssues))
                           .put("callState", row.callState)
                           .put("callStateKnown", row.callStateKnown)
                           .put("label", row.label);
      sims.put(sim);
      callKnown &= row.callStateKnown;
      callActive |= !row.callStateKnown || row.callState != TelephonyManager.CALL_STATE_IDLE;
    }
    // Also cover non-subscription Telecom calls; no actual call is placed or answered.
    try {
      int global = globalCallState();
      boolean known = global >= TelephonyManager.CALL_STATE_IDLE
          && global <= TelephonyManager.CALL_STATE_OFFHOOK;
      callKnown &= known;
      callActive |= !known || global != TelephonyManager.CALL_STATE_IDLE;
    } catch (Exception ignored) {
      callKnown = false;
      callActive = true;
    }
    return new JSONObject()
        .put("ok", true)
        .put("code", "SNAPSHOT")
        .put("sims", sims)
        .put("callStateKnown", callKnown)
        .put("callActive", callActive);
  }

  private static Identity requireCurrentIdentity(Context context, int subId, int slotId)
      throws Exception {
    Identity found = null;
    for (SubscriptionInfo info : activeSubscriptions(context)) {
      if (info.getSubscriptionId() != subId)
        continue;
      if (found != null || info.getSimSlotIndex() != slotId)
        throw new Failure("SUBSCRIPTION_CHANGED");
      found = readIdentity(context, info);
    }
    if (found == null)
      throw new Failure("SUBSCRIPTION_CHANGED");
    if (!found.ready)
      throw new Failure(found.identityCode());
    String mcc = found.homePlmn.substring(0, 3);
    if (mcc.equals("460") || mcc.equals("461"))
      throw new Failure("MAINLAND_SIM");
    return found;
  }

  private static JSONObject prepare(
      Context context, int subId, int slotId, String iface, String outputPath) throws Exception {
    requireTun(iface);
    Identity before = requireCurrentIdentity(context, subId, slotId);
    requireRadioOff(before);
    if (!before.callStateKnown || before.callState != TelephonyManager.CALL_STATE_IDLE)
      throw new Failure("CALL_ACTIVE_OR_UNKNOWN");
    JSONObject template = new JSONObject()
                              .put("enabled", true)
                              .put("transport", TunEgress.selector(iface))
                              .put("slot", slotId)
                              .put("iccid", before.iccid)
                              .put("mcc", before.homePlmn.substring(0, 3))
                               .put("mnc", before.homePlmn.substring(3))
                               .put("mncLen", before.homePlmn.length() - 3)
                               .put("imsRealm", homeImsRealm(before.homePlmn))
                               .put("epdgFqdn", "")
                               .put("dns", "")
                               .put("smsc", "");
    // No old profile, phone number, SMSC or cached IMS identity enters this candidate.
    if (before.carrierId > 0) template.put("carrierId", before.carrierId);
    SimCredentials credentials;
    try {
      credentials = SimCredentials.read((method, id, attributed) -> binderCall("iphonesubinfo",
          "com.android.internal.telephony.IPhoneSubInfo", method,
          attributed ? new Class<?>[] {int.class, String.class, String.class} : new Class<?>[] {int.class},
          attributed ? new Object[] {id, "root", null} : new Object[] {id}), subId, before.phoneId);
    } catch (SimCredentials.ReadFailure e) {
      throw new Failure(e.code);
    }
    for (Map.Entry<String, String> field : credentials.fields().entrySet())
      template.put(field.getKey(), field.getValue());
    validateConfig(template, before, iface);
    writeValidatedConfig(context, before, outputPath, template);
    return new JSONObject().put("ok", true).put("code", "PREPARED");
  }

  private static void validateConfig(JSONObject config, Identity current, String iface)
      throws Exception {
    if (!validPreparedIdentity(current.iccid, current.homePlmn, current.slotId,
            config.optString("iccid", ""), config.optString("mcc", ""), config.optString("mnc", ""),
            config.optInt("slot", -1), config.optString("imsi", ""), config.optString("imei", ""))
        || !config.optBoolean("enabled", false)
        || !("iface:" + iface).equals(config.optString("transport", ""))
        || config.optInt("mncLen", -1) != current.homePlmn.length() - 3
        || !homeImsRealm(current.homePlmn).equals(config.optString("imsRealm", ""))
        || !config.has("epdgFqdn") || !config.optString("epdgFqdn").isEmpty()
        || !config.has("dns") || !config.optString("dns").isEmpty()
        || !config.has("smsc") || !config.optString("smsc").isEmpty()
        || (current.carrierId > 0 && config.optInt("carrierId", -1) != current.carrierId))
      throw new Failure("CONFIG_IDENTITY_MISMATCH");
  }

  static String homeImsRealm(String homePlmn) {
    if (!PolicyEngine.validHomePlmn(homePlmn)) throw new IllegalArgumentException("INVALID_HOME_PLMN");
    String mnc = homePlmn.substring(3);
    return "ims.mnc" + (mnc.length() == 2 ? "0" + mnc : mnc)
        + ".mcc" + homePlmn.substring(0, 3) + ".3gppnetwork.org";
  }

  private static void requireTun(String iface) throws Failure {
    try { TunEgress.requireDevice(iface); }
    catch (Exception error) { throw new Failure("TUN_REQUIRED_OR_UNAVAILABLE"); }
  }

  private static void writeValidatedConfig(Context context, Identity expected, String outputPath,
      JSONObject config) throws Exception {
    if (!validOutputPath(outputPath)) throw new Failure("INVALID_OUTPUT_PATH");
    File dir = new File(CONFIG_DIR), destination = new File(outputPath);
    if (!dir.getCanonicalPath().equals(CONFIG_DIR)
        || !destination.getCanonicalPath().equals(outputPath)) throw new Failure("INVALID_OUTPUT_PATH");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new Failure("CONFIG_WRITE_FAILED");
    Os.chmod(CONFIG_DIR, 0700);
    if (Files.isSymbolicLink(destination.toPath()) || destination.isDirectory())
      throw new Failure("INVALID_OUTPUT_PATH");
    if (!new File(ENGINE).canExecute()) throw new Failure("ENGINE_VALIDATOR_UNAVAILABLE");
    File candidate = File.createTempFile(".candidate-", ".json", dir);
    byte[] bytes = config.toString().getBytes(StandardCharsets.UTF_8);
    try {
      Os.chmod(candidate.getAbsolutePath(), 0600);
      try (FileOutputStream stream = new FileOutputStream(candidate)) {
        stream.write(bytes);
        stream.getFD().sync();
      }
      // This is a runtime configuration. The stock create-template validator intentionally
      // accepts only wifi, while runtime ParseJSON/Validate supports explicit interfaces.
      // Strict verify also compares the candidate identity against the live SIM.
      String result = run(new ProcessBuilder(ENGINE, "config-verify", "--strict", candidate.getAbsolutePath()),
          new byte[0], 10000, "ENGINE_VALIDATION_FAILED");
      JSONObject validation;
      try { validation = new JSONObject(result); }
      catch (Exception e) { throw new Failure("ENGINE_VALIDATOR_OUTPUT_INVALID"); }
      if (!validation.optBoolean("ok", false)) throw new Failure("CANDIDATE_INVALID");
      // Schema validation may take time. Recheck the live identity and call state immediately
      // before committing this exact validated candidate; the existing config survives failures.
      Identity current = requireCurrentIdentity(context, expected.subId, expected.slotId);
      if (!expected.sameIdentity(current) || expected.carrierId != current.carrierId)
        throw new Failure("SUBSCRIPTION_CHANGED");
      if (!current.callStateKnown || current.callState != TelephonyManager.CALL_STATE_IDLE)
        throw new Failure("CALL_ACTIVE_OR_UNKNOWN");
      requireTun(TunEgress.interfaceFromSelector(config.optString("transport", "")));
      requireRadioOff(current);
      Os.rename(candidate.getAbsolutePath(), destination.getAbsolutePath());
    } finally {
      Arrays.fill(bytes, (byte) 0);
      Files.deleteIfExists(candidate.toPath());
    }
  }

  static boolean validPreparedIdentity(String iccid, String homePlmn, int slot, String resultIccid,
      String resultMcc, String resultMnc, int resultSlot, String imsi, String imei) {
    return !normalizeIccid(iccid).isEmpty() && PolicyEngine.validHomePlmn(homePlmn)
        && iccid.equals(normalizeIccid(resultIccid)) && slot == resultSlot
        && homePlmn.substring(0, 3).equals(resultMcc) && homePlmn.substring(3).equals(resultMnc)
        && asciiDigits(imsi, 15, 15) && imsi.startsWith(homePlmn) && asciiDigits(imei, 15, 15);
  }

  /** Fixed subprocess arguments; stderr is drained without logging or mixing into private JSON. */
  private static String run(
      ProcessBuilder builder, byte[] input, long timeoutMs, String failureCode) throws Exception {
    java.lang.Process child = builder.start();
    Collector stdout = new Collector(child.getInputStream(), true);
    Collector stderr = new Collector(child.getErrorStream(), false);
    Thread outThread = new Thread(stdout, "identity-stdout");
    Thread errThread = new Thread(stderr, "identity-stderr");
    outThread.setDaemon(true);
    errThread.setDaemon(true);
    outThread.start();
    errThread.start();
    try {
      try {
        child.getOutputStream().write(input);
      } finally {
        child.getOutputStream().close();
        Arrays.fill(input, (byte) 0);
      }
      if (!child.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        child.destroyForcibly();
        throw new Failure(failureCode);
      }
      outThread.join(1000);
      errThread.join(1000);
      if (outThread.isAlive() || stdout.failed || stdout.overflow)
        throw new Failure(failureCode);
      String result = stdout.bytes.toString(StandardCharsets.UTF_8.name()).trim();
      if (child.exitValue() != 0) {
        String code = failureCode;
        try {
          JSONObject error = new JSONObject(result);
          String candidateCode = error.optString("code", "");
          if (!error.optBoolean("ok", true) && candidateCode.matches("[A-Z0-9_]{3,80}")) code = candidateCode;
        } catch (Exception ignored) { }
        throw new Failure(code);
      }
      return result;
    } finally {
      child.destroy();
      try {
        child.getInputStream().close();
        child.getErrorStream().close();
      } catch (Exception ignored) {
      }
    }
  }

  private static final class Collector implements Runnable {
    final InputStream stream;
    final boolean retain;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    volatile boolean failed, overflow;
    Collector(InputStream stream, boolean retain) {
      this.stream = stream;
      this.retain = retain;
    }
    @Override
    public void run() {
      byte[] buffer = new byte[4096];
      try (InputStream in = stream) {
        int n;
        while ((n = in.read(buffer)) != -1)
          if (retain) {
            if (bytes.size() + n <= OUTPUT_LIMIT)
              bytes.write(buffer, 0, n);
            else
              overflow = true;
          }
      } catch (Exception ignored) {
        failed = true;
      } finally {
        Arrays.fill(buffer, (byte) 0);
      }
    }
  }

  static boolean validOutputPath(String path) {
    if (path == null || !path.startsWith(CONFIG_DIR + "/") || !path.endsWith(".json"))
      return false;
    return asciiDigits(path.substring(CONFIG_DIR.length() + 1, path.length() - 5), 1, 32);
  }

  private static void writePrivateFile(File destination, byte[] bytes, String directory)
      throws Exception {
    File dir = new File(directory);
    if (!dir.getCanonicalPath().equals(directory)
        || !destination.getCanonicalPath().equals(destination.getAbsolutePath()))
      throw new Failure("INVALID_OUTPUT_PATH");
    if (!dir.isDirectory() && !dir.mkdirs())
      throw new Failure("CONFIG_WRITE_FAILED");
    Os.chmod(directory, 0700);
    if (Files.isSymbolicLink(destination.toPath()) || destination.isDirectory())
      throw new Failure("INVALID_OUTPUT_PATH");
    File temporary = File.createTempFile(".identity-", ".tmp", dir);
    try {
      Os.chmod(temporary.getAbsolutePath(), 0600);
      try (FileOutputStream stream = new FileOutputStream(temporary)) {
        stream.write(bytes);
        stream.getFD().sync();
      }
      Os.rename(temporary.getAbsolutePath(), destination.getAbsolutePath());
      Os.chmod(destination.getAbsolutePath(), 0600);
    } finally {
      Arrays.fill(bytes, (byte) 0);
      Files.deleteIfExists(temporary.toPath());
    }
  }

  static String normalizeIccid(String value) {
    if (value == null)
      return "";
    int end = value.length();
    while (end > 0 && (value.charAt(end - 1) == 'F' || value.charAt(end - 1) == 'f')) end--;
    String normalized = value.substring(0, end);
    return asciiDigits(normalized, 18, 22) ? normalized : "";
  }
  static boolean asciiDigits(String value, int min, int max) {
    if (value == null || value.length() < min || value.length() > max)
      return false;
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) < '0' || value.charAt(i) > '9')
        return false;
    return true;
  }
  private static int nonNegativeInt(String value) throws Failure {
    if (!asciiDigits(value, 1, 10))
      throw new Failure("INVALID_ARGUMENT");
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new Failure("INVALID_ARGUMENT");
    }
  }
  private static String emptyIfNull(String value) {
    return value == null ? "" : value;
  }
  private static String sha256(String value) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder();
    for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }
  private static final class Failure extends Exception {
    final String code;
    Failure(String code) {
      super(code);
      this.code = code;
    }
  }
}
