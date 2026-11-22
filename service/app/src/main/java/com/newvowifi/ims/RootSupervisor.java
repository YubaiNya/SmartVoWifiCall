package com.newvowifi.ims;
import android.content.Context;
import android.media.AudioManager;
import com.newvowifi.ims.engine.EngineSession;
import com.newvowifi.ims.policy.PolicyEngine;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
final class RootSupervisor {
  static final String MODULE = "/data/adb/modules/newvowifi";
  final Context context;
  RootSupervisor(Context context) {
    this.context = context;
  }
  static String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }
  String run(String script) throws Exception {
    Process p = new ProcessBuilder("su", "-c", script).start();
    Collector out = new Collector(p.getInputStream(), true),
              err = new Collector(p.getErrorStream(), false);
    Thread reader = new Thread(out, "root-stdout"), drain = new Thread(err, "root-stderr");
    reader.setDaemon(true);
    drain.setDaemon(true);
    reader.start();
    drain.start();
    try {
      p.getOutputStream().close();
      if (!p.waitFor(25, TimeUnit.SECONDS)) {
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
        + " /system/bin/app_process /system/bin com.newvowifi.ims.root.RootIdentityMain " + args);
  }
  static final class Sim {
    final PolicyEngine.Sim policy;
    final String iccid;
    Sim(JSONObject j) {
      iccid = j.optString("iccid");
      policy = new PolicyEngine.Sim(j.optString("simKey"), j.optInt("subId", -1),
          j.optInt("slotId", -1), j.optBoolean("ready"), j.optString("homePlmn"));
    }
    EngineSession.Identity identity() {
      return new EngineSession.Identity(iccid, policy.slotId, policy.subId);
    }
  }
  static final class Snapshot {
    final List<Sim> sims = new ArrayList<>();
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
    return result;
  }
  Map<String, Set<String>> vpnOwners() throws Exception {
    JSONObject result = new JSONObject(run("CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
        + " /system/bin/app_process /system/bin com.newvowifi.ims.root.RootNetworkMain publish"));
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
  void restoreAll() throws Exception { helper("ims-restore-all"); }
  void restore(int slot, String previous) throws Exception {
    if (previous != null && !previous.isEmpty())
      throw new IOException("IMS_ORIGIN_AMBIGUOUS");
    JSONObject response = new JSONObject(helper("ims-restore " + slot));
    if (!response.optBoolean("ok"))
      throw new IOException("IMS_RESTORE_FAILED");
  }
  void start(Sim sim, String iface) throws Exception {
    if (!com.newvowifi.ims.network.InterfaceSnapshot.validName(iface))
      throw new IOException("INVALID_INTERFACE");
    run("[ ! -e " + MODULE + "/disable ] && [ ! -e " + MODULE
        + "/remove ] && [ ! -e /data/adb/newvowifi_ims/rollback-pending ]");
    String config = "/data/adb/newvowifi_ims/config/" + sim.iccid + ".json";
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
}
