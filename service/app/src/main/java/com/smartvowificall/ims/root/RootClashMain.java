package com.smartvowificall.ims.root;

import android.os.Process;
import android.system.Os;
import com.smartvowificall.ims.clash.ClashConfig;
import com.smartvowificall.ims.telemetry.LogSanitizer;
import com.smartvowificall.ims.telemetry.NetworkLogBuffer;
import com.smartvowificall.ims.engine.JsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Root app_process helper managing the bundled mihomo core (/data/adb/smartvowificall_ims/clash).
 * All config writes are atomic (0600) and controller calls stay on 127.0.0.1:19090 with secret.
 */
public final class RootClashMain {
  private static final String BASE_DIR = "/data/adb/smartvowificall_ims";
  private static final String MODULE_DIR = "/data/adb/modules/smartvowificall";
  private static final String CONTROL_SH = MODULE_DIR + "/clash-control.sh";
  private static final int MAX_PROFILE_BYTES = 4 * 1024 * 1024;
  private static final String DELAY_TEST_URL = "http://www.gstatic.com/generate_204";
  private static final ThreadLocal<ClashConfig.InstanceSpec> ACTIVE_SPEC =
      ThreadLocal.withInitial(() -> ClashConfig.spec(0));

  private static ClashConfig.InstanceSpec currentSpec() {
    ClashConfig.InstanceSpec s = ACTIVE_SPEC.get();
    return s != null ? s : ClashConfig.spec(0);
  }

  private static String currentDir() {
    return currentSpec().dirPath;
  }

  private RootClashMain() {}

  public static void main(String[] args) {
    JSONObject output = execute(args, System.in);
    int exitCode = output.optBoolean("ok", false) ? 0 : 1;
    System.out.println(output.toString());
    System.out.flush();
    System.exit(exitCode);
  }

  public static JSONObject execute(String[] args, byte[] stdinBytes) {
    InputStream in = stdinBytes == null
        ? new java.io.ByteArrayInputStream(new byte[0])
        : new java.io.ByteArrayInputStream(stdinBytes);
    return execute(args, in);
  }

  private static JSONObject execute(String[] args, InputStream in) {
    JSONObject output;
    ACTIVE_SPEC.set(ClashConfig.spec(0));
    try {
      if (Process.myUid() != 0) throw new Failure("ROOT_REQUIRED");
      String[] effArgs = args == null ? new String[0] : args;
      if (effArgs.length > 0 && effArgs[0] != null && effArgs[0].startsWith("--instance=")) {
        String idStr = effArgs[0].substring("--instance=".length());
        if (!idStr.matches("[0-9]+")) throw new Failure("CLASH_INSTANCE_INVALID");
        ACTIVE_SPEC.set(ClashConfig.spec(Integer.parseInt(idStr)));
        String[] shifted = new String[effArgs.length - 1];
        System.arraycopy(effArgs, 1, shifted, 0, shifted.length);
        effArgs = shifted;
      }
      String mode = effArgs.length == 0 ? "status" : effArgs[0];
      if (mode.equals("status") && effArgs.length <= 1) {
        output = status();
      } else if (mode.equals("save-profile") && effArgs.length == 1) {
        byte[] raw = readAllLimited(in, MAX_PROFILE_BYTES);
        output = saveProfile(new String(raw, StandardCharsets.UTF_8), null);
      } else if (mode.equals("fetch-subscription") && effArgs.length == 1) {
        String url = new String(readAllLimited(in, 4096), StandardCharsets.UTF_8).trim();
        output = fetchSubscription(url);
      } else if (mode.equals("copy-from") && effArgs.length == 2) {
        if (!effArgs[1].matches("[0-9]+")) throw new Failure("CLASH_INSTANCE_INVALID");
        output = copyFromInstance(Integer.parseInt(effArgs[1]));
      } else if (mode.equals("rebuild") && effArgs.length == 1) {
        output = rebuild();
      } else if (mode.equals("start") && effArgs.length == 1) {
        output = start(false);
      } else if (mode.equals("restart") && effArgs.length == 1) {
        output = start(true);
      } else if (mode.equals("stop") && effArgs.length == 1) {
        output = runControl("stop");
      } else if (mode.equals("stop-all") && effArgs.length == 1) {
        output = runControl("stop", "all");
      } else if (mode.equals("select-node") && effArgs.length == 1) {
        String node = new String(readAllLimited(in, 1024), StandardCharsets.UTF_8).trim();
        output = selectNode(node);
      } else if (mode.equals("test-delay") && effArgs.length == 1) {
        output = testDelay();
      } else if (mode.equals("set-controller") && effArgs.length == 1) {
        String body = new String(readAllLimited(in, 1024), StandardCharsets.UTF_8).trim();
        output = setController(new JSONObject(body));
      } else if (mode.equals("rotate-secret") && effArgs.length == 1) {
        output = rotateSecret();
      } else if (mode.equals("connections") && effArgs.length == 1) {
        output = connections();
      } else if (mode.equals("close-connection") && effArgs.length == 1) {
        String id = new String(readAllLimited(in, 256), StandardCharsets.UTF_8).trim();
        output = closeConnection(id);
      } else if (mode.equals("close-all-connections") && effArgs.length == 1) {
        output = closeAllConnections();
      } else if (mode.equals("sync-uplink") && effArgs.length == 1) {
        output = syncUplink();
      } else {
        throw new Failure("INVALID_ARGUMENT");
      }
    } catch (Throwable error) {
      String code = error instanceof Failure ? ((Failure) error).code
          : (error instanceof IllegalArgumentException && error.getMessage() != null
              && error.getMessage().matches("[A-Z0-9_]{3,80}"))
              ? error.getMessage()
              : "CLASH_ERROR";
      output = new JSONObject();
      try {
        output.put("ok", false).put("code", code);
      } catch (Exception ignored) {}
    } finally {
      ACTIVE_SPEC.remove();
    }
    return output;
  }

