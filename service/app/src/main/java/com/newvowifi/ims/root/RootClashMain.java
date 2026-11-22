package com.newvowifi.ims.root;

import android.os.Process;
import android.system.Os;
import com.newvowifi.ims.clash.ClashConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Root app_process helper managing the bundled mihomo core (/data/adb/newvowifi_ims/clash).
 * All config writes are atomic (0600) and controller calls stay on 127.0.0.1:19090 with secret.
 */
public final class RootClashMain {
  private static final String BASE_DIR = "/data/adb/newvowifi_ims";
  private static final String CLASH_DIR = BASE_DIR + "/clash";
  private static final String MODULE_DIR = "/data/adb/modules/newvowifi";
  private static final String CONTROL_SH = MODULE_DIR + "/clash-control.sh";
  private static final int MAX_PROFILE_BYTES = 4 * 1024 * 1024;
  private static final String DELAY_TEST_URL = "http://www.gstatic.com/generate_204";

  private RootClashMain() {}

  public static void main(String[] args) {
    int exitCode = 0;
    JSONObject output;
    try {
      if (Process.myUid() != 0) throw new Failure("ROOT_REQUIRED");
      String mode = args.length == 0 ? "status" : args[0];
      if (mode.equals("status") && args.length <= 1) {
        output = status();
      } else if (mode.equals("save-profile") && args.length == 1) {
        byte[] raw = readAllLimited(System.in, MAX_PROFILE_BYTES);
        output = saveProfile(new String(raw, StandardCharsets.UTF_8), null);
      } else if (mode.equals("fetch-subscription") && args.length == 1) {
        String url = new String(readAllLimited(System.in, 4096), StandardCharsets.UTF_8).trim();
        output = fetchSubscription(url);
      } else if (mode.equals("rebuild") && args.length == 1) {
        output = rebuild();
      } else if (mode.equals("start") && args.length == 1) {
        output = start(false);
      } else if (mode.equals("restart") && args.length == 1) {
        output = start(true);
      } else if (mode.equals("stop") && args.length == 1) {
        output = runControl("stop");
      } else if (mode.equals("select-node") && args.length == 1) {
        String node = new String(readAllLimited(System.in, 1024), StandardCharsets.UTF_8).trim();
        output = selectNode(node);
      } else if (mode.equals("test-delay") && args.length == 1) {
        output = testDelay();
      } else {
        throw new Failure("INVALID_ARGUMENT");
      }
    } catch (Throwable error) {
      exitCode = 1;
      String code = error instanceof Failure ? ((Failure) error).code
          : (error instanceof IllegalArgumentException && error.getMessage() != null
              && error.getMessage().matches("[A-Z0-9_]{3,80}"))
              ? error.getMessage()
              : "CLASH_ERROR";
      output = new JSONObject();
      try {
        output.put("ok", false).put("code", code);
      } catch (Exception ignored) {}
    }
    System.out.println(output.toString());
    System.out.flush();
    System.exit(exitCode);
  }

  private static void ensureDirs() throws Exception {
    for (String path : new String[] {BASE_DIR, CLASH_DIR}) {
      File dir = new File(path);
      if (!dir.getAbsolutePath().equals(dir.getCanonicalPath()) || Files.isSymbolicLink(dir.toPath()))
        throw new Failure("CLASH_PATH_INVALID");
      if (!dir.isDirectory() && !dir.mkdirs()) throw new Failure("CLASH_DIR_CREATE_FAILED");
      Os.chmod(path, 0700);
    }
  }

  private static File clashFile(String name) throws Exception {
    ensureDirs();
    File f = new File(CLASH_DIR, name);
    if (!f.getCanonicalPath().equals(f.getAbsolutePath()) || Files.isSymbolicLink(f.toPath()))
      throw new Failure("CLASH_PATH_INVALID");
    return f;
  }

  private static void writeAtomic(String name, byte[] data) throws Exception {
    File dest = clashFile(name);
    File tmp = File.createTempFile(".clash-", ".tmp", new File(CLASH_DIR));
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
    File f = new File(CLASH_DIR, name);
    if (!f.isFile() || Files.isSymbolicLink(f.toPath())) return "";
    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
  }

  private static String ensureSecret() throws Exception {
    String existing = readTextIfPresent("controller.secret");
    if (existing.matches("[A-Za-z0-9_-]{16,128}")) return existing;
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    StringBuilder hex = new StringBuilder(48);
    for (byte b : bytes) hex.append(String.format("%02x", b & 255));
    String secret = hex.toString();
    writeAtomic("controller.secret", (secret + "\n").getBytes(StandardCharsets.UTF_8));
    return secret;
  }

  private static JSONObject saveProfile(String rawYaml, String subscriptionUrl) throws Exception {
    String secret = ensureSecret();
    ClashConfig.Built built = ClashConfig.buildRuntimeConfig(rawYaml, secret);
    writeAtomic("profile.yaml", rawYaml.getBytes(StandardCharsets.UTF_8));
    writeAtomic("runtime.yaml", built.yaml.getBytes(StandardCharsets.UTF_8));
    if (subscriptionUrl != null) {
      writeAtomic("subscription.url", (subscriptionUrl + "\n").getBytes(StandardCharsets.UTF_8));
    }
    return new JSONObject()
        .put("ok", true)
        .put("code", "CLASH_PROFILE_SAVED")
        .put("proxyCount", built.proxyNames.size())
        .put("providerCount", built.providerNames.size());
  }

