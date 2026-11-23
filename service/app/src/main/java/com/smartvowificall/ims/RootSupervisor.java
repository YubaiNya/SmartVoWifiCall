package com.smartvowificall.ims;
import android.content.Context;
import android.media.AudioManager;
import com.smartvowificall.ims.engine.EngineSession;
import com.smartvowificall.ims.policy.PolicyEngine;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
final class RootSupervisor {
  static final String MODULE = "/data/adb/modules/smartvowificall";
  final Context context;
  final java.util.concurrent.atomic.AtomicLong commandCount = new java.util.concurrent.atomic.AtomicLong();
  RootSupervisor(Context context) {
    this.context = context;
  }
  static String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }
  String run(String script) throws Exception {
    return run(script, null);
  }
  String run(String script, byte[] stdin) throws Exception {
    commandCount.incrementAndGet();
    Process p = new ProcessBuilder("su", "-c", script).start();
    Collector out = new Collector(p.getInputStream(), true),
              err = new Collector(p.getErrorStream(), false);
    Thread reader = new Thread(out, "root-stdout"), drain = new Thread(err, "root-stderr");
    reader.setDaemon(true);
    drain.setDaemon(true);
    reader.start();
    drain.start();
    try {
      if (stdin != null && stdin.length > 0) p.getOutputStream().write(stdin);
      p.getOutputStream().close();
      if (!p.waitFor(30, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("ROOT_TIMEOUT");
      }
      reader.join(1500);
      drain.join(1500);
      if (reader.isAlive() || out.failed || out.overflow)
        throw new IOException("ROOT_OUTPUT_INVALID");
      String result = out.bytes.toString("UTF-8").trim();
      if (p.exitValue() != 0) {
        String code = "ROOT_COMMAND_FAILED";
        try {
          JSONObject j = new JSONObject(result);
          String c = j.optString("code", "");
          if (!j.optBoolean("ok", true) && c.matches("[A-Z0-9_]{3,80}"))
            code = c;
        } catch (Exception ignored) {
        }
        throw new IOException(code);
      }
      return result;
    } finally {
      p.destroy();
      try {
        p.getInputStream().close();
        p.getErrorStream().close();
      } catch (IOException ignored) {
      }
    }
  }
  static final class Collector implements Runnable {
    final InputStream input;
    final boolean retain;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    volatile boolean failed, overflow;
    Collector(InputStream in, boolean keep) {
      input = in;
      retain = keep;
    }
    public void run() {
      try (InputStream in = input) {
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) != -1)
          if (retain) {
            if (bytes.size() + n <= 2 * 1024 * 1024)
              bytes.write(b, 0, n);
            else
              overflow = true;
          }
      } catch (IOException ignored) {
        failed = true;
      }
    }
  }
  String helper(String args) throws Exception {
    return run("CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
        + " /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootIdentityMain " + args);
  }
  JSONObject clash(String mode, String stdinText) throws Exception {
    byte[] input = stdinText == null ? null : stdinText.getBytes(StandardCharsets.UTF_8);
    String result = run("CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
        + " /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootClashMain " + mode, input);
    return new JSONObject(result);
  }
  JSONObject clashStatus() throws Exception { return clash("status", null); }
  JSONObject clashStart() throws Exception { return clash("start", null); }
  JSONObject clashRestart() throws Exception { return clash("restart", null); }
  JSONObject clashStop() throws Exception { return clash("stop", null); }
  JSONObject clashRebuild() throws Exception { return clash("rebuild", null); }
  JSONObject clashFetchSubscription(String url) throws Exception { return clash("fetch-subscription", url); }
  JSONObject clashSaveProfile(String yaml) throws Exception { return clash("save-profile", yaml); }
  JSONObject clashSelectNode(String node) throws Exception { return clash("select-node", node); }
  JSONObject clashTestDelay() throws Exception { return clash("test-delay", null); }
  JSONObject clashSetController(boolean externalLan, int port) throws Exception {
    return clash("set-controller",
        new JSONObject().put("externalLan", externalLan).put("port", port).toString());
  }
  JSONObject clashRotateSecret() throws Exception { return clash("rotate-secret", null); }
  JSONObject clashConnections() throws Exception { return clash("connections", null); }
  JSONObject clashCloseConnection(String id) throws Exception { return clash("close-connection", id); }
  JSONObject clashCloseAllConnections() throws Exception { return clash("close-all-connections", null); }
  static final class Sim {
    final PolicyEngine.Sim policy;
    final String iccid;
    final int phoneId, simState, rawSimState, carrierId;
    final String identityCode, countryIso, carrierName, simOperatorName,
        networkCountryIso, networkOperatorName, networkPlmn;
    final boolean embedded, opportunistic;
    final List<String> identityIssues;
    Sim(JSONObject j) {
      iccid = j.optString("iccid");
      policy = new PolicyEngine.Sim(j.optString("simKey"), j.optInt("subId", -1),
          j.optInt("slotId", -1), j.optBoolean("ready"), j.optString("homePlmn"));
      phoneId = j.optInt("phoneId", -1);
      simState = j.optInt("simState", 0);
      rawSimState = j.optInt("rawSimState", 0);
      carrierId = j.optInt("carrierId", -1);
      identityCode = j.optString("identityCode", "");
      countryIso = j.optString("countryIso", "");
      carrierName = j.optString("carrierName", j.optString("label", ""));
      simOperatorName = j.optString("simOperatorName", "");
      networkCountryIso = j.optString("networkCountryIso", "");
      networkOperatorName = j.optString("networkOperatorName", "");
      networkPlmn = j.optString("networkPlmn", "");
      embedded = j.optBoolean("isEmbedded", false);
      opportunistic = j.optBoolean("isOpportunistic", false);
      List<String> issues = new ArrayList<>();
      JSONArray arr = j.optJSONArray("identityIssues");
      if (arr != null) {
        for (int i = 0; i < arr.length(); i++) {
          String s = arr.optString(i, "");
          if (!s.isEmpty()) issues.add(s);
        }
      }
      identityIssues = Collections.unmodifiableList(issues);
    }
    EngineSession.Identity identity() {
      return new EngineSession.Identity(iccid, policy.slotId, policy.subId);
    }
  }
  static final class Snapshot {
    final List<Sim> sims = new ArrayList<>();
    final Set<Integer> heldRadioSlots = new HashSet<>();
    boolean callsActive = true;
  }
  Snapshot snapshot() throws Exception {
    JSONObject j = new JSONObject(helper("snapshot"));
    if (!j.optBoolean("ok"))
      throw new IOException(j.optString("code", "IDENTITY_UNAVAILABLE"));
    Snapshot result = new Snapshot();
    result.callsActive = j.optBoolean("callActive", j.optBoolean("anyCallActive", true));
    AudioManager audio = context.getSystemService(AudioManager.class);
    result.callsActive |= audio == null || audio.getMode() != AudioManager.MODE_NORMAL;
    JSONArray a = j.getJSONArray("sims");
    for (int i = 0; i < a.length(); i++) result.sims.add(new Sim(a.getJSONObject(i)));
    JSONArray held = j.optJSONArray("heldRadioSlots");
    if (held != null) {
      for (int i = 0; i < held.length(); i++) {
        int slot = held.optInt(i, -1);
        if (slot >= 0 && slot < 8) result.heldRadioSlots.add(slot);
      }
    }
    return result;
  }
  Map<String, Set<String>> vpnOwners() throws Exception {
    JSONObject result = new JSONObject(run("CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
        + " /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootNetworkMain publish"));
    if (!result.optBoolean("ok") || !result.optBoolean("registryPublished"))
      throw new IOException(result.optString("code", "NETWORK_REGISTRY_UNAVAILABLE"));
    Map<String, Set<String>> owners = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>(), ambiguous = new HashSet<>();
    JSONArray nets = result.getJSONArray("networks");
    for (int n=0;n<nets.length();n++) {
      JSONObject net=nets.getJSONObject(n); String name=net.optString("interface");
      if (!net.optBoolean("vpn") || !net.optBoolean("ownerVerified")) continue;
      if (!seen.add(name)) { ambiguous.add(name); continue; }
      JSONArray packages=net.optJSONArray("ownerPackages");
      if(packages!=null && packages.length()==1 && packages.getString(0).equals("com.follow.clash"))
        owners.put(name,Collections.singleton("com.follow.clash"));
    }
    for(String name:ambiguous)owners.remove(name);
    return owners;
  }
  // This getter is the effective carrier configuration, not manual-override provenance.
  String currentOverride(int slot) throws Exception {
    if (slot < 0 || slot > 7)
      throw new IOException("INVALID_SLOT");
    String value = run("cmd phone ims get-ims-service -s " + slot + " -c -f 1").trim();
    if (!value.isEmpty() && !value.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
      throw new IOException("IMS_CONFIG_UNKNOWN");
    return value;
  }
  void bind(Sim sim) throws Exception {
    JSONObject response = new JSONObject(helper("ims-bind " + sim.policy.subId + " "
        + sim.policy.slotId + " " + quote(sim.policy.simKey) + " " + quote(sim.policy.homePlmn)));
    if (!response.optBoolean("ok"))
      throw new IOException("IMS_BIND_FAILED");
  }
  void setVoiceServiceOverride(Sim sim, boolean hasService) throws Exception {
    JSONObject response = new JSONObject(helper("voice-service-override " + sim.policy.subId + " "
        + sim.policy.slotId + " " + quote(sim.policy.simKey) + " " + quote(sim.policy.homePlmn)
        + " " + (hasService ? "true" : "false")));
    if (!response.optBoolean("ok"))
      throw new IOException(response.optString("code", "VOICE_OVERRIDE_FAILED"));
  }
  boolean holdRadio(Sim sim) throws Exception {
    JSONObject result = new JSONObject(helper("radio-hold " + sim.policy.subId + " " + sim.policy.slotId
        + " " + quote(sim.policy.simKey) + " " + quote(sim.policy.homePlmn)));
    return result.optBoolean("ok") && result.optBoolean("radioOff");
  }
  void verifyRadioOff(Sim sim) throws Exception {
    JSONObject result = new JSONObject(helper("radio-check " + sim.policy.subId + " " + sim.policy.slotId
        + " " + quote(sim.policy.simKey) + " " + quote(sim.policy.homePlmn)));
    if (!result.optBoolean("ok") || !result.optBoolean("radioOff"))
      throw new IOException("CELLULAR_RADIO_NOT_OFF");
  }
  void releaseRadios() throws Exception { helper("radio-release-all"); }
  void releaseRadio(int slot) throws Exception {
    if (slot < 0 || slot > 7) throw new IOException("INVALID_SLOT");
    JSONObject response = new JSONObject(helper("radio-release " + slot));
    if (!response.optBoolean("ok")) throw new IOException("RADIO_RELEASE_FAILED");
  }
  void restoreAll() throws Exception { helper("ims-restore-all"); }
  void restore(int slot, String previous) throws Exception {
    if (previous != null && !previous.isEmpty())
      throw new IOException("IMS_ORIGIN_AMBIGUOUS");
    JSONObject response = new JSONObject(helper("ims-restore " + slot));
    if (!response.optBoolean("ok"))
      throw new IOException("IMS_RESTORE_FAILED");
  }
  void start(Sim sim, String iface) throws Exception {
    if (!com.smartvowificall.ims.network.InterfaceSnapshot.validName(iface))
      throw new IOException("INVALID_INTERFACE");
    run("[ ! -e " + MODULE + "/disable ] && [ ! -e " + MODULE
        + "/remove ] && [ ! -e /data/adb/smartvowificall_ims/rollback-pending ]");
    String config = "/data/adb/smartvowificall_ims/config/" + sim.iccid + ".json";
    JSONObject response = new JSONObject(helper("prepare " + sim.policy.subId + " "
        + sim.policy.slotId + " " + quote(iface) + " " + quote(config)));
    if (!response.optBoolean("ok"))
      throw new IOException(response.optString("code", "CONFIG_FAILED"));
    run("sh " + MODULE + "/control.sh start " + quote(sim.iccid) + " "
        + android.os.Process.myUid());
  }
  void stop(String iccid) throws Exception {
    if (iccid.matches("[0-9]{10,32}"))
      run("sh " + MODULE + "/control.sh stop " + quote(iccid));
  }
  boolean engineRunning(String iccid) throws Exception {
    if (iccid == null || !iccid.matches("[0-9]{10,32}"))
      throw new IOException("INVALID_ID");
    JSONObject state = new JSONObject(run("sh " + MODULE + "/control.sh status " + quote(iccid)));
    if (!state.optBoolean("ok") || !state.has("running"))
      throw new IOException("ENGINE_OWNERSHIP_UNKNOWN");
    return state.getBoolean("running");
  }
  String collectRootLogs() throws Exception {
    String script = "echo '=== PROCESSES ===';"
        + "ps -A 2>/dev/null | grep -iE 'smartvowifi|vowifi-engine|mihomo' || true;"
        + "echo '=== IMS OVERRIDES ===';"
        + "echo \"slot0=$(cmd phone ims get-ims-service -s 0 -c -f 1 2>/dev/null || true)\";"
        + "echo \"slot1=$(cmd phone ims get-ims-service -s 1 -c -f 1 2>/dev/null || true)\";"
        + "echo '=== RADIO & IMS JOURNALS ===';"
        + "for f in /data/adb/smartvowificall_ims/radio/*.json /data/adb/smartvowificall_ims/ims/*.json; do "
        + "[ -f \"$f\" ] && { echo \"--- $f ---\"; cat \"$f\"; echo; }; done;"
        + "echo '=== CLASH / MIHOMO LOGS (TAIL 32KB) ===';"
        + "for f in /data/adb/smartvowificall_ims/clash/*.log; do "
        + "[ -f \"$f\" ] && { echo \"--- $f ---\"; tail -c 32768 \"$f\" 2>/dev/null || true; echo; }; done;"
        + "echo '=== LAUNCHER LOGS (TAIL 48KB) ===';"
        + "for f in /data/adb/smartvowificall_ims/run/*.launcher.log; do "
        + "[ -f \"$f\" ] && { echo \"--- $f ---\"; tail -c 49152 \"$f\" 2>/dev/null || true; echo; }; done;"
        + "echo '=== ENGINE LOGS (TAIL 64KB) ===';"
        + "for f in /data/adb/smartvowificall_ims/logs/*/*.log /data/adb/smartvowificall_ims/logs/*/diagnostic.jsonl; do "
        + "[ -f \"$f\" ] && { echo \"--- $f ---\"; tail -c 65536 \"$f\" 2>/dev/null || true; echo; }; done;"
        + "echo '=== LOGCAT (FILTERED TAIL 400) ===';"
        + "logcat -d -v time 2>/dev/null | grep -iE 'SmartVoWifi|VoWiFiAudio|RootIdentity|RootClash|ImsResolver|ImsServiceController' | tail -n 400 || true";
    return run(script);
  }
  String collectClashHttpTrace() throws Exception {
    return run("[ -f /data/adb/smartvowificall_ims/clash/http-trace.jsonl ] && tail -n 50 /data/adb/smartvowificall_ims/clash/http-trace.jsonl || true");
  }
}