  private static void ensureDirs() throws Exception {
    for (String path : new String[] {BASE_DIR, currentDir()}) {
      File dir = new File(path);
      if (!dir.getAbsolutePath().equals(dir.getCanonicalPath()) || Files.isSymbolicLink(dir.toPath()))
        throw new Failure("CLASH_PATH_INVALID");
      if (!dir.isDirectory() && !dir.mkdirs()) throw new Failure("CLASH_DIR_CREATE_FAILED");
      Os.chmod(path, 0700);
    }
  }

  private static File clashFile(String name) throws Exception {
    ensureDirs();
    File f = new File(currentDir(), name);
    if (!f.getCanonicalPath().equals(f.getAbsolutePath()) || Files.isSymbolicLink(f.toPath()))
      throw new Failure("CLASH_PATH_INVALID");
    return f;
  }

  private static void writeAtomic(String name, byte[] data) throws Exception {
    File dest = clashFile(name);
    File tmp = File.createTempFile(".clash-", ".tmp", new File(currentDir()));
    try {
      Os.chmod(tmp.getAbsolutePath(), 0600);
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        out.write(data);
        out.getFD().sync();
      }
      Os.rename(tmp.getAbsolutePath(), dest.getAbsolutePath());
      Os.chmod(dest.getAbsolutePath(), 0600);
    } finally {
      Files.deleteIfExists(tmp.toPath());
    }
  }

  private static String readTextIfPresent(String name) throws Exception {
    File f = new File(currentDir(), name);
    if (!f.isFile() || Files.isSymbolicLink(f.toPath())) return "";
    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
  }

  private static String ensureSecret() throws Exception {
    String existing = readTextIfPresent("controller.secret");
    if (existing.matches("[A-Za-z0-9_-]{16,128}")) return existing;
    return generateAndSaveSecret();
  }

  private static String generateAndSaveSecret() throws Exception {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    StringBuilder hex = new StringBuilder(48);
    for (byte b : bytes) hex.append(String.format("%02x", b & 255));
    String secret = hex.toString();
    writeAtomic("controller.secret", (secret + "\n").getBytes(StandardCharsets.UTF_8));
    return secret;
  }

  private static final class ControllerSettings {
    final boolean externalLan;
    final int port;
    ControllerSettings(boolean externalLan, int port) {
      this.externalLan = externalLan;
      this.port = port;
    }
    String localUrl(String path) {
      return "http://" + ClashConfig.CONTROLLER_HOST + ":" + port + path;
    }
  }

  private static ControllerSettings readControllerSettings() {
    int defPort = currentSpec().defaultControllerPort;
    try {
      String raw = readTextIfPresent("controller.json");
      if (!raw.isEmpty()) {
        JSONObject j = new JSONObject(raw);
        boolean lan = j.optBoolean("externalLan", false);
        int port = j.optInt("port", defPort);
        if (port >= 1024 && port <= 65535) return new ControllerSettings(lan, port);
      }
    } catch (Exception ignored) {}
    return new ControllerSettings(false, defPort);
  }

  private static String currentLanIpv4() {
    try {
      NetworkInterface wlan = NetworkInterface.getByName("wlan0");
      if (wlan != null && wlan.isUp()) {
        Enumeration<InetAddress> addrs = wlan.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress a = addrs.nextElement();
          if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
        }
      }
    } catch (Exception ignored) {}
    return "";
  }

  /**
   * Detects the active physical IPv4 uplink interface (e.g. wlan0, rmnet_data3) while strictly
   * bypassing any active system VPN (tun0) using Android AOSP FWMARK_PROTECTED_FROM_VPN (0x20000).
   */
  private static String detectPhysicalUplink() {
    try {
      java.lang.Process p = new ProcessBuilder("/system/bin/ip", "-4", "route", "get", "1.1.1.1", "mark", "0x20000")
          .redirectErrorStream(true).start();
      try (InputStream is = p.getInputStream()) {
        byte[] raw = readAllLimited(is, 4096);
        if (p.waitFor(2, TimeUnit.SECONDS)) {
          String[] tokens = new String(raw, StandardCharsets.UTF_8).trim().split("\\s+");
          for (int i = 0; i + 1 < tokens.length; i++) {
            if ("dev".equals(tokens[i]) && ClashConfig.isSafePhysicalUplink(tokens[i + 1])) {
              return tokens[i + 1];
            }
          }
        }
      } finally {
        p.destroy();
      }
    } catch (Exception ignored) {}
    // Fallback: inspect live IPv4 interfaces, preferring wlan*/eth* over cellular, never tun*.
    String fallback = "";
    try {
      Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
      while (ifaces != null && ifaces.hasMoreElements()) {
        NetworkInterface nif = ifaces.nextElement();
        String name = nif.getName();
        if (!nif.isUp() || nif.isLoopback() || !ClashConfig.isSafePhysicalUplink(name)) continue;
        boolean hasV4 = false;
        Enumeration<InetAddress> addrs = nif.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress a = addrs.nextElement();
          if (a instanceof Inet4Address && !a.isLoopbackAddress()) { hasV4 = true; break; }
        }
        if (!hasV4) continue;
        if (name.startsWith("wlan") || name.startsWith("eth")) return name;
        if (fallback.isEmpty()) fallback = name;
      }
    } catch (Exception ignored) {}
    return fallback;
  }

  private static JSONObject syncUplink() throws Exception {
    JSONObject ctl = runControl("status");
    if (!ctl.optBoolean("running", false)) {
      return new JSONObject().put("ok", true).put("code", "CLASH_NOT_RUNNING").put("changed", false);
    }
    String uplink = detectPhysicalUplink();
    boolean changed = syncUplinkIfNeeded(uplink);
    return new JSONObject()
        .put("ok", true)
        .put("code", changed ? "CLASH_UPLINK_UPDATED" : "CLASH_UPLINK_UNCHANGED")
        .put("uplink", uplink)
        .put("changed", changed);
  }

  private static boolean syncUplinkIfNeeded(String uplink) {
    if (!ClashConfig.isSafePhysicalUplink(uplink)) return false;
    try {
      JSONObject cfg = controllerGet("/configs");
      String current = cfg.optString("interface-name", "");
      if (uplink.equals(current)) return false;
      controllerPatch("/configs", new JSONObject().put("interface-name", uplink).toString());
      try {
        controllerDelete("/connections");
      } catch (Exception ignored) {}
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static JSONObject copyFromInstance(int srcId) throws Exception {
    ClashConfig.InstanceSpec src = ClashConfig.spec(srcId);
    if (src.id == currentSpec().id) throw new Failure("CLASH_COPY_SAME_INSTANCE");
    File srcProfile = new File(src.dirPath, "profile.yaml");
    if (!srcProfile.isFile() || Files.isSymbolicLink(srcProfile.toPath()) || srcProfile.length() <= 0)
      throw new Failure("CLASH_SOURCE_PROFILE_MISSING");
    byte[] bytes = Files.readAllBytes(srcProfile.toPath());
    if (bytes.length > MAX_PROFILE_BYTES) throw new Failure("CLASH_PROFILE_TOO_LARGE");
    String srcSub = "";
    File srcSubFile = new File(src.dirPath, "subscription.url");
    if (srcSubFile.isFile() && !Files.isSymbolicLink(srcSubFile.toPath())) {
      srcSub = new String(Files.readAllBytes(srcSubFile.toPath()), StandardCharsets.UTF_8).trim();
    }
    return saveProfile(new String(bytes, StandardCharsets.UTF_8), srcSub.isEmpty() ? null : srcSub);
  }

  private static JSONObject saveProfile(String rawYaml, String subscriptionUrl) throws Exception {
    String secret = ensureSecret();
    ControllerSettings cs = readControllerSettings();
    String uplink = detectPhysicalUplink();
    ClashConfig.Built built = ClashConfig.buildRuntimeConfigForInstance(
        currentSpec().id, rawYaml, secret, cs.externalLan, cs.port, uplink);
    writeAtomic("profile.yaml", rawYaml.getBytes(StandardCharsets.UTF_8));
    writeAtomic("runtime.yaml", built.yaml.getBytes(StandardCharsets.UTF_8));
    if (subscriptionUrl != null) {
      writeAtomic("subscription.url", (subscriptionUrl + "\n").getBytes(StandardCharsets.UTF_8));
    }
    return new JSONObject()
        .put("ok", true)
        .put("code", "CLASH_PROFILE_SAVED")
        .put("instanceId", currentSpec().id)
        .put("uplink", uplink)
        .put("proxyCount", built.proxyNames.size())
        .put("providerCount", built.providerNames.size());
  }

  private static JSONObject rebuild() throws Exception {
    File profile = new File(currentDir(), "profile.yaml");
    if (!profile.isFile() || Files.isSymbolicLink(profile.toPath()))
      throw new Failure("CLASH_PROFILE_MISSING");
    byte[] bytes = Files.readAllBytes(profile.toPath());
    if (bytes.length > MAX_PROFILE_BYTES) throw new Failure("CLASH_PROFILE_TOO_LARGE");
    String secret = ensureSecret();
    ControllerSettings cs = readControllerSettings();
    String uplink = detectPhysicalUplink();
    ClashConfig.Built built = ClashConfig.buildRuntimeConfigForInstance(
        currentSpec().id, new String(bytes, StandardCharsets.UTF_8), secret, cs.externalLan, cs.port, uplink);
    writeAtomic("runtime.yaml", built.yaml.getBytes(StandardCharsets.UTF_8));
    return new JSONObject()
        .put("ok", true)
        .put("code", "CLASH_RUNTIME_BUILT")
        .put("instanceId", currentSpec().id)
        .put("uplink", uplink)
        .put("proxyCount", built.proxyNames.size())
        .put("providerCount", built.providerNames.size());
  }

  private static JSONObject setController(JSONObject input) throws Exception {
    boolean lan = input.optBoolean("externalLan", false);
    int port = input.optInt("port", currentSpec().defaultControllerPort);
    if (port < 1024 || port > 65535) throw new Failure("CLASH_CONTROLLER_PORT_INVALID");
    JSONObject saved = new JSONObject().put("externalLan", lan).put("port", port);
    writeAtomic("controller.json", (saved.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    File profile = new File(currentDir(), "profile.yaml");
    boolean restarted = false;
    if (profile.isFile() && profile.length() > 0) {
      rebuild();
      JSONObject ctl = runControl("status");
      if (ctl.optBoolean("running")) {
        start(true);
        restarted = true;
      }
    }
    return new JSONObject()
        .put("ok", true)
        .put("code", "CLASH_CONTROLLER_UPDATED")
        .put("externalLan", lan)
        .put("port", port)
        .put("restarted", restarted);
  }

  private static JSONObject rotateSecret() throws Exception {
    String secret = generateAndSaveSecret();
    File profile = new File(currentDir(), "profile.yaml");
    boolean restarted = false;
    if (profile.isFile() && profile.length() > 0) {
      rebuild();
      JSONObject ctl = runControl("status");
      if (ctl.optBoolean("running")) {
        start(true);
        restarted = true;
      }
    }
    return new JSONObject()
        .put("ok", true)
        .put("code", "CLASH_SECRET_ROTATED")
        .put("secret", secret)
        .put("restarted", restarted);
  }

  private static JSONObject connections() throws Exception {
    JSONObject ctl = runControl("status");
    if (!ctl.optBoolean("running")) {
      return new JSONObject()
          .put("ok", true)
          .put("code", "CLASH_CONNECTIONS")
          .put("running", false)
          .put("downloadTotal", 0)
          .put("uploadTotal", 0)
          .put("memory", 0)
          .put("connections", new JSONArray());
    }
    JSONObject raw = controllerGet("/connections");
    JSONArray list = new JSONArray();
    JSONArray src = raw.optJSONArray("connections");
    if (src != null) {
      for (int i = 0; i < src.length(); i++) {
        JSONObject c = src.optJSONObject(i);
        if (c == null) continue;
        JSONObject meta = c.optJSONObject("metadata");
        String net = meta == null ? "" : meta.optString("network", "");
        String type = meta == null ? "" : meta.optString("type", "");
        String srcIp = meta == null ? "" : meta.optString("sourceIP", "");
        String srcPort = meta == null ? "" : meta.optString("sourcePort", "");
        String host = meta == null ? "" : meta.optString("host", "");
        String dstIp = meta == null ? "" : meta.optString("destinationIP", "");
        String dstPort = meta == null ? "" : meta.optString("destinationPort", "");
        String destHost = !host.isEmpty() ? host : dstIp;
        String dest = dstPort.isEmpty() ? destHost : destHost + ":" + dstPort;
        String source = srcPort.isEmpty() ? srcIp : srcIp + ":" + srcPort;
        JSONArray chains = c.optJSONArray("chains");
        list.put(new JSONObject()
            .put("id", c.optString("id", ""))
            .put("network", net)
            .put("type", type)
            .put("source", source)
            .put("destination", dest)
            .put("rule", c.optString("rule", ""))
            .put("rulePayload", c.optString("rulePayload", ""))
            .put("chains", chains == null ? new JSONArray() : chains)
            .put("upload", c.optLong("upload", 0))
            .put("download", c.optLong("download", 0))
            .put("start", c.optString("start", "")));
      }
    }
    return new JSONObject()
        .put("ok", true)
        .put("code", "CLASH_CONNECTIONS")
        .put("running", true)
        .put("downloadTotal", raw.optLong("downloadTotal", 0))
        .put("uploadTotal", raw.optLong("uploadTotal", 0))
        .put("memory", raw.optLong("memory", 0))
        .put("connections", list);
  }

  private static JSONObject closeConnection(String id) throws Exception {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
      throw new Failure("INVALID_CONNECTION_ID");
    controllerDelete("/connections/" + urlEncode(id));
    return new JSONObject().put("ok", true).put("code", "CLASH_CONNECTION_CLOSED").put("id", id);
  }

  private static JSONObject closeAllConnections() throws Exception {
    controllerDelete("/connections");
    return new JSONObject().put("ok", true).put("code", "CLASH_CONNECTIONS_CLEARED");
  }

  private static JSONObject fetchSubscription(String urlText) throws Exception {
    if (urlText == null || !(urlText.startsWith("https://") || urlText.startsWith("http://")))
      throw new Failure("INVALID_SUBSCRIPTION_URL");
    URL current = new URL(urlText);
    HttpURLConnection conn = null;
    for (int hop = 0; hop < 6; hop++) {
      long started = System.nanoTime();
      Map<String, Object> reqHeaders = new LinkedHashMap<>();
      reqHeaders.put("User-Agent", "clash.meta/1.19.34");
      reqHeaders.put("Accept", "application/yaml, text/yaml, text/plain, */*");
      reqHeaders.put("Accept-Encoding", "gzip");
      conn = (HttpURLConnection) current.openConnection();
      conn.setInstanceFollowRedirects(false);
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(20000);
      conn.setRequestMethod("GET");
      conn.setRequestProperty("User-Agent", "clash.meta/1.19.34");
      conn.setRequestProperty("Accept", "application/yaml, text/yaml, text/plain, */*");
      conn.setRequestProperty("Accept-Encoding", "gzip");
      int status = conn.getResponseCode();
      long durMs = (System.nanoTime() - started) / 1_000_000L;
      Map<String, Object> respHeaders = extractHeaders(conn);
      if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
        String loc = conn.getHeaderField("Location");
        recordHttpTrace("clash_subscription", "GET", current.toString(), status, durMs,
            reqHeaders, null, respHeaders, Collections.singletonMap("redirect", LogSanitizer.HIDDEN), null);
        conn.disconnect();
        if (loc == null || loc.isEmpty()) throw new Failure("SUBSCRIPTION_FETCH_FAILED");
        current = new URL(current, loc);
        String proto = current.getProtocol();
        if (!"http".equals(proto) && !"https".equals(proto))
          throw new Failure("INVALID_SUBSCRIPTION_URL");
        continue;
      }
      if (status < 200 || status >= 300) {
        recordHttpTrace("clash_subscription", "GET", current.toString(), status, durMs,
            reqHeaders, null, respHeaders, null, "SUBSCRIPTION_HTTP_" + status);
        conn.disconnect();
        throw new Failure("SUBSCRIPTION_HTTP_" + status);
      }
      try (InputStream raw = conn.getInputStream();
           InputStream in = "gzip".equalsIgnoreCase(conn.getContentEncoding())
               ? new GZIPInputStream(raw) : raw) {
        byte[] data = readAllLimited(in, MAX_PROFILE_BYTES);
        Map<String, Object> bodySummary = new LinkedHashMap<>();
        bodySummary.put("contentType", conn.getContentType() == null ? "" : conn.getContentType());
        bodySummary.put("bytes", data.length);
        bodySummary.put("body", LogSanitizer.HIDDEN);
        recordHttpTrace("clash_subscription", "GET", current.toString(), status, durMs,
            reqHeaders, null, respHeaders, bodySummary, null);
        return saveProfile(new String(data, StandardCharsets.UTF_8), urlText);
      } finally {
        conn.disconnect();
      }
    }
    throw new Failure("SUBSCRIPTION_TOO_MANY_REDIRECTS");
  }

  private static JSONObject start(boolean restart) throws Exception {
    rebuild();
    if (restart) {
      try { runControl("stop"); } catch (Exception ignored) {}
    }
    JSONObject res = runControl("start");
    // Wait briefly for controller API and apply saved node selection if present.
    String saved = readTextIfPresent("selected-node.txt");
    for (int i = 0; i < 15; i++) {
      try {
        JSONObject group = controllerGet("/proxies/" + urlEncode(ClashConfig.PROXY_GROUP));
        if (!saved.isEmpty() && jsonArrayContains(group.optJSONArray("all"), saved)
            && !saved.equals(group.optString("now", ""))) {
          controllerPut("/proxies/" + urlEncode(ClashConfig.PROXY_GROUP),
              new JSONObject().put("name", saved).toString());
        }
        break;
      } catch (Exception ignored) {
        Thread.sleep(300);
      }
    }
    return res;
  }

  private static JSONObject selectNode(String node) throws Exception {
    if (node == null || node.isEmpty() || node.length() > 512)
      throw new Failure("INVALID_NODE_NAME");
    writeAtomic("selected-node.txt", (node + "\n").getBytes(StandardCharsets.UTF_8));
    JSONObject ctl = runControl("status");
    if (ctl.optBoolean("running")) {
      controllerPut("/proxies/" + urlEncode(ClashConfig.PROXY_GROUP),
          new JSONObject().put("name", node).toString());
    }
    return new JSONObject().put("ok", true).put("code", "CLASH_NODE_SELECTED").put("node", node);
  }

  private static JSONObject testDelay() throws Exception {
    JSONObject ctl = runControl("status");
    if (!ctl.optBoolean("running")) throw new Failure("CLASH_NOT_RUNNING");
    String path = "/group/" + urlEncode(ClashConfig.PROXY_GROUP) + "/delay?timeout=5000&url="
        + urlEncode(DELAY_TEST_URL);
    JSONObject delays = controllerGet(path);
    return new JSONObject().put("ok", true).put("code", "CLASH_DELAY_TESTED").put("delays", delays);
  }

  private static JSONObject status() throws Exception {
    ClashConfig.InstanceSpec spec = currentSpec();
    File profile = new File(spec.dirPath, "profile.yaml");
    boolean hasProfile = profile.isFile() && profile.length() > 0;
    JSONObject ctl;
    try {
      ctl = runControl("status");
    } catch (Exception e) {
      ctl = new JSONObject().put("running", false).put("tunUp", false);
    }
    boolean running = ctl.optBoolean("running", false);
    boolean tunUp = ctl.optBoolean("tunUp", false);
    ControllerSettings cs = readControllerSettings();
    String secret = ensureSecret();
    JSONObject out = new JSONObject()
        .put("ok", true)
        .put("code", "CLASH_STATUS")
        .put("instanceId", spec.id)
        .put("hasProfile", hasProfile)
        .put("profileBytes", hasProfile ? profile.length() : 0)
        .put("running", running)
        .put("tunUp", tunUp)
        .put("device", spec.tunDevice)
        .put("netnsId", ctl.optString("netnsId", ""))
        .put("externalLan", cs.externalLan)
        .put("controllerPort", cs.port)
        .put("controllerSecret", secret)
        .put("lanIpv4", currentLanIpv4())
        .put("subscriptionUrl", readTextIfPresent("subscription.url"))
        .put("savedNode", readTextIfPresent("selected-node.txt"))
        .put("currentNode", "")
        .put("nodes", new JSONArray());
    if (running) {
      syncUplinkIfNeeded(detectPhysicalUplink());
      try {
        JSONObject allProxies = controllerGet("/proxies").optJSONObject("proxies");
        JSONObject group = allProxies == null ? null : allProxies.optJSONObject(ClashConfig.PROXY_GROUP);
        if (group != null) {
          out.put("currentNode", group.optString("now", ""));
          JSONArray all = group.optJSONArray("all");
          JSONArray nodes = new JSONArray();
          if (all != null) {
            for (int i = 0; i < all.length(); i++) {
              String name = all.optString(i, "");
              if (name.isEmpty()) continue;
              JSONObject item = new JSONObject().put("name", name);
              JSONObject detail = allProxies.optJSONObject(name);
              if (detail != null) {
                item.put("type", detail.optString("type", ""));
                JSONArray hist = detail.optJSONArray("history");
                if (hist != null && hist.length() > 0) {
                  item.put("delay", hist.getJSONObject(hist.length() - 1).optInt("delay", 0));
                }
              }
              nodes.put(item);
            }
          }
          out.put("nodes", nodes);
        }
      } catch (Exception ignored) {
        // Controller may still be starting up.
      }
    }
    return out;
  }

  private static JSONObject controllerGet(String path) throws Exception {
    String secret = readTextIfPresent("controller.secret");
    ControllerSettings cs = readControllerSettings();
    String target = cs.localUrl(path);
    long started = System.nanoTime();
    Map<String, Object> reqHeaders = new LinkedHashMap<>();
    if (!secret.isEmpty()) reqHeaders.put("Authorization", "Bearer " + secret);
    HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
    conn.setConnectTimeout(3000);
    conn.setReadTimeout(8000);
    conn.setRequestMethod("GET");
    if (!secret.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + secret);
    try {
      int code = conn.getResponseCode();
      long durMs = (System.nanoTime() - started) / 1_000_000L;
      Map<String, Object> respHeaders = extractHeaders(conn);
      if (code < 200 || code >= 300) {
        recordHttpTrace("clash_http", "GET", target, code, durMs, reqHeaders, null, respHeaders,
            null, "CLASH_API_HTTP_" + code);
        throw new Failure("CLASH_API_HTTP_" + code);
      }
      try (InputStream in = conn.getInputStream()) {
        byte[] body = readAllLimited(in, MAX_PROFILE_BYTES);
        String bodyText = new String(body, StandardCharsets.UTF_8);
        // Skip routine high-frequency 200 OK status polls (/configs, /proxies, /connections) so
        // http-trace.jsonl stays compact and never stores truncated proxy-node lists.
        boolean routinePoll = "/configs".equals(path) || "/proxies".equals(path)
            || "/connections".equals(path);
        if (!routinePoll) {
          recordHttpTrace("clash_http", "GET", target, code, durMs, reqHeaders, null, respHeaders,
              NetworkLogBuffer.parseJsonOrText(bodyText, 4096), null);
        }
        return new JSONObject(bodyText);
      }
    } finally {
      conn.disconnect();
    }
  }

  private static void controllerPut(String path, String jsonBody) throws Exception {
    String secret = readTextIfPresent("controller.secret");
    ControllerSettings cs = readControllerSettings();
    String target = cs.localUrl(path);
    long started = System.nanoTime();
    Map<String, Object> reqHeaders = new LinkedHashMap<>();
    reqHeaders.put("Content-Type", "application/json");
    if (!secret.isEmpty()) reqHeaders.put("Authorization", "Bearer " + secret);
    HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
    conn.setConnectTimeout(3000);
    conn.setReadTimeout(5000);
    conn.setRequestMethod("PUT");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    if (!secret.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + secret);
    byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
    try {
      try (OutputStream out = conn.getOutputStream()) {
        out.write(payload);
      }
      int code = conn.getResponseCode();
      long durMs = (System.nanoTime() - started) / 1_000_000L;
      Map<String, Object> respHeaders = extractHeaders(conn);
      Object reqParsed = NetworkLogBuffer.parseJsonOrText(jsonBody, 2048);
      if (code < 200 || code >= 300) {
        recordHttpTrace("clash_http", "PUT", target, code, durMs, reqHeaders, reqParsed,
            respHeaders, null, "CLASH_API_HTTP_" + code);
        throw new Failure("CLASH_API_HTTP_" + code);
      }
      recordHttpTrace("clash_http", "PUT", target, code, durMs, reqHeaders, reqParsed,
          respHeaders, "", null);
    } finally {
      conn.disconnect();
    }
  }

  private static void controllerPatch(String path, String jsonBody) throws Exception {
    String secret = readTextIfPresent("controller.secret");
    ControllerSettings cs = readControllerSettings();
    byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
    StringBuilder req = new StringBuilder();
    req.append("PATCH ").append(path).append(" HTTP/1.1\r\n");
    req.append("Host: ").append(ClashConfig.CONTROLLER_HOST).append(':').append(cs.port).append("\r\n");
    req.append("Content-Type: application/json\r\n");
    if (!secret.isEmpty()) req.append("Authorization: Bearer ").append(secret).append("\r\n");
    req.append("Content-Length: ").append(payload.length).append("\r\n");
    req.append("Connection: close\r\n\r\n");
    try (java.net.Socket sock = new java.net.Socket()) {
      sock.connect(new java.net.InetSocketAddress(ClashConfig.CONTROLLER_HOST, cs.port), 2000);
      sock.setSoTimeout(3000);
      OutputStream out = sock.getOutputStream();
      out.write(req.toString().getBytes(StandardCharsets.UTF_8));
      out.write(payload);
      out.flush();
      byte[] resp = readAllLimited(sock.getInputStream(), 2048);
      String statusLine = new String(resp, StandardCharsets.UTF_8);
      if (!statusLine.startsWith("HTTP/1.1 2") && !statusLine.startsWith("HTTP/1.0 2")) {
        throw new Failure("CLASH_API_PATCH_FAILED");
      }
    }
  }

  private static void controllerDelete(String path) throws Exception {
    String secret = readTextIfPresent("controller.secret");
    ControllerSettings cs = readControllerSettings();
    String target = cs.localUrl(path);
    long started = System.nanoTime();
    Map<String, Object> reqHeaders = new LinkedHashMap<>();
    if (!secret.isEmpty()) reqHeaders.put("Authorization", "Bearer " + secret);
    HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
    conn.setConnectTimeout(3000);
    conn.setReadTimeout(5000);
    conn.setRequestMethod("DELETE");
    if (!secret.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + secret);
    try {
      int code = conn.getResponseCode();
      long durMs = (System.nanoTime() - started) / 1_000_000L;
      Map<String, Object> respHeaders = extractHeaders(conn);
      if (code < 200 || code >= 300) {
        recordHttpTrace("clash_http", "DELETE", target, code, durMs, reqHeaders, null, respHeaders,
            null, "CLASH_API_HTTP_" + code);
        throw new Failure("CLASH_API_HTTP_" + code);
      }
      recordHttpTrace("clash_http", "DELETE", target, code, durMs, reqHeaders, null, respHeaders,
          "", null);
    } finally {
      conn.disconnect();
    }
  }

  private static Map<String, Object> extractHeaders(HttpURLConnection conn) {
    Map<String, Object> out = new LinkedHashMap<>();
    try {
      Map<String, List<String>> raw = conn.getHeaderFields();
      if (raw != null) {
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
          if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
          out.put(e.getKey(), String.join(", ", e.getValue()));
        }
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  private static void recordHttpTrace(String channel, String method, String target, int status,
      long durationMs, Map<String, Object> reqHeaders, Object reqBody,
      Map<String, Object> respHeaders, Object respBody, String error) {
    try {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("ts", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
          .withZone(ZoneOffset.UTC).format(Instant.now()));
      entry.put("channel", channel);
      entry.put("direction", "req_res");
      entry.put("method", method);
      entry.put("target", LogSanitizer.sanitizeUrl(target));
      entry.put("status", status);
      entry.put("durationMs", Math.max(0L, durationMs));
      entry.put("requestHeaders", LogSanitizer.sanitizeStructure(reqHeaders, "requestHeaders"));
      entry.put("requestBody", LogSanitizer.sanitizeStructure(reqBody, "requestBody"));
      entry.put("responseHeaders", LogSanitizer.sanitizeStructure(respHeaders, "responseHeaders"));
      entry.put("responseBody", LogSanitizer.sanitizeStructure(respBody, "responseBody"));
      if (error != null && !error.isEmpty()) entry.put("error", LogSanitizer.sanitizeText(error));
      String line = JsonCodec.encode(entry);
      File f = clashFile("http-trace.jsonl");
      List<String> lines = new ArrayList<>();
      if (f.isFile()) {
        String existing = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        for (String l : existing.split("\n")) {
          if (!l.trim().isEmpty()) lines.add(l.trim());
        }
      }
      lines.add(line);
      int start = Math.max(0, lines.size() - 50);
      StringBuilder sb = new StringBuilder();
      for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
      writeAtomic("http-trace.jsonl", sb.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Throwable ignored) {
      // Trace logging is strictly best-effort and must never fail Clash operations.
    }
  }

  private static JSONObject runControl(String action) throws Exception {
    return runControl(action, String.valueOf(currentSpec().id));
  }

  private static JSONObject runControl(String action, String instanceArg) throws Exception {
    java.lang.Process p = new ProcessBuilder("/system/bin/sh", CONTROL_SH, action, instanceArg).start();
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    Thread tOut = new Thread(() -> drain(p.getInputStream(), stdout, 262144));
    Thread tErr = new Thread(() -> drain(p.getErrorStream(), null, 0));
    tOut.setDaemon(true); tErr.setDaemon(true);
    tOut.start(); tErr.start();
    try {
      p.getOutputStream().close();
      if (!p.waitFor(25, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new Failure("CLASH_CONTROL_TIMEOUT");
      }
      tOut.join(1000); tErr.join(1000);
      String text = stdout.toString("UTF-8").trim();
      JSONObject parsed = null;
      try { parsed = new JSONObject(text); } catch (Exception ignored) {}
      if (p.exitValue() != 0) {
        String code = parsed != null ? parsed.optString("code", "CLASH_CONTROL_FAILED") : "CLASH_CONTROL_FAILED";
        throw new Failure(code.matches("[A-Z0-9_]{3,80}") ? code : "CLASH_CONTROL_FAILED");
      }
      return parsed != null ? parsed : new JSONObject().put("ok", true).put("code", "CLASH_OK");
    } finally {
      p.destroy();
    }
  }

  private static void drain(InputStream in, ByteArrayOutputStream out, int limit) {
    try (InputStream s = in) {
      byte[] buf = new byte[4096];
      int n;
      while ((n = s.read(buf)) != -1) {
        if (out != null && out.size() + n <= limit) out.write(buf, 0, n);
      }
    } catch (IOException ignored) {}
  }

  private static byte[] readAllLimited(InputStream in, int maxBytes) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) != -1) {
      if (out.size() + n > maxBytes) throw new Failure("CLASH_INPUT_TOO_LARGE");
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static boolean jsonArrayContains(JSONArray arr, String target) {
    if (arr == null || target == null) return false;
    for (int i = 0; i < arr.length(); i++) if (target.equals(arr.optString(i, ""))) return true;
    return false;
  }

  private static String urlEncode(String value) throws Exception {
    return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
  }

  private static final class Failure extends Exception {
    final String code;
    Failure(String code) { super(code); this.code = code; }
  }
}
