package com.smartvowificall.ims;
import android.content.*;
import android.content.pm.PackageInfo;
import android.net.*;
import android.os.Build;
import android.telephony.ims.*;
import android.telephony.ims.stub.*;
import com.smartvowificall.ims.clash.*;
import com.smartvowificall.ims.engine.*;
import com.smartvowificall.ims.network.*;
import com.smartvowificall.ims.policy.*;
import com.smartvowificall.ims.telemetry.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.*;

final class RuntimeController {
  private static RuntimeController instance;
  static synchronized RuntimeController get(Context c) {
    if (instance == null)
      instance = new RuntimeController(c.getApplicationContext());
    return instance;
  }
  final Context context;
  final RootSupervisor root;
  final SharedPreferences prefs;
  final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
  final ScheduledExecutorService policyWorker = Executors.newSingleThreadScheduledExecutor();
  final ExecutorService io = Executors.newCachedThreadPool();
  final ExecutorService binderExecutor = Executors.newCachedThreadPool();
  final PolicyLoop loop = new PolicyLoop(policyWorker, new PolicyLoop.Work() {
    public void refresh() { tick(); }
    public void poll() { pollBindings(); }
    public boolean stable() {
      return lastFullSyncSucceeded && staleSlotSince.isEmpty() && ((!bindings.isEmpty()
          && bindings.values().stream().allMatch(
              b -> b.registered && b.providerBound && featureMatches(b.sim)))
          || (!fallbackReasons.isEmpty() && bindings.isEmpty())
          || (snapshot != null && snapshot.sims.stream().noneMatch(
              s -> PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE)));
    }
    public boolean quiescent() { return !enabled() && shutdownComplete; }
  });
  final Map<Integer, Binding> bindings = new ConcurrentHashMap<>();
  volatile Map<Integer, Boolean> radioOff = Collections.emptyMap();
  final Map<Integer, Long> lastRebindNanos = new HashMap<>();
  final Map<Integer, String> lastSlotIdentity = new HashMap<>();
  final FallbackPolicy fallback = new FallbackPolicy();
  final EventRingBuffer events = new EventRingBuffer(200);
  final NetworkLogBuffer networkLogs = new NetworkLogBuffer(80);
  final Set<Integer> fallbackReleasedSlots = ConcurrentHashMap.newKeySet();
  volatile Map<Integer, String> fallbackReasons = Collections.emptyMap();
  final Map<Integer, PolicyEngine.AppliedOverride> applied = new LinkedHashMap<>();
  volatile RootSupervisor.Snapshot snapshot;
  volatile InterfaceSnapshot interfaces;
  volatile ClashState clash = ClashState.EMPTY;
  volatile String status = "等待启动";
  volatile boolean shutdownComplete, lastFullSyncSucceeded;
  private volatile boolean clashRestartPending;
  private volatile String lastAutoClashSimKey = "";
  private final java.util.concurrent.atomic.AtomicBoolean telemetryInFlight =
      new java.util.concurrent.atomic.AtomicBoolean(false);
  final Map<Integer, NewMmTelFeature> features = new ConcurrentHashMap<>();
  final Map<Integer, ImsRegistrationImplBase> registrations = new ConcurrentHashMap<>();
  RuntimeController(Context c) {
    context = c;
    root = new RootSupervisor(c);
    prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("policy", 0);
    loadJournal();
  }
  boolean enabled() {
    return prefs.getBoolean("enabled", false);
  }
  boolean billable() {
    return prefs.getBoolean("billable", false);
  }
  boolean clashEnabled() {
    return prefs.getBoolean("clash_enabled", false);
  }
  void setClashEnabled(boolean v) {
    prefs.edit().putBoolean("clash_enabled", v).commit();
    if (v) {
      fallback.reset();
      fallbackReleasedSlots.clear();
    }
    kick();
  }
  String preferredClashNode(String simKey) {
    return simKey == null || simKey.isEmpty() ? "" : prefs.getString("clash_node." + simKey, "");
  }
  private void savePreferredClashNode(String simKey, String node) {
    if (simKey == null || simKey.isEmpty() || node == null || node.isEmpty()) return;
    if (!node.equals(prefs.getString("clash_node." + simKey, "")))
      prefs.edit().putString("clash_node." + simKey, node).commit();
  }
  void retryNow() {
    policyWorker.execute(() -> {
      if (enabled()) {
        fallback.reset();
        fallbackReleasedSlots.clear();
        lastRebindNanos.clear();
      }
      events.record("I", "policy", "manual retry requested");
      kick();
    });
  }
  static final String DEFAULT_TELEMETRY_ENDPOINT =
      "https://smartvowificall-telemetry.yubainya.workers.dev/v1/telemetry";
  static final String TELEMETRY_ROUTE_WIFICALL = "wificall";
  static final String TELEMETRY_ROUTE_SYSTEM = "system";
  boolean telemetryEnabled() {
    // Strictly opt-in: disabled by default until user enables it.
    return prefs.getBoolean("telemetry_enabled", false);
  }
  void setTelemetryEnabled(boolean enabled) {
    prefs.edit().putBoolean("telemetry_enabled", enabled).commit();
    events.record("I", "telemetry", enabled ? "enabled" : "disabled");
  }
  String telemetryRoute() {
    String saved = prefs.getString("telemetry_route", TELEMETRY_ROUTE_WIFICALL);
    return TELEMETRY_ROUTE_SYSTEM.equals(saved) ? TELEMETRY_ROUTE_SYSTEM : TELEMETRY_ROUTE_WIFICALL;
  }
  void setTelemetryRoute(String route) {
    String normalized = TELEMETRY_ROUTE_SYSTEM.equals(route)
        ? TELEMETRY_ROUTE_SYSTEM : TELEMETRY_ROUTE_WIFICALL;
    prefs.edit().putString("telemetry_route", normalized).commit();
    events.record("I", "telemetry", "route=" + normalized);
  }
  String telemetryEndpoint() {
    String saved = prefs.getString("telemetry_endpoint", "");
    return saved.isEmpty() ? DEFAULT_TELEMETRY_ENDPOINT : saved;
  }
  void setTelemetryEndpoint(String url, Consumer<String> done) {
    String trimmed = url == null ? "" : url.trim();
    if (trimmed.isEmpty() || DEFAULT_TELEMETRY_ENDPOINT.equals(trimmed)) {
      prefs.edit().remove("telemetry_endpoint").commit();
      done.accept("已恢复默认遥测接口地址");
      return;
    }
    try {
      String normalized = TelemetryClient.normalizeAndValidateEndpoint(trimmed);
      prefs.edit().putString("telemetry_endpoint", normalized).commit();
      events.record("I", "telemetry", "endpoint updated");
      done.accept("已保存遥测接口地址");
    } catch (IllegalArgumentException e) {
      done.accept("接口地址无效（需 HTTPS）：" + safeCode(e));
    }
  }
  static final class TelemetryUploadRecord {
    final String logId, trigger, code, path;
    final long timestampMs;
    final boolean ok;
    final int bytes;
    TelemetryUploadRecord(String logId, long timestampMs, String trigger, boolean ok,
        String code, String path, int bytes) {
      this.logId = logId == null ? "" : logId;
      this.timestampMs = timestampMs;
      this.trigger = trigger == null ? "manual" : trigger;
      this.ok = ok;
      this.code = code == null ? "" : code;
      this.path = path == null ? "" : path;
      this.bytes = Math.max(0, bytes);
    }
  }
  List<TelemetryUploadRecord> telemetryUploadHistory() {
    List<TelemetryUploadRecord> out = new ArrayList<>();
    String raw = prefs.getString("telemetry_history_json", "");
    if (raw.isEmpty()) return out;
    try {
      JSONArray arr = new JSONArray(raw);
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o == null) continue;
        out.add(new TelemetryUploadRecord(
            o.optString("logId", ""),
            o.optLong("timestampMs", 0L),
            o.optString("trigger", "manual"),
            o.optBoolean("ok", false),
            o.optString("code", ""),
            o.optString("path", ""),
            o.optInt("bytes", 0)));
      }
    } catch (Exception ignored) {
    }
    return out;
  }
  private synchronized void recordTelemetryUpload(TelemetryUploadRecord rec) {
    List<TelemetryUploadRecord> list = new ArrayList<>();
    list.add(rec);
    for (TelemetryUploadRecord prev : telemetryUploadHistory()) {
      if (list.size() >= 20) break;
      list.add(prev);
    }
    JSONArray arr = new JSONArray();
    for (TelemetryUploadRecord item : list) {
      try {
        arr.put(new JSONObject()
            .put("logId", item.logId)
            .put("timestampMs", item.timestampMs)
            .put("trigger", item.trigger)
            .put("ok", item.ok)
            .put("code", item.code)
            .put("path", item.path)
            .put("bytes", item.bytes));
      } catch (Exception ignored) {
      }
    }
    prefs.edit().putString("telemetry_history_json", arr.toString()).commit();
  }
  String telemetryLastStatus() {
    return prefs.getString("telemetry_last_status", "");
  }
  private String telemetryInstallId() {
    String id = prefs.getString("telemetry_install_id", "");
    if (id.isEmpty()) {
      id = UUID.randomUUID().toString();
      prefs.edit().putString("telemetry_install_id", id).commit();
    }
    return id;
  }
  String buildSanitizedTelemetryJson(String trigger) {
    long now = System.currentTimeMillis();
    return buildSanitizedTelemetryJson(LogSanitizer.generateLogId(now), trigger, false);
  }
  void buildSanitizedTelemetryPreviewAsync(Consumer<String> done) {
    io.execute(() -> {
      try {
        long now = System.currentTimeMillis();
        String json = buildSanitizedTelemetryJson(LogSanitizer.generateLogId(now), "manual", true);
        String pretty;
        try {
          pretty = new JSONObject(json).toString(2);
        } catch (Exception e) {
          pretty = json;
        }
        done.accept(pretty);
      } catch (Exception e) {
        done.accept("生成预览失败：" + safeCode(e));
      }
    });
  }
  private List<Map<String, Object>> collectCombinedNetworkLogs(boolean includeRootTrace) {
    List<Map<String, Object>> merged = new ArrayList<>();
    if (includeRootTrace) {
      try {
        String lines = root.collectClashHttpTrace();
        if (lines != null && !lines.isEmpty()) {
          for (String line : lines.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
              merged.add(JsonCodec.decodeObject(trimmed));
            } catch (Exception ignored) {
            }
          }
        }
      } catch (Exception ignored) {
      }
    }
    merged.addAll(networkLogs.snapshot(80));
    merged.sort(Comparator.comparing(m -> String.valueOf(m.getOrDefault("ts", ""))));
    if (merged.size() > 100) {
      return new ArrayList<>(merged.subList(merged.size() - 100, merged.size()));
    }
    return merged;
  }
  private List<Map<String, Object>> collectSanitizedClashConnections() {
    List<Map<String, Object>> out = new ArrayList<>();
    if (!clash.running) return out;
    try {
      JSONObject raw = root.clashConnections();
      JSONArray arr = raw.optJSONArray("connections");
      if (arr != null) {
        int limit = Math.min(arr.length(), 60);
        for (int i = 0; i < limit; i++) {
          JSONObject o = arr.optJSONObject(i);
          if (o == null) continue;
          try {
            out.add(JsonCodec.decodeObject(o.toString()));
          } catch (Exception ignored) {
          }
        }
      }
    } catch (Exception ignored) {
    }
    return out;
  }
  String buildSanitizedTelemetryJson(String logId, String trigger, boolean includeRootLogs) {
    String versionName = "";
    int versionCode = 0;
    try {
      PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      versionName = pi.versionName == null ? "" : pi.versionName;
      versionCode = (int) pi.getLongVersionCode();
    } catch (Exception ignored) {
    }
    List<String> supportedAbis = Build.SUPPORTED_ABIS == null
        ? Collections.emptyList() : Arrays.asList(Build.SUPPORTED_ABIS);
    String abi = supportedAbis.isEmpty() ? "" : supportedAbis.get(0);
    LogSanitizer.DeviceInfo dev = collectDeviceInfo(abi, supportedAbis);
    ClashState cs = clash;
    LogSanitizer.ClashTelemetry clashTel = new LogSanitizer.ClashTelemetry(
        clashEnabled(), cs.hasProfile, cs.running, cs.tunUp, cs.nodes.size(),
        cs.externalLan, cs.controllerPort, cs.netnsId, cs.lastError);
    List<LogSanitizer.SimTelemetry> simRows = new ArrayList<>();
    RootSupervisor.Snapshot snap = snapshot;
    InterfaceSnapshot inv = interfaces;
    if (snap != null) {
      for (RootSupervisor.Sim s : snap.sims) {
        String sel = selection(s.policy.simKey);
        boolean isTun = false;
        if (inv != null && !sel.isEmpty()) {
          InterfaceSnapshot.Selection resolved = inv.selection(sel);
          isTun = resolved.entry != null && resolved.entry.voWifiTun();
        }
        Binding b = bindings.get(s.policy.slotId);
        if (!bindingMatches(b, s)) b = null;
        simRows.add(new LogSanitizer.SimTelemetry(
            s.policy.slotId,
            s.policy.subId,
            s.phoneId,
            s.policy.ready,
            s.simState,
            s.rawSimState,
            s.identityCode,
            s.identityIssues,
            s.countryIso,
            s.carrierName,
            s.simOperatorName,
            s.networkCountryIso,
            s.networkOperatorName,
            s.networkPlmn,
            s.policy.homePlmn,
            s.carrierId,
            s.embedded,
            s.opportunistic,
            LogSanitizer.classifyEgress(sel, isTun),
            sel,
            !preferredClashNode(s.policy.simKey).isEmpty(),
            Boolean.TRUE.equals(radioOff.get(s.policy.slotId)),
            featureMatches(s),
            b != null ? b.ims : "IDLE",
            b != null && b.registered,
            b != null ? b.tunnel : "IDLE",
            b != null && b.callActive,
            b != null ? b.callSnapshotError : 0L,
            b != null && Boolean.TRUE.equals(b.voiceServiceOverride.lastApplied()),
            b != null && b.providerBound,
            b != null && b.providerDeferred,
            fallbackReasons.getOrDefault(s.policy.slotId, "")));
      }
    }
    LogSanitizer.ServiceTelemetry svc = new LogSanitizer.ServiceTelemetry(
        enabled(), billable(), status, shutdownComplete, root.commandCount.get(), clashTel, simRows);
    Map<String, Object> diagMap = null;
    try {
      diagMap = JsonCodec.decodeObject(diagnostics().toString());
    } catch (Exception ignored) {
    }
    List<Map<String, Object>> ifaceList = new ArrayList<>();
    if (inv != null) {
      for (InterfaceSnapshot.Entry e : inv.entries.values()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.name);
        m.put("up", e.up);
        m.put("available", e.available());
        m.put("type", e.type());
        m.put("voWifiTun", e.voWifiTun());
        m.put("owners", new ArrayList<>(e.ownerPackages));
        m.put("addresses", new ArrayList<>(e.addresses));
        ifaceList.add(m);
      }
    }
    List<Map<String, Object>> netLogs = collectCombinedNetworkLogs(includeRootLogs);
    List<Map<String, Object>> clashConns =
        includeRootLogs ? collectSanitizedClashConnections() : Collections.emptyList();
    String rootLogsText = "";
    if (includeRootLogs) {
      try {
        rootLogsText = root.collectRootLogs();
      } catch (Exception e) {
        rootLogsText = "ROOT_LOG_COLLECTION_FAILED: " + safeCode(e);
      }
    }
    return LogSanitizer.buildTelemetryPayload(
        logId, telemetryInstallId(), trigger, System.currentTimeMillis(), versionName, versionCode,
        dev, svc, diagMap, ifaceList, events.snapshot(120), netLogs, clashConns, rootLogsText);
  }
  void uploadTelemetryNow(String trigger, Consumer<String> done) {
    io.execute(() -> {
      if (!telemetryInFlight.compareAndSet(false, true)) {
        done.accept("遥测正在上传中，请稍候");
        return;
      }
      long now = System.currentTimeMillis();
      String logId = LogSanitizer.generateLogId(now);
      try {
        String endpoint = telemetryEndpoint();
        if (endpoint.isEmpty()) {
          done.accept("请先填写 Cloudflare Worker 遥测接口地址");
          return;
        }
        String route = telemetryRoute();
        String routeLabel = TELEMETRY_ROUTE_WIFICALL.equals(route) ? "Wi-Fi Calling 链路" : "系统路由";
        String payload = buildSanitizedTelemetryJson(logId, trigger, true);
        TelemetryClient.UploadResult result = TELEMETRY_ROUTE_WIFICALL.equals(route)
            ? root.uploadTelemetryViaWifiCall(endpoint, logId, payload)
            : TelemetryClient.upload(endpoint, payload, "", logId);
        String finalLogId = result.logId.isEmpty() ? logId : result.logId;
        String summary = result.ok
            ? "上传成功（" + routeLabel + "）· ID: " + finalLogId
            : "上传失败（" + result.code + " · " + routeLabel + "）· ID: " + finalLogId;
        prefs.edit()
            .putLong("telemetry_last_upload_ms", System.currentTimeMillis())
            .putString("telemetry_last_status", summary)
            .commit();
        recordTelemetryUpload(new TelemetryUploadRecord(
            finalLogId, System.currentTimeMillis(), trigger, result.ok,
            result.code, result.recordPath, result.payloadBytes));
        Map<String, Object> reqHdrs = new LinkedHashMap<>();
        reqHdrs.put("Content-Type", "application/json; charset=utf-8");
        reqHdrs.put("X-SmartVoWifiCall-Schema", LogSanitizer.SCHEMA);
        reqHdrs.put("X-SmartVoWifiCall-Log-Id", finalLogId);
        Map<String, Object> reqSummary = new LinkedHashMap<>();
        reqSummary.put("schema", LogSanitizer.SCHEMA);
        reqSummary.put("logId", finalLogId);
        reqSummary.put("trigger", trigger);
        reqSummary.put("route", route);
        reqSummary.put("payloadBytes", result.payloadBytes);
        networkLogs.recordHttp("telemetry_http", "POST", endpoint, result.httpStatus,
            result.durationMs, reqHdrs, reqSummary, result.responseHeaders, result.responseBody,
            result.ok ? null : result.code);
        events.record(result.ok ? "I" : "W", "telemetry",
            "upload " + trigger + " route=" + route + " id=" + finalLogId + " " + result.code);
        done.accept(summary);
      } catch (Exception e) {
        String code = safeCode(e);
        String hint = "WIFICALL_LINK_UNAVAILABLE".equals(code)
            ? "Wi-Fi Calling 链路未就绪，请先开启内置代理或切换为系统路由"
            : "上传失败（" + code + "）· ID: " + logId;
        prefs.edit().putString("telemetry_last_status", hint).commit();
        recordTelemetryUpload(new TelemetryUploadRecord(
            logId, System.currentTimeMillis(), trigger, false, code, "", 0));
        events.record("W", "telemetry", "upload failed id=" + logId + ": " + code);
        done.accept(hint);
      } finally {
        telemetryInFlight.set(false);
      }
    });
  }
  private void maybeAutoUploadTelemetry(boolean callsActive) {
    if (callsActive || !telemetryEnabled()) return;
    if (telemetryEndpoint().isEmpty()) return;
    long last = prefs.getLong("telemetry_last_upload_ms", 0L);
    if (System.currentTimeMillis() - last < TimeUnit.HOURS.toMillis(6)) return;
    uploadTelemetryNow("periodic", ignored -> {});
  }
  void exportFullLogs(boolean sanitizedOnly, OutputStream out, Consumer<String> done) {
    io.execute(() -> {
      try (ZipOutputStream zip = new ZipOutputStream(out)) {
        String modeNote = sanitizedOnly
            ? "SmartVoWifiCall Sanitized Log Bundle (private fields replaced with [Hidden])\n"
            : "SmartVoWifiCall Raw Full Log Bundle (CONTAINS SENSITIVE DEVICE/SIM DATA - DO NOT SHARE PUBLICLY)\n";
        writeZipEntry(zip, "README.txt", modeNote);
        long now = System.currentTimeMillis();
        String logId = LogSanitizer.generateLogId(now);
        writeZipEntry(zip, "telemetry-sanitized.json",
            buildSanitizedTelemetryJson(logId, "manual", true) + "\n");
        String diag = diagnostics().toString(2) + "\n";
        writeZipEntry(zip, "diagnostics.json",
            sanitizedOnly ? LogSanitizer.sanitizeText(diag) : diag);
        writeZipEntry(zip, "network-logs.json",
            JsonCodec.encode(collectCombinedNetworkLogs(true)) + "\n");
        writeZipEntry(zip, "app-events.log", events.formatAll(sanitizedOnly));
        String rootLogs;
        try {
          rootLogs = root.collectRootLogs();
        } catch (Exception e) {
          rootLogs = "ROOT_LOG_COLLECTION_FAILED: " + safeCode(e) + "\n";
        }
        writeZipEntry(zip, "root-logs.txt",
            sanitizedOnly ? LogSanitizer.sanitizeText(rootLogs) : rootLogs);
        zip.finish();
        events.record("I", "export", sanitizedOnly ? "exported sanitized zip" : "exported raw zip");
        done.accept(sanitizedOnly ? "已导出脱敏日志包（ZIP）" : "已导出全部原始日志包（ZIP）");
      } catch (Exception e) {
        events.record("W", "export", "failed: " + safeCode(e));
        done.accept("导出日志失败：" + safeCode(e));
      }
    });
  }
  private LogSanitizer.DeviceInfo collectDeviceInfo(String abi, List<String> supportedAbis) {
    String marketName = firstNonEmptyProp(
        "ro.product.marketname",
        "ro.product.vendor.marketname",
        "ro.product.odm.marketname",
        "ro.config.marketing_name",
        "ro.vendor.oplus.market.name");
    String certModel = firstNonEmptyProp(
        "ro.product.cert",
        "ro.product.vendor.cert",
        "ro.product.odm.cert");
    if (certModel.isEmpty()) certModel = Build.MODEL == null ? "" : Build.MODEL;

    String socManufacturer = "";
    String socModel = "";
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      socManufacturer = Build.SOC_MANUFACTURER == null ? "" : Build.SOC_MANUFACTURER;
      socModel = Build.SOC_MODEL == null ? "" : Build.SOC_MODEL;
    }
    if (socManufacturer.isEmpty() || "unknown".equalsIgnoreCase(socManufacturer)) {
      socManufacturer = firstNonEmptyProp("ro.soc.manufacturer");
    }
    if (socModel.isEmpty() || "unknown".equalsIgnoreCase(socModel)) {
      socModel = firstNonEmptyProp("ro.soc.model", "ro.hardware.chipname", "ro.board.platform");
    }

    long totalRamMb = 0L;
    long availRamMb = 0L;
    try {
      android.app.ActivityManager am = context.getSystemService(android.app.ActivityManager.class);
      if (am != null) {
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        totalRamMb = mi.totalMem / (1024L * 1024L);
        availRamMb = mi.availMem / (1024L * 1024L);
      }
    } catch (Exception ignored) {
    }

    String screenResolution = "";
    int screenDensityDpi = 0;
    try {
      android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
      if (dm != null) screenDensityDpi = dm.densityDpi;
      android.view.WindowManager wm = context.getSystemService(android.view.WindowManager.class);
      if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.graphics.Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        if (bounds.width() > 0 && bounds.height() > 0) {
          screenResolution = bounds.width() + "x" + bounds.height();
        }
      }
      if (screenResolution.isEmpty() && dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
        screenResolution = dm.widthPixels + "x" + dm.heightPixels;
      }
    } catch (Exception ignored) {
    }

    String osVersion = collectVendorOsVersion();
    String baseband = "";
    try {
      String r = Build.getRadioVersion();
      if (r != null && !r.trim().isEmpty()) baseband = r.trim();
    } catch (Exception ignored) {
    }
    if (baseband.isEmpty()) {
      baseband = firstNonEmptyProp("gsm.version.baseband", "ro.baseband");
    }

    String hardwareRevision = firstNonEmptyProp(
        "ro.boot.hardware.revision",
        "ro.boot.hwversion",
        "ro.hardware.revision",
        "ro.boot.hwlevel");
    String hardwareRegion = firstNonEmptyProp("ro.boot.hwc", "ro.miui.region");

    String kernelFull = "";
    try {
      java.io.File procVer = new java.io.File("/proc/version");
      if (procVer.isFile()) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
            new java.io.InputStreamReader(new java.io.FileInputStream(procVer), StandardCharsets.UTF_8))) {
          String line = br.readLine();
          if (line != null) kernelFull = line.trim();
        }
      }
    } catch (Exception ignored) {
    }

    return new LogSanitizer.DeviceInfo(
        Build.MANUFACTURER,
        Build.BRAND,
        Build.MODEL,
        marketName,
        certModel,
        Build.DEVICE,
        Build.PRODUCT,
        Build.HARDWARE,
        Build.BOARD,
        socManufacturer,
        socModel,
        Runtime.getRuntime().availableProcessors(),
        totalRamMb,
        availRamMb,
        screenResolution,
        screenDensityDpi,
        osVersion,
        Build.DISPLAY,
        Build.VERSION.SDK_INT,
        Build.VERSION.RELEASE,
        Build.VERSION.SECURITY_PATCH,
        Build.VERSION.INCREMENTAL,
        baseband,
        hardwareRevision,
        hardwareRegion,
        abi,
        supportedAbis,
        Locale.getDefault().toLanguageTag(),
        TimeZone.getDefault().getID(),
        System.getProperty("os.version", ""),
        kernelFull,
        Build.FINGERPRINT);
  }
  private static String collectVendorOsVersion() {
    String miOsInc = sysProp("ro.mi.os.version.incremental");
    String miOsName = sysProp("ro.mi.os.version.name");
    if (!miOsInc.isEmpty()) {
      return miOsName.isEmpty() ? "HyperOS " + miOsInc : "HyperOS " + miOsName + " (" + miOsInc + ")";
    }
    String miui = sysProp("ro.miui.ui.version.name");
    if (!miui.isEmpty()) {
      String inc = Build.VERSION.INCREMENTAL == null ? "" : Build.VERSION.INCREMENTAL;
      return inc.isEmpty() ? "MIUI " + miui : "MIUI " + miui + " (" + inc + ")";
    }
    String oplus = firstNonEmptyProp("ro.build.version.oplusrom", "ro.build.version.opporom");
    if (!oplus.isEmpty()) return "ColorOS " + oplus;
    String vivo = sysProp("ro.vivo.os.build.display.id");
    if (!vivo.isEmpty()) return vivo;
    String magic = sysProp("ro.build.version.magic");
    if (!magic.isEmpty()) return "MagicOS " + magic;
    String harmony = sysProp("hw_sc.build.platform.version");
    if (!harmony.isEmpty()) return "HarmonyOS " + harmony;
    String emui = sysProp("ro.build.version.emui");
    if (!emui.isEmpty()) return emui;
    String oneUi = sysProp("ro.build.version.oneui");
    if (!oneUi.isEmpty()) return "OneUI " + oneUi;
    return Build.DISPLAY == null ? "" : Build.DISPLAY;
  }
  private static String firstNonEmptyProp(String... keys) {
    for (String k : keys) {
      String v = sysProp(k);
      if (!v.isEmpty() && !"unknown".equalsIgnoreCase(v)) return v;
    }
    return "";
  }
  private static String sysProp(String key) {
    try {
      String v = (String) Class.forName("android.os.SystemProperties")
          .getMethod("get", String.class, String.class)
          .invoke(null, key, "");
      return v == null ? "" : v.trim();
    } catch (Exception ignored) {
      return "";
    }
  }
  private static void writeZipEntry(ZipOutputStream zip, String name, String content) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
    zip.write(bytes);
    zip.closeEntry();
  }
  void useBuiltinTunForForeignSims() {
    RootSupervisor.Snapshot snap = snapshot;
    if (snap == null) return;
    SharedPreferences.Editor ed = prefs.edit();
    for (RootSupervisor.Sim s : snap.sims) {
      if (PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE)
        ed.putString("selected." + s.policy.simKey, ClashConfig.TUN_DEVICE);
    }
    if (clash.hasProfile) ed.putBoolean("clash_enabled", true);
    ed.commit();
    fallback.reset();
    kick();
  }
  void clashFetchSubscription(String url, Consumer<String> done) {
    io.execute(() -> {
      try {
        JSONObject r = root.clashFetchSubscription(url);
        prefs.edit().putBoolean("clash_enabled", true).commit();
        applyClashUpdate(done, "订阅已更新");
      } catch (Exception e) {
        done.accept("拉取失败：" + safeCode(e));
      }
    });
  }
  void clashSaveProfile(String yaml, Consumer<String> done) {
    io.execute(() -> {
      try {
        JSONObject r = root.clashSaveProfile(yaml);
        prefs.edit().putBoolean("clash_enabled", true).commit();
        applyClashUpdate(done, "配置已导入");
      } catch (Exception e) {
        done.accept("导入失败：" + safeCode(e));
      }
    });
  }
  void clashReloadLocal(Consumer<String> done) {
    io.execute(() -> {
      try {
        JSONObject r = root.clashRebuild();
        applyClashUpdate(done, "配置已重载");
      } catch (Exception e) {
        done.accept("重载失败：" + safeCode(e));
      }
    });
  }
  void clashSelectNode(String node, Consumer<String> done) {
    policyWorker.execute(() -> {
      try {
        root.clashSelectNode(node);
        RootSupervisor.Snapshot snap = snapshot;
        if (snap != null) {
          for (RootSupervisor.Sim s : snap.sims) {
            if (PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE
                && ClashConfig.TUN_DEVICE.equals(selection(s.policy.simKey))) {
              savePreferredClashNode(s.policy.simKey, node);
              lastAutoClashSimKey = s.policy.simKey;
            }
          }
        }
        boolean busy = (snap != null && snap.callsActive)
            || bindings.values().stream().anyMatch(b -> b.callActive);
        if (!busy) {
          // Drop stale UDP/IKE flows pinned to the old node, clear fallback backoff, and recycle
          // wfctun0 bindings so the engine dials fresh immediately instead of sitting in BACKOFF.
          try { root.clashCloseAllConnections(); } catch (Exception ignored) {}
          fallback.reset();
          fallbackReleasedSlots.clear();
          for (Binding b : new ArrayList<>(bindings.values())) {
            if (ClashConfig.TUN_DEVICE.equals(b.iface)) {
              try { clearSlot(b.slot()); } catch (Exception ignored) {}
            }
          }
        }
        refreshClashState();
        kick();
        done.accept("已切换出口节点：" + node);
      } catch (Exception e) {
        done.accept("切换失败：" + safeCode(e));
      }
    });
  }
  void clashTestDelay(Consumer<String> done) {
    io.execute(() -> {
      try {
        JSONObject r = root.clashTestDelay();
        JSONObject delays = r.optJSONObject("delays");
        int okCount = delays == null ? 0 : delays.length();
        refreshClashState();
        done.accept("测速完成（可用节点 " + okCount + " 个）");
      } catch (Exception e) {
        done.accept("测速失败：" + safeCode(e));
      }
    });
  }
  private void applyClashUpdate(Consumer<String> done, String message) {
    policyWorker.execute(() -> {
      clashRestartPending = true;
      fallback.reset();
      fallbackReleasedSlots.clear();
      refreshClashState();
      kick();
      done.accept(message + (enabled() ? "" : "，开启服务后生效"));
    });
  }
  void clashRestartCore(Consumer<String> done) {
    applyClashUpdate(done, "已安排重启，通话中将稍后执行");
  }
  void clashSetController(boolean externalLan, int port, Consumer<String> done) {
    policyWorker.execute(() -> {
      try {
        if (snapshot == null || snapshot.callsActive
            || bindings.values().stream().anyMatch(b -> b.callActive))
          throw new IllegalStateException("CALL_ACTIVE_OR_UNKNOWN");
        root.clashSetController(externalLan, port);
        refreshClashState();
        kick();
        done.accept(externalLan
            ? "外部控制已开启（0.0.0.0:" + port + "）"
            : "外部控制已限制为仅本机（127.0.0.1:" + port + "）");
      } catch (Exception e) {
        done.accept("保存外部控制失败：" + safeCode(e));
      }
    });
  }
  void clashRotateSecret(Consumer<String> done) {
    policyWorker.execute(() -> {
      try {
        if (snapshot == null || snapshot.callsActive
            || bindings.values().stream().anyMatch(b -> b.callActive))
          throw new IllegalStateException("CALL_ACTIVE_OR_UNKNOWN");
        root.clashRotateSecret();
        refreshClashState();
        kick();
        done.accept("控制器密钥已重置并生效");
      } catch (Exception e) {
        done.accept("重置密钥失败：" + safeCode(e));
      }
    });
  }
  void clashFetchConnections(Consumer<ConnectionsSnapshot> done) {
    io.execute(() -> {
      try {
        done.accept(ConnectionsSnapshot.fromJson(root.clashConnections(), ""));
      } catch (Exception e) {
        done.accept(ConnectionsSnapshot.error(safeCode(e)));
      }
    });
  }
  void clashCloseConnection(String id, Consumer<String> done) {
    io.execute(() -> {
      try {
        root.clashCloseConnection(id);
        done.accept("已断开该连接");
      } catch (Exception e) {
        done.accept("断开失败：" + safeCode(e));
      }
    });
  }
  void clashCloseAllConnections(Consumer<String> done) {
    io.execute(() -> {
      try {
        root.clashCloseAllConnections();
        done.accept("已断开全部活跃连接");
      } catch (Exception e) {
        done.accept("断开失败：" + safeCode(e));
      }
    });
  }
  void refreshClashState() {
    try {
      clash = ClashState.fromJson(root.clashStatus(), "");
    } catch (Exception e) {
      clash = clash.withError(safeCode(e));
    }
  }
  private void syncClash(boolean callsActive) {
    try {
      ClashState current = ClashState.fromJson(root.clashStatus(), "");
      if (ServiceLifecycle.shouldStartProxy(enabled(), clashEnabled(), current.hasProfile,
          callsActive, current.running && current.tunUp, clashRestartPending)) {
        if (clashRestartPending && current.running) root.clashRestart();
        else root.clashStart();
        clashRestartPending = false;
        current = ClashState.fromJson(root.clashStatus(), "");
      }
      // Stop only after the policy has released all dependent sessions and radios.
      clash = current;
    } catch (Exception e) {
      clash = clash.withError(safeCode(e));
    }
  }
  boolean radioReady(Binding binding) {
    return binding != null && Boolean.TRUE.equals(radioOff.get(binding.slot()));
  }
  void verifyRadioOff(Binding binding) throws Exception {
    if (!enabled() || !radioReady(binding)) throw new IllegalStateException("CELLULAR_RADIO_NOT_OFF");
    root.verifyRadioOff(binding.sim);
  }
  void setEnabled(boolean v) {
    SharedPreferences.Editor edit = prefs.edit().putBoolean("enabled", v);
    if (v) edit.putBoolean("clash_enabled", true);
    edit.commit();
    shutdownComplete = false;
    fallback.reset();
    fallbackReleasedSlots.clear();
    if (v) lastAutoClashSimKey = "";
    kick();
  }
  void select(String key, String iface) {
    SharedPreferences.Editor ed = prefs.edit().putString("selected." + key, iface);
    if (ClashConfig.TUN_DEVICE.equals(iface) && clash.hasProfile)
      ed.putBoolean("clash_enabled", true);
    ed.commit();
    fallback.reset();
    kick();
  }
  String selection(String key) {
    return prefs.getString("selected." + key, "");
  }
  void setBillable(boolean v) {
    policyWorker.execute(() -> {
      if (snapshot == null || snapshot.callsActive
          || bindings.values().stream().anyMatch(b -> b.callActive))
        return;
      prefs.edit().putBoolean("billable", v).commit();
      for (Binding b : bindings.values()) b.close();
      bindings.clear();
      kick();
    });
  }
  void start() {
    loop.start();
  }
  void kick() {
    loop.kick();
  }
  ImsRegistrationImplBase registration(int slot) {
    return registrations.computeIfAbsent(
        slot, s -> new ImsRegistrationImplBase(binderExecutor));
  }
  ImsRegistrationImplBase registrationFor(RootSupervisor.Sim sim) {
    return registration(sim.policy.slotId);
  }
  boolean eligible(int slot) {
    RootSupervisor.Snapshot s = snapshot;
    return s != null
        && s.sims.stream().anyMatch(x
            -> x.policy.slotId == slot
                && PolicyEngine.eligibility(x.policy) == PolicyEngine.Reason.ELIGIBLE);
  }
  boolean matchesSubscription(int slot, int sub) {
    RootSupervisor.Snapshot current = snapshot;
    return current != null
        && current.sims.stream().anyMatch(s
            -> s.policy.slotId == slot && s.policy.subId == sub && s.policy.ready
                && PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE);
  }
  void loadJournal() {
    try {
      JSONArray a = new JSONArray(prefs.getString("journal", "[]"));
      for (int i = 0; i < a.length(); i++) {
        JSONObject j = a.getJSONObject(i);
        int slot = j.getInt("slot");
        applied.put(slot,
            new PolicyEngine.AppliedOverride(
                j.getString("key"), j.getInt("sub"), slot, j.getString("iface")));
      }
    } catch (Exception e) {
      status = "绑定记录读取失败";
    }
  }
  void saveJournal() throws Exception {
    JSONArray a = new JSONArray();
    for (PolicyEngine.AppliedOverride x : applied.values())
      a.put(new JSONObject()
                .put("slot", x.slotId)
                .put("sub", x.subId)
                .put("key", x.simKey)
                .put("iface", x.interfaceName));
    if (!prefs.edit().putString("journal", a.toString()).commit())
      throw new java.io.IOException("JOURNAL_WRITE_FAILED");
  }
  void tick() {
    if (!enabled() && shutdownComplete) return;
    lastFullSyncSucceeded = false;
    try {
      snapshot = root.snapshot();
      if (!enabled()) {
        pollBindings();
        boolean stopped = ServiceLifecycle.stopWhenIdle(
            snapshot.callsActive || bindings.values().stream().anyMatch(b -> b.callActive),
            new ServiceLifecycle.Shutdown() {
              public void clearSessions() throws Exception {
                Set<Integer> slots = new HashSet<>(applied.keySet());
                slots.addAll(bindings.keySet());
                for (int slot : slots) clearSlot(slot);
              }
              public void restoreIms() throws Exception { root.restoreAll(); }
              public void releaseRadios() throws Exception { root.releaseRadios(); }
              public void stopProxy() throws Exception { root.clashStop(); }
            });
        if (!stopped) {
          status = "通话结束后关闭";
          return;
        }
        refreshClashState();
        radioOff = Collections.emptyMap();
        fallbackReasons = Collections.emptyMap();
        fallbackReleasedSlots.clear();
        staleSlotSince.clear();
        lastSlotIdentity.clear();
        lastAutoClashSimKey = "";
        shutdownComplete = true;
        lastFullSyncSucceeded = true;
        status = "已关闭";
        return;
      }
      shutdownComplete = false;
      boolean busyPre = snapshot.callsActive || bindings.values().stream().anyMatch(b -> b.callActive);
      syncClash(busyPre);
      interfaces =
          new InterfaceInventory(context, () -> root.run(InterfaceParser.ROOT_LISTING_SCRIPT), root.vpnOwners())
              .read();
      for (RootSupervisor.Sim s : snapshot.sims) {
        if (!prefs.contains("selected." + s.policy.simKey)
            && interfaces.preferredVpnInterface != null
            && PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE)
          prefs.edit()
              .putString("selected." + s.policy.simKey, interfaces.preferredVpnInterface)
              .commit();
      }
      pollBindings();
      boolean calls =
          snapshot.callsActive || bindings.values().stream().anyMatch(b -> b.callActive);
      if (!calls) {
        for (Binding b : new ArrayList<>(bindings.values())) {
          if (ClashConfig.TUN_DEVICE.equals(b.iface)
              && (!clash.tunUp || clash.netnsId.isEmpty() || !clash.netnsId.equals(b.clashNetnsId))) {
            clearSlot(b.slot());
          }
        }
        // Handle eSIM profile switch BEFORE fallback evaluation and radio-hold so the new profile
        // never inherits the previous profile's dead binding, registration timeout, or backoff.
        for (RootSupervisor.Sim sim : snapshot.sims) {
          if (PolicyEngine.eligibility(sim.policy) != PolicyEngine.Reason.ELIGIBLE) continue;
          int slotId = sim.policy.slotId;
          String identity = sim.policy.subId + ":" + sim.policy.simKey;
          String prev = lastSlotIdentity.put(slotId, identity);
          Binding existing = bindings.get(slotId);
          PolicyEngine.AppliedOverride prevApplied = applied.get(slotId);
          boolean switched = (prev != null && !prev.equals(identity))
              || (existing != null && !bindingMatches(existing, sim))
              || (prevApplied != null && (!prevApplied.simKey.equals(sim.policy.simKey)
                  || prevApplied.subId != sim.policy.subId));
          if (switched) {
            events.record("I", "esim", "slot " + slotId + " profile switched plmn=" + sim.policy.homePlmn);
            fallback.resetSlot(slotId);
            fallbackReleasedSlots.remove(slotId);
            lastRebindNanos.remove(slotId);
            if (existing != null || prevApplied != null) {
              clearSlot(slotId, true);
            }
          }
        }
        syncPerSimClashNode();
      }
      Map<Integer, Boolean> nextRadioOff = new HashMap<>();
      if (enabled()) {
        // Fallback first: a SIM whose VoWiFi path is unusable must not keep its cellular radio off.
        long fallbackNow = System.nanoTime();
        Map<Integer, String> blocked = new HashMap<>();
        Set<Integer> releaseSlots = new HashSet<>();
        for (RootSupervisor.Sim sim : snapshot.sims) {
          if (PolicyEngine.eligibility(sim.policy) != PolicyEngine.Reason.ELIGIBLE) continue;
          int slotId = sim.policy.slotId;
          boolean held = Boolean.TRUE.equals(radioOff.get(slotId))
              || snapshot.heldRadioSlots.contains(slotId);
          Binding current = bindings.get(slotId);
          if (!bindingMatches(current, sim)) current = null;
          String sel = selection(sim.policy.simKey);
          boolean clashExplicitlyOff = ClashConfig.TUN_DEVICE.equals(sel) && !clashEnabled();
          String block = fallback.evaluate(slotId, calls, held && !clashExplicitlyOff,
              !sel.isEmpty(), clashExplicitlyOff ? FallbackPolicy.Egress.UNAVAILABLE : egressOf(sim), current != null,
              current != null && current.registered, fallbackNow);
          if (block != null) {
            blocked.put(slotId, block);
            if (held || !fallbackReleasedSlots.contains(slotId)) releaseSlots.add(slotId);
          }
        }
        fallbackReasons = Collections.unmodifiableMap(blocked);
        for (RootSupervisor.Sim sim : snapshot.sims) {
          if (PolicyEngine.eligibility(sim.policy) != PolicyEngine.Reason.ELIGIBLE) continue;
          int slotId = sim.policy.slotId;
          if (blocked.containsKey(slotId)) {
            if (!calls) {
              if (bindings.containsKey(slotId) || applied.containsKey(slotId)) {
                clearSlot(slotId);
              }
              deregisterAndRestoreSlot(sim);
            }
            continue;
          }
          fallbackReleasedSlots.remove(slotId);
          boolean off = root.holdRadio(sim);
          nextRadioOff.put(slotId, off);
          // Reserve this card's ordinary IMS provider even while TUN/registration is absent.
          // Also re-nudge ImsResolver if it queried empty features before our first snapshot
          // or still holds a stale MmTelFeature from the previous eSIM profile.
          long now = System.nanoTime();
          Long previousRebind = lastRebindNanos.get(slotId);
          boolean needsFeature = !featureMatches(sim)
              && (previousRebind == null || now - previousRebind >= TimeUnit.SECONDS.toNanos(15));
          boolean alreadyBound = RegistrationRouting.OWN_PACKAGE.equals(root.currentOverride(slotId));
          if (off && !calls && (!alreadyBound || needsFeature)) {
            lastRebindNanos.put(slotId, now);
            if (alreadyBound && needsFeature) {
              // Xiaomi's ImsServiceController ignores updates when mImsFeatures is unchanged
              // after process death/force-stop or eSIM profile switch; cycle through restore first.
              try {
                root.restore(slotId, prefs.getString("previous." + slotId, ""));
              } catch (Exception ignored) {}
            }
            root.bind(sim);
          }
        }
        if (!releaseSlots.isEmpty() && !calls) {
          for (int slotId : releaseSlots) {
            root.releaseRadio(slotId);
            fallbackReleasedSlots.add(slotId);
          }
        }
        releaseStaleSlots(nextRadioOff, blocked.keySet(), calls);
      } else {
        fallbackReasons = Collections.emptyMap();
        fallbackReleasedSlots.clear();
        staleSlotSince.clear();
      }
      radioOff = Collections.unmodifiableMap(nextRadioOff);
      SmartVoWifiImsService.refreshFeatures();
      Map<String, String> selected = new HashMap<>();
      List<PolicyEngine.Sim> sims = new ArrayList<>();
      for (RootSupervisor.Sim s : snapshot.sims) {
        selected.put(s.policy.simKey, selection(s.policy.simKey));
        if (enabled() && Boolean.TRUE.equals(radioOff.get(s.policy.slotId)))
          sims.add(s.policy);
      }
      PolicyEngine.Plan plan = PolicyEngine.evaluate(sims, selected, interfaces, applied, calls);
      if (plan.deferredForCall) {
        status = "通话期间暂缓切换";
        return;
      }
      boolean candidateStarted = false;
      for (PolicyEngine.Action action : plan.actions) {
        if (action.kind == PolicyEngine.Kind.CLEAR) {
          clear(action);
          continue;
        }
        RootSupervisor.Sim sim =
            snapshot.sims.stream()
                .filter(s
                    -> s.policy.subId == action.subId && s.policy.slotId == action.slotId
                        && s.policy.simKey.equals(action.simKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("STALE_SIM"));
        String previous = root.currentOverride(action.slotId);
        if (!previous.isEmpty() && !previous.equals("com.smartvowificall.ims"))
          throw new java.io.IOException("OTHER_CARRIER_OVERRIDE_PRESENT");
        // Persist intent before changing the remote state so process death remains recoverable.
        if (!prefs.edit()
                 .putString("previous." + action.slotId,
                     previous.equals("com.smartvowificall.ims") ? "" : previous)
                 .putString("iccid." + action.slotId, sim.iccid)
                 .commit())
          throw new java.io.IOException("JOURNAL_WRITE_FAILED");
        applied.put(action.slotId, action.asAppliedOverride());
        try {
          saveJournal();
        } catch (Exception e) {
          applied.remove(action.slotId);
          throw e;
        }
        try {
          root.start(sim, action.interfaceName);
          connect(sim, action.interfaceName,
              ClashConfig.TUN_DEVICE.equals(action.interfaceName) ? clash.netnsId : "");
          candidateStarted = true;
        } catch (Exception failure) {
          clear(action);
          throw failure;
        }
      }
      // Reconnect persisted sessions after Android restarts the application process.
      for (PolicyEngine.AppliedOverride x : new ArrayList<>(applied.values())) {
        RootSupervisor.Sim sim =
            snapshot.sims.stream()
                .filter(s
                    -> s.policy.simKey.equals(x.simKey) && s.policy.subId == x.subId
                        && s.policy.slotId == x.slotId)
                .findFirst()
                .orElse(null);
        if (sim != null && !bindings.containsKey(x.slotId) && !calls) {
          try {
            root.start(sim, x.interfaceName);
            connect(sim, x.interfaceName,
                ClashConfig.TUN_DEVICE.equals(x.interfaceName) ? clash.netnsId : "");
            candidateStarted = true;
          } catch (Exception failure) {
            clearSlot(x.slotId);
            throw failure;
          }
        }
      }
      if (candidateStarted) pollBindings();
      synchronizeProviders();
      if (!clashEnabled() && (clash.running || clash.tunUp) && !calls && bindings.values().stream()
          .noneMatch(b -> ClashConfig.TUN_DEVICE.equals(b.iface))) {
        root.clashStop();
        refreshClashState();
      }
      lastFullSyncSucceeded = true;
      maybeAutoUploadTelemetry(calls);
      long selectedProviders = bindings.values().stream().filter(b -> b.providerBound).count();
      boolean tunRequired = plan.decisions.stream().anyMatch(d -> d.reason == PolicyEngine.Reason.TUN_REQUIRED);
      boolean radioPending = radioOff.values().stream().anyMatch(value -> !value);
      status = enabled() ? (!fallbackReasons.isEmpty()
              ? "已切回手机网络（" + FallbackPolicy.describe(fallbackReasons.values().iterator().next()) + "）"
          : radioPending ? "正在切换"
          : radioOff.isEmpty() ? "等待可用 SIM 卡"
          : tunRequired ? "请选择可用连接"
          : selectedProviders > 0 && bindings.values().stream().anyMatch(b -> b.registered)
              ? "网络通话已就绪"
          : "正在连接")
                         : "已关闭";
    } catch (Exception e) {
      radioOff = Collections.emptyMap();
      for (Binding b : bindings.values()) {
        b.registered = false;
        b.reported = false;
        syncVoiceServiceOverride(b, false);
        b.registration.onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE));
        NewMmTelFeature feature = features.get(b.slot());
        if (feature != null) feature.registrationChanged(false);
      }
      status = "待处理：" + safeCode(e);
      events.record("W", "policy", "tick failed: " + safeCode(e));
    }
  }
  boolean featureMatches(RootSupervisor.Sim sim) {
    if (sim == null) return false;
    NewMmTelFeature f = features.get(sim.policy.slotId);
    return f != null && f.subId == sim.policy.subId && f.simKey.equals(sim.policy.simKey);
  }
  boolean bindingMatches(Binding b, RootSupervisor.Sim sim) {
    return b != null && sim != null && b.sim.policy.subId == sim.policy.subId
        && b.sim.policy.simKey.equals(sim.policy.simKey);
  }
  private void syncPerSimClashNode() {
    ClashState cs = clash;
    if (!clashEnabled() || !cs.running || !cs.tunUp || cs.nodes.isEmpty() || snapshot == null) return;
    RootSupervisor.Sim target = null;
    for (RootSupervisor.Sim sim : snapshot.sims) {
      if (PolicyEngine.eligibility(sim.policy) == PolicyEngine.Reason.ELIGIBLE
          && ClashConfig.TUN_DEVICE.equals(selection(sim.policy.simKey))) {
        target = sim;
        break;
      }
    }
    if (target == null) return;
    String simKey = target.policy.simKey;
    if (simKey.equals(lastAutoClashSimKey)) return;
    String preferred = preferredClashNode(simKey);
    if (preferred.isEmpty()) {
      lastAutoClashSimKey = simKey;
      return;
    }
    boolean available = cs.nodes.stream().anyMatch(n -> preferred.equals(n.name));
    if (!available) return;
    lastAutoClashSimKey = simKey;
    if (preferred.equals(cs.currentNode)) return;
    try {
      root.clashSelectNode(preferred);
      try { root.clashCloseAllConnections(); } catch (Exception ignored) {}
      refreshClashState();
      fallback.resetSlot(target.policy.slotId);
      fallbackReleasedSlots.remove(target.policy.slotId);
      Binding existing = bindings.get(target.policy.slotId);
      if (existing != null && ClashConfig.TUN_DEVICE.equals(existing.iface)) {
        clearSlot(target.policy.slotId, true);
      }
    } catch (Exception ignored) {
      // Fall back to the currently selected node and retry on next SIM switch.
    }
  }
  /**
   * eSIM profile switch: a slot whose radio we held may now host an ineligible profile (e.g.,
   * mainland SIM) or be disabled. Once that persists past the 6-second debounce (so a transient
   * SIM_NOT_READY during an overseas-to-overseas switch does not flicker the cellular radio),
   * give the slot back: stop any stale session, restore IMS, and release the radio lease.
   */
  private final Map<Integer, Long> staleSlotSince = new HashMap<>();
  private void releaseStaleSlots(Map<Integer, Boolean> next, Set<Integer> blocked, boolean calls) {
    long now = System.nanoTime();
    Set<Integer> held = new HashSet<>();
    if (snapshot != null) held.addAll(snapshot.heldRadioSlots);
    for (Map.Entry<Integer, Boolean> e : radioOff.entrySet()) {
      if (Boolean.TRUE.equals(e.getValue())) held.add(e.getKey());
    }
    staleSlotSince.keySet().removeIf(s -> next.containsKey(s) || !held.contains(s));
    if (calls) return;
    for (int slot : held) {
      if (next.containsKey(slot) || blocked.contains(slot)) continue;
      Long since = staleSlotSince.putIfAbsent(slot, now);
      if (since == null || now - since < TimeUnit.SECONDS.toNanos(6)) continue;
      try {
        if (bindings.containsKey(slot) || applied.containsKey(slot)) clearSlot(slot, false);
        if (RegistrationRouting.OWN_PACKAGE.equals(root.currentOverride(slot)))
          root.restore(slot, prefs.getString("previous." + slot, ""));
        root.releaseRadio(slot);
        fallback.resetSlot(slot);
        fallbackReleasedSlots.remove(slot);
        lastSlotIdentity.remove(slot);
        lastRebindNanos.remove(slot);
        staleSlotSince.remove(slot);
      } catch (Exception ignored) {
        // Retry on the next 5-second policy tick while staleSlotSince stays non-empty.
      }
    }
  }
  private void synchronizeProviders() throws Exception {
    boolean frameworkBusy = snapshot == null || snapshot.callsActive;
    for (Binding b : new ArrayList<>(bindings.values())) {
      if (bindings.get(b.slot()) != b) continue;
      RegistrationRouting.Result route = RegistrationRouting.synchronize(
          b.registered && b.engine.isConnected(), frameworkBusy, b.callActive,
          new RegistrationRouting.Backend() {
            public String current() throws Exception { return root.currentOverride(b.slot()); }
            public void selectIndependent() throws Exception {
              if (!b.registered || !b.engine.isConnected())
                throw new IllegalStateException("IMS_NOT_REGISTERED");
              root.bind(b.sim);
            }
            public void restoreSystem() throws Exception {
              root.restore(b.slot(), prefs.getString("previous." + b.slot(), ""));
            }
          });
      b.providerBound = route.independent;
      b.providerDeferred = route.deferred;
    }
  }
  static String safeCode(Throwable e) {
    String s = e.getMessage();
    return s != null && s.matches("[A-Z0-9_]{3,80}") ? s : e.getClass().getSimpleName();
  }
  void clear(PolicyEngine.Action a) throws Exception {
    clearSlot(a.slotId);
  }
  FallbackPolicy.Egress egressOf(RootSupervisor.Sim sim) {
    InterfaceSnapshot inventory = interfaces;
    String selected = selection(sim.policy.simKey);
    if (inventory == null || selected.isEmpty()) return FallbackPolicy.Egress.UNKNOWN;
    InterfaceSnapshot.Selection s = inventory.selection(selected);
    if (!s.available()) return FallbackPolicy.Egress.UNAVAILABLE;
    return s.entry != null && s.entry.voWifiTun() ? FallbackPolicy.Egress.AVAILABLE_TUN
                                                   : FallbackPolicy.Egress.AVAILABLE_NOT_TUN;
  }
  private void deregisterAndRestoreSlot(RootSupervisor.Sim sim) {
    int slot = sim.policy.slotId;
    try {
      root.setVoiceServiceOverride(sim, false);
    } catch (Exception ignored) {}
    NewMmTelFeature f = features.get(slot);
    if (f != null) f.registrationChanged(false);
    registration(slot).onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED));
    try {
      if (RegistrationRouting.OWN_PACKAGE.equals(root.currentOverride(slot))) {
        root.restore(slot, prefs.getString("previous." + slot, ""));
      }
    } catch (Exception ignored) {}
  }
  void clearSlot(int slot) throws Exception {
    boolean keepProvider = enabled() && Boolean.TRUE.equals(radioOff.get(slot))
        && !fallbackReasons.containsKey(slot);
    clearSlot(slot, keepProvider);
  }
  void clearSlot(int slot, boolean keepProvider) throws Exception {
    Binding old = bindings.remove(slot);
    if (old != null) {
      syncVoiceServiceOverride(old, false);
      old.close();
    }
    // Always clear MMTEL capabilities and notify onDeregistered BEFORE unbinding our ImsService,
    // otherwise Android's ImsPhone disconnects callbacks early and leaves the VoWiFi icon stuck.
    NewMmTelFeature clearedFeature = features.get(slot);
    if (clearedFeature != null) clearedFeature.registrationChanged(false);
    (old == null ? registration(slot) : old.registration)
        .onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN));
    if (!keepProvider) root.restore(slot, prefs.getString("previous." + slot, ""));
    String savedIccid = prefs.getString("iccid." + slot, "");
    if (old != null && !old.sim.iccid.isEmpty()) {
      root.stop(old.sim.iccid);
    }
    if (!savedIccid.isEmpty() && (old == null || !savedIccid.equals(old.sim.iccid))) {
      root.stop(savedIccid);
    }
    applied.remove(slot);
    saveJournal();
  }
  void connect(RootSupervisor.Sim sim, String iface, String clashNetnsId) throws Exception {
    Binding b = new Binding(sim, iface, clashNetnsId);
    try {
      b.engine.connect();
      bindings.put(sim.policy.slotId, b);
    } catch (Exception error) {
      b.close();
      throw error;
    }
  }
  void pollBindings() {
    for (Binding b : new ArrayList<>(bindings.values())) {
      try {
        if (!b.engine.isConnected())
          b.engine.connect();
        Map<String, Object> state = b.engine.getStatus();
        b.ims = String.valueOf(state.get("ims"));
        boolean registered = "REGISTERED".equals(b.ims) && radioReady(b);
        if (registered != b.registered) kick();
        if (registered != b.registered || !b.reported) {
          b.registered = registered;
          b.reported = true;
          events.record("I", "ims", "slot " + b.slot() + " plmn=" + b.sim.policy.homePlmn
              + " registered=" + registered);
          if (registered)
            b.registration.onRegistered(ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
          else
            b.registration.onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED));
        }
        if (registered && ClashConfig.TUN_DEVICE.equals(b.iface)
            && preferredClashNode(b.sim.policy.simKey).isEmpty()
            && !clash.currentNode.isEmpty()) {
          savePreferredClashNode(b.sim.policy.simKey, clash.currentNode);
          lastAutoClashSimKey = b.sim.policy.simKey;
        }
        NewMmTelFeature f = features.get(b.slot());
        if (f != null && f.subId == b.sim.policy.subId && f.simKey.equals(b.sim.policy.simKey))
          f.registrationChanged(registered);
        syncVoiceServiceOverride(b, registered && billable());
        b.tunnel = EngineState.text(state, "tunnel");
        try {
          b.engine.getCallSnapshot();
          b.callSnapshotError = 0;
          Map<String, Object> call = b.engine.getState().callSnapshot;
          String stage = String.valueOf(call.get("stage"));
          if (f != null) worker.execute(() -> {
            if (bindings.get(b.slot()) == b) f.snapshot(call);
          });
          else b.callActive = !stage.equals("IDLE");
        } catch (EngineRpcClient.RpcException notReady) {
          // An application-level call readiness error does not invalidate the
          // registration status just read on this still-connected socket.
          b.callSnapshotError = notReady.code;
        } catch (EngineRpcClient.RequestTimeoutException unknown) {
          b.callSnapshotError = -1;
        }
      } catch (Exception e) {
        b.registered = false;
        b.reported = false;
        b.ims = "DISCONNECTED";
        syncVoiceServiceOverride(b, false);
        b.registration.onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN));
        NewMmTelFeature f = features.get(b.slot());
        if (f != null)
          f.registrationChanged(false);
        try {
          if (!root.engineRunning(b.sim.iccid)) {
            b.callActive = false;
            if (f != null)
              worker.execute(f::engineDied);
            if (snapshot != null && !snapshot.callsActive)
              clearSlot(b.slot());
          }
        } catch (Exception recoveryError) {
          status = "待处理：" + safeCode(recoveryError);
        }
      }
    }
  }
  private void syncVoiceServiceOverride(Binding b, boolean hasService) {
    if (b == null) return;
    try {
      b.voiceServiceOverride.synchronize(
          hasService && bindings.get(b.slot()) == b && b.registered && billable() && radioReady(b),
          hasService && frameworkVoiceInService(b), value -> root.setVoiceServiceOverride(b.sim, value));
    } catch (Exception ignored) {
      // Retried automatically on the next policy tick.
    }
  }
  private boolean frameworkVoiceInService(Binding b) {
    try {
      android.telephony.ServiceState state = context
          .getSystemService(android.telephony.TelephonyManager.class)
          .createForSubscriptionId(b.sim.policy.subId).getServiceState();
      return state != null && state.getState() == android.telephony.ServiceState.STATE_IN_SERVICE;
    } catch (RuntimeException error) {
      return false;
    }
  }
  static ImsReasonInfo reason(int code) {
    return new ImsReasonInfo(code, 0, "");
  }
  JSONObject diagnostics() {
    JSONObject out = new JSONObject();
    try {
      out.put("enabled", enabled()).put("billableAllowed", billable()).put("status", status)
          .put("shutdownComplete", shutdownComplete).put("rootCommandCount", root.commandCount.get());
      JSONArray sims = new JSONArray();
      RootSupervisor.Snapshot snap = snapshot;
      if (snap != null) for (RootSupervisor.Sim s : snap.sims) {
        JSONObject row = new JSONObject().put("slot", s.policy.slotId).put("subId", s.policy.subId)
            .put("ready", s.policy.ready).put("homePlmn", s.policy.homePlmn)
            .put("countryIso", s.countryIso).put("carrierName", s.carrierName)
            .put("simOperatorName", s.simOperatorName)
            .put("networkCountryIso", s.networkCountryIso)
            .put("networkOperatorName", s.networkOperatorName)
            .put("networkPlmn", s.networkPlmn)
            .put("carrierId", s.carrierId).put("isEmbedded", s.embedded)
            .put("selectedInterface", selection(s.policy.simKey))
            .put("preferredClashNode", preferredClashNode(s.policy.simKey))
            .put("radioOff", Boolean.TRUE.equals(radioOff.get(s.policy.slotId)))
            .put("featureAttached", featureMatches(s));
        String fb = fallbackReasons.get(s.policy.slotId);
        if (fb != null) row.put("fallbackReason", fb);
        NewMmTelFeature feature = features.get(s.policy.slotId);
        if (feature != null) {
          row.put("callSessionCount", feature.calls.size())
              .put("pendingOutgoing", feature.pendingOutgoing != null);
          NewCallSession pending = feature.pendingOutgoing;
          if (pending != null) row.put("pendingState", pending.getState());
        }
        Binding b = bindings.get(s.policy.slotId);
        if (!bindingMatches(b, s)) b = null;
        if (b != null) row.put("engineCallStage", b.engine.getState().callSnapshot.get("stage"));
        if (b != null) row.put("ims", b.ims).put("registered", b.registered)
            .put("tunnel", b.tunnel).put("callActive", b.callActive)
            .put("callSnapshotError", b.callSnapshotError)
            .put("voiceServiceOverride", Boolean.TRUE.equals(b.voiceServiceOverride.lastApplied()))
            .put("independentProvider", b.providerBound).put("providerSwitchDeferred", b.providerDeferred);
        sims.put(row);
      }
      out.put("sims", sims);
      JSONArray nets = new JSONArray(); InterfaceSnapshot inventory = interfaces;
      if (inventory != null) {
        out.put("preferredVpnInterface", inventory.preferredVpnInterface);
        for (InterfaceSnapshot.Entry e : inventory.entries.values()) nets.put(new JSONObject()
            .put("name",e.name).put("up",e.up).put("available",e.available())
            .put("type",e.type()).put("voWifiTun",e.voWifiTun()).put("owners",new JSONArray(e.ownerPackages)));
      }
      out.put("interfaces", nets);
      ClashState cs = clash;
      out.put("clash", new JSONObject()
          .put("enabled", clashEnabled())
          .put("hasProfile", cs.hasProfile)
          .put("running", cs.running)
          .put("tunUp", cs.tunUp)
          .put("device", ClashConfig.TUN_DEVICE)
          .put("netnsId", cs.netnsId)
          .put("externalLan", cs.externalLan)
          .put("controllerPort", cs.controllerPort)
          .put("currentNode", cs.currentNode)
          .put("nodeCount", cs.nodes.size())
          .put("lastError", cs.lastError));
    } catch (Exception ignored) { }
    return out;
  }
  final class Binding implements EngineSession.Listener {
    final RootSupervisor.Sim sim;
    final String iface, clashNetnsId;
    final EngineSession engine;
    final ImsRegistrationImplBase registration;
    volatile boolean registered, reported, callActive, providerBound, providerDeferred;
    final VoiceServiceOverride voiceServiceOverride = new VoiceServiceOverride();
    volatile String ims = "CONNECTING";
    volatile String tunnel = "UNKNOWN";
    volatile long callSnapshotError;
    Binding(RootSupervisor.Sim sim, String iface, String clashNetnsId) {
      this.sim = sim;
      this.iface = iface == null ? "" : iface;
      this.clashNetnsId = clashNetnsId == null ? "" : clashNetnsId;
      registration = registrationFor(sim);
      engine = new EngineSession(sim.identity(), new AndroidTransport(context.getApplicationInfo().sourceDir), this, billable());
      engine.setPacketObserver(new EngineRpcClient.PacketObserver() {
        @Override
        public void onRpc(String method, long requestId, long durationMs,
            Map<String, Object> requestFrame, Map<String, Object> responseFrame, String error) {
          networkLogs.recordRpc(sim.policy.slotId, method, requestId, durationMs,
              requestFrame, responseFrame, error);
        }
        @Override
        public void onEventFrame(String eventName, Map<String, Object> frame) {
          networkLogs.recordEngineEvent(sim.policy.slotId, eventName, frame);
        }
      });
    }
    int slot() {
      return sim.policy.slotId;
    }
    public void onEvent(EngineSession.Identity identity, EngineState.Event event) {
      worker.execute(() -> {
        if (bindings.get(slot()) != this)
          return;
        NewMmTelFeature f = features.get(slot());
        if (f != null)
          f.event(event);
      });
    }
    public void onDisconnected(EngineSession.Identity identity, Throwable why) {
      worker.execute(() -> {
        if (bindings.get(slot()) == this) {
          registered = false;
          reported = false;
          kick();
          io.execute(() -> syncVoiceServiceOverride(this, false));
          registration.onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN));
          NewMmTelFeature f = features.get(slot());
          if (f != null)
            f.registrationChanged(false);
        }
      });
    }
    void close() {
      engine.close();
    }
  }
  static final class ClashNode {
    final String name, type;
    final int delay;
    ClashNode(String name, String type, int delay) {
      this.name = name; this.type = type; this.delay = delay;
    }
    String label() {
      StringBuilder sb = new StringBuilder(name);
      if (!type.isEmpty()) sb.append(" [").append(type).append("]");
      if (delay > 0) sb.append(" · ").append(delay).append("ms");
      return sb.toString();
    }
  }
  static final class ClashState {
    static final ClashState EMPTY = new ClashState(false, false, false, 0, "", "", "", "",
        false, ClashConfig.CONTROLLER_PORT, "", "", Collections.emptyList(), "");
    final boolean hasProfile, running, tunUp, externalLan;
    final int controllerPort;
    final long profileBytes;
    final String subscriptionUrl, savedNode, currentNode, netnsId, controllerSecret, lanIpv4, lastError;
    final List<ClashNode> nodes;
    ClashState(boolean hasProfile, boolean running, boolean tunUp, long profileBytes,
        String subscriptionUrl, String savedNode, String currentNode, String netnsId,
        boolean externalLan, int controllerPort, String controllerSecret, String lanIpv4,
        List<ClashNode> nodes, String lastError) {
      this.hasProfile = hasProfile; this.running = running; this.tunUp = tunUp;
      this.profileBytes = profileBytes; this.subscriptionUrl = subscriptionUrl;
      this.savedNode = savedNode; this.currentNode = currentNode;
      this.netnsId = netnsId == null ? "" : netnsId;
      this.externalLan = externalLan;
      this.controllerPort = controllerPort > 0 ? controllerPort : ClashConfig.CONTROLLER_PORT;
      this.controllerSecret = controllerSecret == null ? "" : controllerSecret;
      this.lanIpv4 = lanIpv4 == null ? "" : lanIpv4;
      this.nodes = Collections.unmodifiableList(nodes); this.lastError = lastError;
    }
    ClashState withError(String err) {
      return new ClashState(hasProfile, running, tunUp, profileBytes, subscriptionUrl, savedNode,
          currentNode, netnsId, externalLan, controllerPort, controllerSecret, lanIpv4, nodes, err);
    }
    static ClashState fromJson(JSONObject j, String err) {
      List<ClashNode> list = new ArrayList<>();
      JSONArray arr = j.optJSONArray("nodes");
      if (arr != null) {
        for (int i = 0; i < arr.length(); i++) {
          JSONObject o = arr.optJSONObject(i);
          if (o != null && !o.optString("name").isEmpty())
            list.add(new ClashNode(o.optString("name"), o.optString("type", ""), o.optInt("delay", 0)));
        }
      }
      return new ClashState(
          j.optBoolean("hasProfile"), j.optBoolean("running"), j.optBoolean("tunUp"),
          j.optLong("profileBytes"), j.optString("subscriptionUrl", ""),
          j.optString("savedNode", ""), j.optString("currentNode", ""),
          j.optString("netnsId", ""),
          j.optBoolean("externalLan", false),
          j.optInt("controllerPort", ClashConfig.CONTROLLER_PORT),
          j.optString("controllerSecret", ""),
          j.optString("lanIpv4", ""),
          list, err);
    }
  }
  volatile com.smartvowificall.ims.policy.CompatReport compatReport;
  volatile boolean rootUnavailable;

  void runCompatCheckAsync(java.util.function.Consumer<com.smartvowificall.ims.policy.CompatReport> callback) {
    io.execute(() -> {
      com.smartvowificall.ims.policy.CompatReport report = root.compatCheck();
      compatReport = report;
      rootUnavailable = com.smartvowificall.ims.policy.CompatReport.MODE_READ_ONLY_NO_ROOT.equals(report.mode);
      if (callback != null) callback.accept(report);
    });
  }

  static final class ClashConnection {
    final String id, network, type, source, destination, rule, rulePayload, start;
    final List<String> chains;
    final long upload, download;
    ClashConnection(String id, String network, String type, String source, String destination,
        String rule, String rulePayload, List<String> chains, long upload, long download, String start) {
      this.id = id; this.network = network; this.type = type;
      this.source = source; this.destination = destination;
      this.rule = rule; this.rulePayload = rulePayload;
      this.chains = Collections.unmodifiableList(chains);
      this.upload = upload; this.download = download; this.start = start;
    }
  }
  static final class ConnectionsSnapshot {
    static final ConnectionsSnapshot EMPTY = new ConnectionsSnapshot(false, 0, 0, 0, Collections.emptyList(), "");
    final boolean running;
    final long downloadTotal, uploadTotal, memory;
    final List<ClashConnection> connections;
    final String error;
    ConnectionsSnapshot(boolean running, long downloadTotal, long uploadTotal, long memory,
        List<ClashConnection> connections, String error) {
      this.running = running; this.downloadTotal = downloadTotal;
      this.uploadTotal = uploadTotal; this.memory = memory;
      this.connections = Collections.unmodifiableList(connections);
      this.error = error == null ? "" : error;
    }
    static ConnectionsSnapshot error(String err) {
      return new ConnectionsSnapshot(false, 0, 0, 0, Collections.emptyList(), err);
    }
    static ConnectionsSnapshot fromJson(JSONObject j, String err) {
      List<ClashConnection> list = new ArrayList<>();
      JSONArray arr = j.optJSONArray("connections");
      if (arr != null) {
        for (int i = 0; i < arr.length(); i++) {
          JSONObject o = arr.optJSONObject(i);
          if (o == null) continue;
          List<String> chains = new ArrayList<>();
          JSONArray ch = o.optJSONArray("chains");
          if (ch != null) for (int k = 0; k < ch.length(); k++) chains.add(ch.optString(k, ""));
          list.add(new ClashConnection(
              o.optString("id", ""),
              o.optString("network", ""),
              o.optString("type", ""),
              o.optString("source", ""),
              o.optString("destination", ""),
              o.optString("rule", ""),
              o.optString("rulePayload", ""),
              chains,
              o.optLong("upload", 0),
              o.optLong("download", 0),
              o.optString("start", "")));
        }
      }
      return new ConnectionsSnapshot(
          j.optBoolean("running", false),
          j.optLong("downloadTotal", 0),
          j.optLong("uploadTotal", 0),
          j.optLong("memory", 0),
          list, err);
    }
  }
}