  private static JSONObject rebuild() throws Exception {
    File profile = new File(CLASH_DIR, "profile.yaml");
    if (!profile.isFile() || Files.isSymbolicLink(profile.toPath()))
      throw new Failure("CLASH_PROFILE_MISSING");
    byte[] bytes = Files.readAllBytes(profile.toPath());
    if (bytes.length > MAX_PROFILE_BYTES) throw new Failure("CLASH_PROFILE_TOO_LARGE");
    String secret = ensureSecret();
    ClashConfig.Built built = ClashConfig.buildRuntimeConfig(new String(bytes, StandardCharsets.UTF_8), secret);
    writeAtomic("runtime.yaml", built.yaml.getBytes(StandardCharsets.UTF_8));
    return new JSONObject()
        .put("ok", true)
        .put("code", "CLASH_RUNTIME_BUILT")
        .put("proxyCount", built.proxyNames.size())
        .put("providerCount", built.providerNames.size());
  }

  private static JSONObject fetchSubscription(String urlText) throws Exception {
    if (urlText == null || !(urlText.startsWith("https://") || urlText.startsWith("http://")))
      throw new Failure("INVALID_SUBSCRIPTION_URL");
    URL current = new URL(urlText);
    HttpURLConnection conn = null;
    for (int hop = 0; hop < 6; hop++) {
      conn = (HttpURLConnection) current.openConnection();
      conn.setInstanceFollowRedirects(false);
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(20000);
      conn.setRequestMethod("GET");
      conn.setRequestProperty("User-Agent", "clash.meta/1.19.34");
      conn.setRequestProperty("Accept", "application/yaml, text/yaml, text/plain, */*");
      conn.setRequestProperty("Accept-Encoding", "gzip");
      int status = conn.getResponseCode();
      if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
        String loc = conn.getHeaderField("Location");
        conn.disconnect();
        if (loc == null || loc.isEmpty()) throw new Failure("SUBSCRIPTION_FETCH_FAILED");
        current = new URL(current, loc);
        String proto = current.getProtocol();
        if (!"http".equals(proto) && !"https".equals(proto))
          throw new Failure("INVALID_SUBSCRIPTION_URL");
        continue;
      }
      if (status < 200 || status >= 300) {
        conn.disconnect();
        throw new Failure("SUBSCRIPTION_HTTP_" + status);
      }
      try (InputStream raw = conn.getInputStream();
           InputStream in = "gzip".equalsIgnoreCase(conn.getContentEncoding())
               ? new GZIPInputStream(raw) : raw) {
        byte[] data = readAllLimited(in, MAX_PROFILE_BYTES);
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
    File profile = new File(CLASH_DIR, "profile.yaml");
    boolean hasProfile = profile.isFile() && profile.length() > 0;
    JSONObject ctl;
    try {
      ctl = runControl("status");
    } catch (Exception e) {
      ctl = new JSONObject().put("running", false).put("tunUp", false);
    }
    boolean running = ctl.optBoolean("running", false);
    boolean tunUp = ctl.optBoolean("tunUp", false);
    JSONObject out = new JSONObject()
        .put("ok", true)
        .put("code", "CLASH_STATUS")
        .put("hasProfile", hasProfile)
        .put("profileBytes", hasProfile ? profile.length() : 0)
        .put("running", running)
        .put("tunUp", tunUp)
        .put("device", ClashConfig.TUN_DEVICE)
        .put("netnsId", ctl.optString("netnsId", ""))
        .put("subscriptionUrl", readTextIfPresent("subscription.url"))
        .put("savedNode", readTextIfPresent("selected-node.txt"))
        .put("currentNode", "")
        .put("nodes", new JSONArray());
    if (running) {
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
    HttpURLConnection conn = (HttpURLConnection) new URL("http://" + ClashConfig.CONTROLLER_ADDR + path).openConnection();
    conn.setConnectTimeout(3000);
    conn.setReadTimeout(8000);
    conn.setRequestMethod("GET");
    if (!secret.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + secret);
    try {
      int code = conn.getResponseCode();
      if (code < 200 || code >= 300) throw new Failure("CLASH_API_HTTP_" + code);
      try (InputStream in = conn.getInputStream()) {
        byte[] body = readAllLimited(in, MAX_PROFILE_BYTES);
        return new JSONObject(new String(body, StandardCharsets.UTF_8));
      }
    } finally {
      conn.disconnect();
    }
  }

  private static void controllerPut(String path, String jsonBody) throws Exception {
    String secret = readTextIfPresent("controller.secret");
    HttpURLConnection conn = (HttpURLConnection) new URL("http://" + ClashConfig.CONTROLLER_ADDR + path).openConnection();
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
      if (code < 200 || code >= 300) throw new Failure("CLASH_API_HTTP_" + code);
    } finally {
      conn.disconnect();
    }
  }

  private static JSONObject runControl(String action) throws Exception {
    java.lang.Process p = new ProcessBuilder("/system/bin/sh", CONTROL_SH, action).start();
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
