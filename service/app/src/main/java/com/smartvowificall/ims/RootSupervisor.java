package com.smartvowificall.ims;
import android.content.Context;
import android.media.AudioManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import com.smartvowificall.ims.engine.EngineSession;
import com.smartvowificall.ims.policy.PolicyEngine;
import com.smartvowificall.ims.root.RootDaemonProtocol;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
final class RootSupervisor {
  static final String MODULE = "/data/adb/modules/smartvowificall";
  private static final AtomicLong LAST_DAEMON_BOOTSTRAP_MS = new AtomicLong();
  final Context context;
  final AtomicLong commandCount = new AtomicLong();
  final AtomicLong daemonCallCount = new AtomicLong();
  final Map<Integer, String> radioLeaseMethods = new ConcurrentHashMap<>();
  RootSupervisor(Context context) {
    this.context = context;
  }
  static String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }
  String run(String script) throws Exception {
    return run(script, null, 30);
  }
  String run(String script, byte[] stdin) throws Exception {
    return run(script, stdin, 30);
  }
  String run(String script, byte[] stdin, long timeoutSeconds) throws Exception {
    commandCount.incrementAndGet();
    Process p = new ProcessBuilder("su", "-c", script).start();
    Collector out = new Collector(p.getInputStream(), true),
              err = new Collector(p.getErrorStream(), false);
    Thread reader = new Thread(out, "root-stdout"), drain = new Thread(err, "root-stderr");
    reader.setDaemon(true);
    drain.setDaemon(true);
    reader.start();
    drain.start();
    // Write stdin asynchronously and ignore EPIPE when the child exits early with a JSON error.
    Thread writer = null;
    if (stdin != null && stdin.length > 0) {
      writer = new Thread(() -> {
        try (OutputStream os = p.getOutputStream()) {
          os.write(stdin);
          os.flush();
        } catch (IOException ignored) {
        }
      }, "root-stdin");
      writer.setDaemon(true);
      writer.start();
    } else {
      try {
        p.getOutputStream().close();
      } catch (IOException ignored) {
      }
    }
    try {
      if (!p.waitFor(Math.max(5L, timeoutSeconds), TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("ROOT_TIMEOUT");
      }
      if (writer != null) writer.join(1500);
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
        p.getOutputStream().close();
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
  private JSONObject callDaemon(String target, List<String> args, byte[] stdinBytes) throws IOException {
    LocalSocket socket = new LocalSocket();
    try {
      socket.connect(new LocalSocketAddress(
          RootDaemonProtocol.DEFAULT_SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
      socket.setSoTimeout(30_000);
      String req = RootDaemonProtocol.encodeRequest(target, args, stdinBytes);
      OutputStream out = socket.getOutputStream();
      out.write((req + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      String line = reader.readLine();
      if (line == null || line.trim().isEmpty()) throw new IOException("DAEMON_EOF");
      JSONObject envelope = new JSONObject(line);
      if (!envelope.optBoolean("ok", false)) {
        throw new IOException(envelope.optString("code", "DAEMON_ERROR"));
      }
      JSONObject result = envelope.optJSONObject("result");
      if (result == null) throw new IOException("DAEMON_EMPTY_RESULT");
      daemonCallCount.incrementAndGet();
      return result;
    } catch (JSONException e) {
      throw new IOException("DAEMON_JSON_INVALID", e);
    } finally {
      try { socket.close(); } catch (IOException ignored) {}
    }
  }

  private void triggerDaemonBootstrapAsync() {
    long now = System.currentTimeMillis();
    long prev = LAST_DAEMON_BOOTSTRAP_MS.get();
    if (now - prev < 30_000L || !LAST_DAEMON_BOOTSTRAP_MS.compareAndSet(prev, now)) return;
    Thread t = new Thread(() -> {
      try {
        int myUid = android.os.Process.myUid();
        String apk = context.getApplicationInfo().sourceDir;
        String sock = quote(RootDaemonProtocol.DEFAULT_SOCKET_PATH);
        String cmd = "BASE=/data/adb/smartvowificall_ims; SOCK=" + sock + "; UID=" + myUid + "; "
            + "mkdir -p \"$BASE/run\" /data/local/tmp/smartvowificall; "
            + "chmod 0711 /data/local/tmp/smartvowificall; "
            + "alive=0; for c in /proc/[0-9]*/cmdline; do [ -r \"$c\" ] || continue; "
            + "case \"$(tr '\\000' ' ' < \"$c\" 2>/dev/null)\" in "
            + "*com.smartvowificall.ims.root.RootDaemonMain*) alive=1; break;; esac; done; "
            + "if [ \"$alive\" = 1 ] && [ -S \"$SOCK\" ]; then "
            + "chown \"0:$UID\" \"$SOCK\" 2>/dev/null || :; chmod 0660 \"$SOCK\" 2>/dev/null || :; "
            + "elif [ \"$alive\" = 0 ]; then "
            + "setsid env CLASSPATH=" + quote(apk)
            + " /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootDaemonMain "
            + "$SOCK $UID >>\"$BASE/run/root-daemon.log\" 2>&1 </dev/null & fi";
        run(cmd);
      } catch (Throwable ignored) {}
    }, "root-daemon-bootstrap");
    t.setDaemon(true);
    t.start();
  }

  private JSONObject identityCall(String... args) throws Exception {
    try {
      JSONObject res = callDaemon("identity", Arrays.asList(args), null);
      if (res.optBoolean("ok", false)
          || (!"IDENTITY_UNAVAILABLE".equals(res.optString("code"))
              && !"SERVICE_UNAVAILABLE".equals(res.optString("code")))) {
        return res;
      }
    } catch (IOException ignored) {
      triggerDaemonBootstrapAsync();
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < args.length; i++) {
      if (i > 0) sb.append(' ');
      sb.append(quote(args[i]));
    }
    return new JSONObject(helper(sb.toString()));
  }

  String helper(String args) throws Exception {
    return run("CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
        + " /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootIdentityMain " + args);
  }
  JSONObject clash(int instanceId, String stdinText, String... modeArgs) throws Exception {
    byte[] input = stdinText == null ? null : stdinText.getBytes(StandardCharsets.UTF_8);
    List<String> fullArgs = new ArrayList<>();
    fullArgs.add("--instance=" + instanceId);
    for (String a : modeArgs) fullArgs.add(a);
    try {
      JSONObject res = callDaemon("clash", fullArgs, input);
      if (!res.optBoolean("ok", true)) {
        String code = res.optString("code", "CLASH_ERROR");
        if (code.matches("[A-Z0-9_]{3,80}")) throw new IOException(code);
      }
      return res;
    } catch (IOException e) {
      if (e.getMessage() != null && !e.getMessage().startsWith("DAEMON_")
          && !e.getMessage().contains("No such file")
          && !e.getMessage().contains("Connection refused")
          && !e.getMessage().contains("Permission denied")
          && e.getMessage().matches("[A-Z0-9_]{3,80}")) {
        throw e;
      }
      triggerDaemonBootstrapAsync();
    }
    StringBuilder sb = new StringBuilder();
    for (String a : fullArgs) sb.append(' ').append(quote(a));
    String result = run("CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
        + " /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootClashMain" + sb, input);
    return new JSONObject(result);
  }
  JSONObject clashStatus(int id) throws Exception { return clash(id, null, "status"); }
  JSONObject clashStart(int id) throws Exception { return clash(id, null, "start"); }
  JSONObject clashRestart(int id) throws Exception { return clash(id, null, "restart"); }
  JSONObject clashStop(int id) throws Exception { return clash(id, null, "stop"); }
  JSONObject clashStopAll() throws Exception { return clash(0, null, "stop-all"); }
  JSONObject clashRebuild(int id) throws Exception { return clash(id, null, "rebuild"); }
  JSONObject clashCopyFrom(int dstId, int srcId) throws Exception {
    return clash(dstId, null, "copy-from", String.valueOf(srcId));
  }
  JSONObject clashFetchSubscription(int id, String url) throws Exception {
    return clash(id, url, "fetch-subscription");
  }
  JSONObject clashSaveProfile(int id, String yaml) throws Exception {
    return clash(id, yaml, "save-profile");
  }
  JSONObject clashSelectNode(int id, String node) throws Exception {
    return clash(id, node, "select-node");
  }
  JSONObject clashTestDelay(int id) throws Exception { return clash(id, null, "test-delay"); }
  JSONObject clashSetController(int id, boolean externalLan, int port) throws Exception {
    return clash(id, new JSONObject().put("externalLan", externalLan).put("port", port).toString(), "set-controller");
  }
  JSONObject clashRotateSecret(int id) throws Exception { return clash(id, null, "rotate-secret"); }
  JSONObject clashConnections(int id) throws Exception { return clash(id, null, "connections"); }
  JSONObject clashCloseConnection(int id, String connId) throws Exception {
    return clash(id, connId, "close-connection");
  }
  JSONObject clashCloseAllConnections(int id) throws Exception {
    return clash(id, null, "close-all-connections");
  }
  // Legacy instance-0 overloads
  JSONObject clashStatus() throws Exception { return clashStatus(0); }
  JSONObject clashStop() throws Exception { return clashStop(0); }
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
    JSONObject j = identityCall("snapshot");
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
    JSONObject result;
    try {
      result = callDaemon("network", Collections.singletonList("publish"), null);
    } catch (IOException ignored) {
      triggerDaemonBootstrapAsync();
      result = new JSONObject(run("CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
          + " /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootNetworkMain publish"));
    }
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
    JSONObject response = identityCall("ims-bind", String.valueOf(sim.policy.subId),
        String.valueOf(sim.policy.slotId), sim.policy.simKey, sim.policy.homePlmn);
    if (!response.optBoolean("ok"))
      throw new IOException("IMS_BIND_FAILED");
  }
  void setVoiceServiceOverride(Sim sim, boolean hasService) throws Exception {
    JSONObject response = identityCall("voice-service-override", String.valueOf(sim.policy.subId),
        String.valueOf(sim.policy.slotId), sim.policy.simKey, sim.policy.homePlmn,
        hasService ? "true" : "false");
    if (!response.optBoolean("ok"))
      throw new IOException(response.optString("code", "VOICE_OVERRIDE_FAILED"));
  }
  boolean holdRadio(Sim sim) throws Exception {
    JSONObject result = identityCall("radio-hold", String.valueOf(sim.policy.subId),
        String.valueOf(sim.policy.slotId), sim.policy.simKey, sim.policy.homePlmn);
    String method = result.optString("radioLeaseMethod", "UNKNOWN");
    radioLeaseMethods.put(sim.policy.slotId,
        "radio_sub".equals(method) || "modem".equals(method) ? method : "UNKNOWN");
    return result.optBoolean("ok") && result.optBoolean("radioOff");
  }
  void verifyRadioOff(Sim sim) throws Exception {
    JSONObject result = identityCall("radio-check", String.valueOf(sim.policy.subId),
        String.valueOf(sim.policy.slotId), sim.policy.simKey, sim.policy.homePlmn);
    if (!result.optBoolean("ok") || !result.optBoolean("radioOff"))
      throw new IOException("CELLULAR_RADIO_NOT_OFF");
  }
  void releaseRadios() throws Exception {
    JSONObject response = identityCall("radio-release-all");
    if (!response.optBoolean("ok")) throw new IOException(response.optString("code", "RADIO_RELEASE_FAILED"));
  }
  void releaseRadio(int slot) throws Exception {
    if (slot < 0 || slot > 7) throw new IOException("INVALID_SLOT");
    JSONObject response = identityCall("radio-release", String.valueOf(slot));
    if (!response.optBoolean("ok")) throw new IOException(response.optString("code", "RADIO_RELEASE_FAILED"));
  }
  void restoreAll() throws Exception {
    JSONObject response = identityCall("ims-restore-all");
    if (!response.optBoolean("ok")) throw new IOException(response.optString("code", "IMS_RESTORE_FAILED"));
  }
  void restore(int slot, String previous) throws Exception {
    if (previous != null && !previous.isEmpty())
      throw new IOException("IMS_ORIGIN_AMBIGUOUS");
    JSONObject response = identityCall("ims-restore", String.valueOf(slot));
    if (!response.optBoolean("ok"))
      throw new IOException("IMS_RESTORE_FAILED");
  }
  void start(Sim sim, String iface) throws Exception {
    if (!com.smartvowificall.ims.network.InterfaceSnapshot.validName(iface))
      throw new IOException("INVALID_INTERFACE");
    run("[ ! -e " + MODULE + "/disable ] && [ ! -e " + MODULE
        + "/remove ] && [ ! -e /data/adb/smartvowificall_ims/rollback-pending ]");
    String config = "/data/adb/smartvowificall_ims/config/" + sim.iccid + ".json";
    JSONObject response = identityCall("prepare", String.valueOf(sim.policy.subId),
        String.valueOf(sim.policy.slotId), iface, config);
    if (!response.optBoolean("ok"))
      throw new IOException(response.optString("code", "CONFIG_FAILED"));
    run("sh " + MODULE + "/control.sh start " + quote(sim.iccid) + " "
        + android.os.Process.myUid() + " APP_POLICY " + sim.policy.slotId);
  }
  void stop(String iccid, int slot, String reason) throws Exception {
    if (reason == null || !reason.matches("[A-Z_]{3,64}")) reason = "UNSPECIFIED";
    if (slot < 0 || slot > 7) slot = -1;
    if (iccid.matches("[0-9]{10,32}"))
      run("sh " + MODULE + "/control.sh stop " + quote(iccid) + " " + reason + " " + slot);
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
        + "ps -Af 2>/dev/null | grep -iE 'smartvowifi|vowifi-engine|mihomo|RootDaemon|RootSimAuth|RootNetwork|service\\.sh' | grep -v grep || true;"
        + "echo '=== IMS OVERRIDES ===';"
        + "echo \"slot0=$(cmd phone ims get-ims-service -s 0 -c -f 1 2>/dev/null || true)\";"
        + "echo \"slot1=$(cmd phone ims get-ims-service -s 1 -c -f 1 2>/dev/null || true)\";"
        + "echo '=== RADIO & IMS JOURNALS ===';"
        + "for f in /data/adb/smartvowificall_ims/radio/*.json /data/adb/smartvowificall_ims/ims/*.json; do "
        + "[ -f \"$f\" ] && { echo \"--- $f ---\"; cat \"$f\"; echo; }; done;"
        + "echo '=== DAEMON & SIM-AUTH LOGS (TAIL 16KB) ===';"
        + "for f in /data/adb/smartvowificall_ims/run/root-daemon.log /data/adb/smartvowificall_ims/run/sim-auth.log; do "
        + "[ -f \"$f\" ] && { echo \"--- $f ---\"; tail -c 16384 \"$f\" 2>/dev/null || true; echo; }; done;"
        + "echo '=== CLASH / MIHOMO LOGS (TAIL 32KB) ===';"
        + "for f in /data/adb/smartvowificall_ims/clash/*.log /data/adb/smartvowificall_ims/clash1/*.log; do "
        + "[ -f \"$f\" ] && { echo \"--- $f ---\"; tail -c 32768 \"$f\" 2>/dev/null || true; echo; }; done;"
        + "echo '=== LAUNCHER LOGS (TAIL 48KB) ===';"
        + "for f in /data/adb/smartvowificall_ims/run/*.launcher.log; do "
        + "[ -f \"$f\" ] && { echo \"--- $f ---\"; tail -c 49152 \"$f\" 2>/dev/null || true; echo; }; done;"
        + "echo '=== ENGINE LOGS (TAIL 64KB) ===';"
        + "for f in /data/adb/smartvowificall_ims/logs/*/*.log /data/adb/smartvowificall_ims/logs/*/diagnostic.jsonl; do "
        + "[ -f \"$f\" ] && { echo \"--- $f ---\"; tail -c 65536 \"$f\" 2>/dev/null || true; echo; }; done;"
        + "echo '=== LOGCAT (FILTERED TAIL 400) ===';"
        + "logcat -d -v time 2>/dev/null | grep -iE 'SmartVoWifi|VoWiFiAudio|RootIdentity|RootClash|RootDaemon|RootSimAuth|ImsResolver|ImsServiceController' | tail -n 400 || true;"
        // Keep stop attribution at the tail so noisy logcat does not displace it.
        + "echo '=== LIFECYCLE (BOUNDED, NO SUBSCRIBER DATA) ===';"
        + "for f in /data/adb/smartvowificall_ims/logs/*/control-lifecycle.jsonl.1 /data/adb/smartvowificall_ims/logs/*/control-lifecycle.jsonl; do "
        + "[ -f \"$f\" ] && { tail -c 65536 \"$f\" 2>/dev/null || true; echo; }; done; true";
    return run(script);
  }
  String collectAuthTrace() throws Exception {
    // Read complete lines from both retained generations; never tail individual packets.
    return run("for d in /data/adb/smartvowificall_ims/logs/*; do "
        + "[ -d \"$d\" ] || continue; for f in \"$d/auth.jsonl.1\" \"$d/auth.jsonl\"; do "
        + "if [ -f \"$f\" ]; then cat \"$f\" || exit 1; fi; done; done");
  }
  String collectClashHttpTrace() throws Exception {
    return run("for f in /data/adb/smartvowificall_ims/clash/http-trace.jsonl /data/adb/smartvowificall_ims/clash1/http-trace.jsonl; do [ -f \"$f\" ] && tail -n 25 \"$f\"; done || true");
  }
  com.smartvowificall.ims.policy.CompatReport compatCheck() {
    com.smartvowificall.ims.policy.CompatReport.Input in =
        new com.smartvowificall.ims.policy.CompatReport.Input();
    in.apiLevel = android.os.Build.VERSION.SDK_INT;
    in.privilegedApp = (context.getApplicationInfo().flags
        & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
    try {
      boolean viaDaemon = false;
      JSONObject j;
      try {
        j = callDaemon("identity", Collections.singletonList("compat-check"), null);
        viaDaemon = true;
      } catch (IOException ignored) {
        triggerDaemonBootstrapAsync();
        j = new JSONObject(helper("compat-check"));
      }
      in.rootAvailable = j.optBoolean("ok", false);
      in.daemonActive = viaDaemon;
      in.moduleInstalled = j.optBoolean("moduleInstalled", false);
      in.engineBinaryReady = j.optBoolean("engineBinaryReady", false);
      in.imsOverrideVariant = j.optString("imsOverrideVariant", "");
      in.modemSlotVariant = j.optString("modemSlotVariant", "");
      in.voiceOverrideVariant = j.optString("voiceOverrideVariant", "");
      in.iccAuthAvailable = j.optBoolean("iccAuthAvailable", false);
      in.qrtrAvailable = j.optBoolean("qrtrAvailable", false);
      in.chipsetFamily = j.optString("chipsetFamily", "generic");
    } catch (Throwable ignored) {
      in.rootAvailable = false;
      in.daemonActive = false;
    }
    return com.smartvowificall.ims.policy.CompatReport.evaluate(in);
  }

  com.smartvowificall.ims.telemetry.TelemetryClient.UploadResult uploadTelemetryViaWifiCall(
      String endpoint, String logId, String payloadJson) throws Exception {
    String script =
        "NS_PATH=''; "
        + "root_ns=$(readlink /proc/1/ns/net 2>/dev/null || :); "
        + "for cand in /data/adb/smartvowificall_ims/clash/netns.owner /data/adb/smartvowificall_ims/clash1/netns.owner; do "
        + "[ -f \"$cand\" ] || continue; "
        + "read -r nspid nsstart nsid < \"$cand\" 2>/dev/null || continue; "
        + "[ -n \"$nspid\" ] && [ -e \"/proc/$nspid/ns/net\" ] && kill -0 \"$nspid\" 2>/dev/null || continue; "
        + "live_ns=$(readlink \"/proc/$nspid/ns/net\" 2>/dev/null || :); "
        + "[ -n \"$live_ns\" ] && [ \"$live_ns\" = \"$nsid\" ] && [ \"$live_ns\" != \"$root_ns\" ] || continue; "
        + "NS_PATH=\"/proc/$nspid/ns/net\"; break; "
        + "done; "
        + "[ -n \"$NS_PATH\" ] || { echo '{\"ok\":false,\"code\":\"WIFICALL_LINK_UNAVAILABLE\"}'; exit 1; }; "
        + "nsenter --net=\"$NS_PATH\" -- /system/bin/env CLASSPATH="
        + quote(context.getApplicationInfo().sourceDir)
        + " /system/bin/app_process /system/bin com.smartvowificall.ims.root.RootTelemetryMain "
        + quote(endpoint) + " " + quote(logId);
    String raw = run(script, payloadJson.getBytes(StandardCharsets.UTF_8), 60);
    JSONObject j = new JSONObject(raw);
    Map<String, Object> headers = new LinkedHashMap<>();
    JSONObject h = j.optJSONObject("responseHeaders");
    if (h != null) {
      Iterator<String> it = h.keys();
      while (it.hasNext()) {
        String k = it.next();
        headers.put(k, h.optString(k, ""));
      }
    }
    return new com.smartvowificall.ims.telemetry.TelemetryClient.UploadResult(
        j.optBoolean("ok", false),
        j.optInt("httpStatus", 0),
        j.optString("code", "UPLOAD_FAILED"),
        j.optString("logId", logId),
        j.optString("recordPath", ""),
        j.optInt("payloadBytes", payloadJson.getBytes(StandardCharsets.UTF_8).length),
        j.optLong("durationMs", 0L),
        headers,
        j.optString("responseBody", ""));
  }
}
