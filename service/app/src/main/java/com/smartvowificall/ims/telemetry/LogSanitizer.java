package com.smartvowificall.ims.telemetry;

import com.smartvowificall.ims.engine.JsonCodec;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java log sanitizer and telemetry payload builder.
 *
 * <p>Preserves full diagnostic/log/packet structure while replacing all privacy-sensitive fields
 * and inline tokens uniformly with {@link #HIDDEN} ({@code [Hidden]}).
 */
public final class LogSanitizer {
  public static final String SCHEMA = "smartvowificall.telemetry.v2";
  public static final String HIDDEN = "[Hidden]";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DATE_COMPACT =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  private static final Set<String> SENSITIVE_KEYS = new HashSet<>(Arrays.asList(
      "iccid", "imsi", "imei", "meid", "msisdn", "phone", "phonenumber", "number", "peer",
      "remote", "caller", "callee", "dialnumber", "destinationnumber", "simkey",
      "subscriptionurl", "controllersecret", "secret", "token", "password", "passwd",
      "authorization", "cookie", "set-cookie", "x-telemetry-key", "apikey", "api_key",
      "nonce", "autn", "rand", "res", "ik", "ck", "privatekey", "private_key", "psk",
      "impi", "impu", "sipimpu", "sipuri", "currentnode", "savednode", "preferredclashnode",
      "now", "lanipv4", "serial", "androidid", "ssid", "bssid", "wifissid", "wifibssid",
      "email", "username", "uuid", "server", "sni", "servername", "peerip", "localip"
  ));

  /** Map keys whose child keys themselves are private proxy node names (e.g. Clash /proxies). */
  private static final Set<String> NODE_MAP_KEYS = new HashSet<>(Arrays.asList(
      "proxies", "delays"
  ));

  /** List keys whose string elements are private proxy node names. */
  private static final Set<String> NODE_LIST_KEYS = new HashSet<>(Arrays.asList(
      "chains", "all"
  ));

  private static final Pattern ICCID_RE =
      Pattern.compile("\\b89\\d{13,20}[0-9A-Fa-f]{0,2}\\b");
  private static final Pattern BEARER_RE =
      Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{6,}");
  private static final Pattern URL_RE =
      Pattern.compile("\\bhttps?://[^\\s\"'<>\\])}]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern SIP_URI_RE =
      Pattern.compile("\\bsip:[^@\\s\"'<>]+@", Pattern.CASE_INSENSITIVE);
  private static final Pattern TEL_URI_RE =
      Pattern.compile("\\btel:\\+?[0-9][0-9\\-().\\s]{4,18}", Pattern.CASE_INSENSITIVE);
  private static final Pattern EMAIL_RE =
      Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
  private static final Pattern KV_SECRET_RE = Pattern.compile(
      "(?i)(\"?(?:iccid|imsi|imei|meid|msisdn|phone|phoneNumber|number|peer|caller|callee|"
          + "simKey|subscriptionUrl|controllerSecret|secret|token|password|authorization|"
          + "cookie|nonce|autn|rand|res|ik|ck|privateKey|private_key|impi|impu|sipImpu|"
          + "currentNode|savedNode|preferredClashNode|lanIpv4|serial|androidId|ssid|bssid|"
          + "x-telemetry-key)\"?\\s*[:=]\\s*)"
          + "(\"[^\"]*\"|'[^']*'|(?i:(?:Bearer|Basic)\\s+)?[^\\s,;}\\]\\n]+)");
  private static final Pattern IPV4_RE = Pattern.compile(
      "(?<![\\w.])(?!127\\.0\\.0\\.1(?![\\w.]))(?!198\\.19\\.[01]\\.\\d+(?![\\w.]))"
          + "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}(?![\\w.])");
  private static final Pattern IPV6_RE =
      Pattern.compile("\\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{0,4}\\b");
  private static final Pattern LONG_DIGITS_RE =
      Pattern.compile("\\b\\d{14,22}\\b");
  private static final Pattern PHONE_PLUS_RE =
      Pattern.compile("(?<!\\w)\\+[1-9]\\d{6,14}\\b");
  private static final Pattern PATH_ICCID_RE =
      Pattern.compile("(?<=/)89\\d{13,20}[0-9A-Fa-f]{0,2}(?=\\.[A-Za-z0-9]+|/|\\b)");

  private LogSanitizer() {}

  /** Generates a human-readable lookup ID such as {@code SVW-20261123-7A4F9C}. */
  public static String generateLogId(long epochMs) {
    String date = DATE_COMPACT.format(Instant.ofEpochMilli(epochMs));
    byte[] bytes = new byte[3];
    RANDOM.nextBytes(bytes);
    StringBuilder hex = new StringBuilder(6);
    for (byte b : bytes) hex.append(String.format(Locale.US, "%02X", b & 0xFF));
    return "SVW-" + date + "-" + hex;
  }

  public static String safeLogId(String candidate, long fallbackEpochMs) {
    if (candidate != null && candidate.matches("SVW-\\d{8}-[0-9A-Fa-f]{6,8}")) {
      return candidate.toUpperCase(Locale.US);
    }
    return generateLogId(fallbackEpochMs);
  }

  /**
   * Sanitizes arbitrary free-form text or raw log lines, keeping structure intact and replacing
   * every private value with {@code [Hidden]}.
   */
  public static String sanitizeText(String input) {
    if (input == null || input.isEmpty()) return "";
    String s = input;
    s = BEARER_RE.matcher(s).replaceAll("$1 " + HIDDEN);
    s = KV_SECRET_RE.matcher(s).replaceAll("$1\"" + HIDDEN + "\"");
    s = sanitizeUrlsInText(s);
    s = PATH_ICCID_RE.matcher(s).replaceAll(HIDDEN);
    s = ICCID_RE.matcher(s).replaceAll(HIDDEN);
    s = SIP_URI_RE.matcher(s).replaceAll("sip:" + HIDDEN + "@");
    s = TEL_URI_RE.matcher(s).replaceAll("tel:" + HIDDEN);
    s = EMAIL_RE.matcher(s).replaceAll(HIDDEN);
    s = PHONE_PLUS_RE.matcher(s).replaceAll(HIDDEN);
    s = IPV4_RE.matcher(s).replaceAll(HIDDEN);
    s = IPV6_RE.matcher(s).replaceAll(HIDDEN);
    s = LONG_DIGITS_RE.matcher(s).replaceAll(HIDDEN);
    return s;
  }

  private static String sanitizeUrlsInText(String input) {
    Matcher m = URL_RE.matcher(input);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String url = m.group();
      m.appendReplacement(sb, Matcher.quoteReplacement(sanitizeUrl(url)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /**
   * Sanitizes a URL while keeping safe diagnostic endpoints (localhost Clash API, public telemetry
   * worker path, gstatic 204 test URL) readable and hiding external subscription hosts/queries.
   */
  public static String sanitizeUrl(String url) {
    if (url == null || url.isEmpty()) return "";
    String lower = url.toLowerCase(Locale.US);
    boolean isLocal = lower.startsWith("http://127.0.0.1:")
        || lower.startsWith("http://localhost:")
        || lower.startsWith("http://127.0.0.1/")
        || lower.startsWith("http://localhost/");
    boolean isKnownPublic = lower.startsWith("https://smartvowificall-telemetry.")
        || lower.startsWith("http://www.gstatic.com/generate_204")
        || lower.startsWith("https://www.gstatic.com/generate_204");
    if (isLocal || isKnownPublic) {
      int q = url.indexOf('?');
      String base = q >= 0 ? url.substring(0, q) : url;
      // Hide URL-encoded proxy node names after /proxies/<group> if not standard group
      if (isLocal && base.contains("/proxies/")
          && !base.endsWith("/proxies/VoWiFi") && !base.endsWith("/proxies/WFC-PROXY")) {
        int idx = base.indexOf("/proxies/");
        base = base.substring(0, idx + "/proxies/".length()) + HIDDEN;
      }
      if (q >= 0) {
        String query = url.substring(q + 1);
        StringBuilder safeQuery = new StringBuilder();
        for (String pair : query.split("&")) {
          if (safeQuery.length() > 0) safeQuery.append('&');
          int eq = pair.indexOf('=');
          if (eq <= 0) {
            safeQuery.append(pair);
          } else {
            String k = pair.substring(0, eq);
            String v = pair.substring(eq + 1);
            String kl = k.toLowerCase(Locale.US);
            if (kl.equals("timeout")) {
              safeQuery.append(k).append('=').append(v);
            } else if (kl.equals("url") && v.toLowerCase(Locale.US).contains("gstatic.com")) {
              safeQuery.append(k).append('=').append(v);
            } else {
              safeQuery.append(k).append('=').append(HIDDEN);
            }
          }
        }
        return base + "?" + safeQuery;
      }
      return base;
    }
    return HIDDEN;
  }

  /**
   * Deeply sanitizes a JSON-compatible tree (Map / List / String / Number / Boolean), preserving
   * every field key and nesting structure while replacing private values with {@code [Hidden]}.
   */
  public static Object sanitizeStructure(Object value) {
    return sanitizeStructure(value, "", 0);
  }

  public static Object sanitizeStructure(Object value, String rootKey) {
    return sanitizeStructure(value, rootKey == null ? "" : rootKey, 0);
  }

  @SuppressWarnings("unchecked")
  private static Object sanitizeStructure(Object value, String parentKey, int depth) {
    if (value == null) return null;
    if (depth > 12) return HIDDEN;
    if (value instanceof Boolean || value instanceof Number) return value;
    if (value instanceof String) {
      String s = (String) value;
      if (s.isEmpty()) return "";
      if (s.contains("\"proxies\":{") || s.contains("\"WFC-PROXY\":{")
          || s.contains("\"VoWiFi\":{")) {
        return HIDDEN;
      }
      if (s.length() > 4096) s = s.substring(0, 4096) + "...(truncated)";
      return sanitizeText(s);
    }
    if (value instanceof List<?>) {
      List<?> src = (List<?>) value;
      String pk = parentKey == null ? "" : parentKey.toLowerCase(Locale.US);
      boolean maskItems = NODE_LIST_KEYS.contains(pk);
      int limit = Math.min(src.size(), 120);
      List<Object> out = new ArrayList<>(limit);
      for (int i = 0; i < limit; i++) {
        Object item = src.get(i);
        if (maskItems && item instanceof String) {
          String s = (String) item;
          out.add("DIRECT".equals(s) || "REJECT".equals(s) || "VoWiFi".equals(s)
              || "WFC-PROXY".equals(s) ? s : HIDDEN);
        } else {
          out.add(sanitizeStructure(item, parentKey, depth + 1));
        }
      }
      return out;
    }
    if (value instanceof Map<?, ?>) {
      Map<?, ?> src = (Map<?, ?>) value;
      String pk = parentKey == null ? "" : parentKey.toLowerCase(Locale.US);
      boolean maskChildKeys = NODE_MAP_KEYS.contains(pk);
      Map<String, Object> out = new LinkedHashMap<>();
      int count = 0;
      for (Map.Entry<?, ?> e : src.entrySet()) {
        if (count++ >= 120) break;
        String rawKey = String.valueOf(e.getKey());
        String keyLower = rawKey.toLowerCase(Locale.US);
        String outKey = rawKey;
        if (maskChildKeys && !"VoWiFi".equals(rawKey) && !"WFC-PROXY".equals(rawKey)
            && !"DIRECT".equals(rawKey) && !"REJECT".equals(rawKey) && !"GLOBAL".equals(rawKey)) {
          outKey = HIDDEN + "#" + count;
        }
        Object rawVal = e.getValue();
        if (SENSITIVE_KEYS.contains(keyLower)) {
          out.put(outKey, maskSensitiveValue(rawVal));
        } else if ("name".equals(keyLower) && ("nodes".equals(pk) || "proxies".equals(pk)
            || "requestbody".equals(pk) || "responsebody".equals(pk))) {
          String sv = rawVal == null ? "" : String.valueOf(rawVal);
          out.put(outKey, (sv.isEmpty() || "VoWiFi".equals(sv) || "WFC-PROXY".equals(sv)
              || "DIRECT".equals(sv) || "REJECT".equals(sv) || "GLOBAL".equals(sv)) ? sv : HIDDEN);
        } else {
          out.put(outKey, sanitizeStructure(rawVal, rawKey, depth + 1));
        }
      }
      return out;
    }
    return sanitizeText(String.valueOf(value));
  }

  private static Object maskSensitiveValue(Object rawVal) {
    if (rawVal == null) return null;
    if (rawVal instanceof String) {
      return ((String) rawVal).isEmpty() ? "" : HIDDEN;
    }
    if (rawVal instanceof List<?>) {
      List<?> src = (List<?>) rawVal;
      List<Object> masked = new ArrayList<>(Math.min(src.size(), 60));
      for (int i = 0; i < Math.min(src.size(), 60); i++) masked.add(HIDDEN);
      return masked;
    }
    if (rawVal instanceof Map<?, ?>) {
      Map<?, ?> src = (Map<?, ?>) rawVal;
      Map<String, Object> masked = new LinkedHashMap<>();
      int n = 0;
      for (Object k : src.keySet()) {
        if (n++ >= 60) break;
        masked.put(String.valueOf(k), HIDDEN);
      }
      return masked;
    }
    return HIDDEN;
  }

  public static String classifyEgress(String selectedInterface, boolean isVoWifiTun) {
    if (selectedInterface == null || selectedInterface.isEmpty()) return "none";
    if ("wfctun0".equals(selectedInterface) || "wfctun1".equals(selectedInterface)) return "builtin_clash";
    return isVoWifiTun ? "external_tun" : "non_tun";
  }

  public static final class DeviceInfo {
    public final String manufacturer;
    public final String brand;
    public final String model;
    public final String marketName;
    public final String certModel;
    public final String device;
    public final String product;
    public final String hardware;
    public final String board;
    public final String socManufacturer;
    public final String socModel;
    public final int cpuCores;
    public final long totalRamMb;
    public final long availRamMb;
    public final String screenResolution;
    public final int screenDensityDpi;
    public final String osVersion;
    public final String buildDisplay;
    public final int sdkInt;
    public final String release;
    public final String securityPatch;
    public final String incremental;
    public final String basebandVersion;
    public final String hardwareRevision;
    public final String hardwareRegion;
    public final String abi;
    public final List<String> supportedAbis;
    public final String locale;
    public final String timezone;
    public final String kernelVersion;
    public final String kernelFull;
    public final String fingerprint;

    public DeviceInfo(String manufacturer, String model, int sdkInt, String release, String abi) {
      this(manufacturer, "", model, "", "", "", "", "", "", "", "", 0, 0L, 0L, "", 0, "", "",
          sdkInt, release, "", "", "", "", "", abi,
          abi == null || abi.isEmpty() ? Collections.emptyList() : Collections.singletonList(abi),
          "", "", "", "", "");
    }

    public DeviceInfo(String manufacturer, String brand, String model, String device,
        String product, String hardware, String board, int sdkInt, String release,
        String securityPatch, String incremental, String abi, List<String> supportedAbis,
        String locale, String timezone, String kernelVersion, String fingerprint) {
      this(manufacturer, brand, model, "", "", device, product, hardware, board, "", "", 0,
          0L, 0L, "", 0, "", "", sdkInt, release, securityPatch, incremental, "", "", "",
          abi, supportedAbis, locale, timezone, kernelVersion, "", fingerprint);
    }

    public DeviceInfo(String manufacturer, String brand, String model, String marketName,
        String certModel, String device, String product, String hardware, String board,
        String socManufacturer, String socModel, int cpuCores, long totalRamMb, long availRamMb,
        String screenResolution, int screenDensityDpi, String osVersion, String buildDisplay,
        int sdkInt, String release, String securityPatch, String incremental,
        String basebandVersion, String hardwareRevision, String hardwareRegion, String abi,
        List<String> supportedAbis, String locale, String timezone, String kernelVersion,
        String kernelFull, String fingerprint) {
      this.manufacturer = safeShort(manufacturer, 48);
      this.brand = safeShort(brand, 48);
      this.model = safeShort(model, 64);
      this.marketName = safeShort(sanitizeText(marketName), 64);
      this.certModel = safeShort(sanitizeText(certModel), 64);
      this.device = safeShort(device, 48);
      this.product = safeShort(product, 64);
      this.hardware = safeShort(hardware, 48);
      this.board = safeShort(board, 48);
      this.socManufacturer = safeShort(socManufacturer, 48);
      this.socModel = safeShort(socModel, 64);
      this.cpuCores = Math.max(0, cpuCores);
      this.totalRamMb = Math.max(0L, totalRamMb);
      this.availRamMb = Math.max(0L, availRamMb);
      this.screenResolution = safeShort(screenResolution, 32);
      this.screenDensityDpi = Math.max(0, screenDensityDpi);
      this.osVersion = safeShort(sanitizeText(osVersion), 128);
      this.buildDisplay = safeShort(sanitizeText(buildDisplay), 128);
      this.sdkInt = sdkInt;
      this.release = safeShort(release, 24);
      this.securityPatch = safeShort(securityPatch, 24);
      this.incremental = safeShort(incremental, 64);
      this.basebandVersion = safeShort(sanitizeText(basebandVersion), 160);
      this.hardwareRevision = safeShort(sanitizeText(hardwareRevision), 64);
      this.hardwareRegion = safeShort(sanitizeText(hardwareRegion), 32);
      this.abi = safeShort(abi, 24);
      this.supportedAbis = supportedAbis == null ? Collections.emptyList() : supportedAbis;
      this.locale = safeShort(locale, 32);
      this.timezone = safeShort(timezone, 48);
      this.kernelVersion = safeShort(sanitizeText(kernelVersion), 128);
      this.kernelFull = safeShort(sanitizeText(kernelFull), 256);
      this.fingerprint = safeShort(fingerprint, 160);
    }
  }

  public static final class SimTelemetry {
    public final int slot;
    public final int subId;
    public final int phoneId;
    public final boolean ready;
    public final int simState;
    public final int rawSimState;
    public final String identityCode;
    public final List<String> identityIssues;
    public final String countryIso;
    public final String carrierName;
    public final String simOperatorName;
    public final String networkCountryIso;
    public final String networkOperatorName;
    public final String networkPlmn;
    public final String homePlmn;
    public final int carrierId;
    public final boolean embedded;
    public final boolean opportunistic;
    public final String egressKind;
    public final String selectedInterface;
    public final boolean hasPreferredNode;
    public final boolean radioOff;
    public final boolean featureAttached;
    public final String ims;
    public final boolean registered;
    public final String tunnel;
    public final boolean callActive;
    public final long callSnapshotError;
    public final boolean voiceServiceOverride;
    public final boolean independentProvider;
    public final boolean providerSwitchDeferred;
    public final String fallbackReason;

    public SimTelemetry(int slot, boolean ready, String homePlmn, String egressKind,
        boolean hasPreferredNode, boolean radioOff, boolean featureAttached, String ims,
        boolean registered, String tunnel, boolean callActive, long callSnapshotError,
        boolean voiceServiceOverride, boolean independentProvider,
        boolean providerSwitchDeferred, String fallbackReason) {
      this(slot, -1, -1, ready, 0, 0, "", Collections.emptyList(), "", "", "", "", "", "",
          homePlmn, -1, false, false, egressKind, "", hasPreferredNode, radioOff, featureAttached,
          ims, registered, tunnel, callActive, callSnapshotError, voiceServiceOverride,
          independentProvider, providerSwitchDeferred, fallbackReason);
    }

    public SimTelemetry(int slot, int subId, int phoneId, boolean ready, int simState,
        int rawSimState, String identityCode, List<String> identityIssues, String countryIso,
        String carrierName, String simOperatorName, String networkCountryIso,
        String networkOperatorName, String networkPlmn, String homePlmn, int carrierId,
        boolean embedded, boolean opportunistic, String egressKind, String selectedInterface,
        boolean hasPreferredNode, boolean radioOff, boolean featureAttached, String ims,
        boolean registered, String tunnel, boolean callActive, long callSnapshotError,
        boolean voiceServiceOverride, boolean independentProvider,
        boolean providerSwitchDeferred, String fallbackReason) {
      this.slot = slot;
      this.subId = subId;
      this.phoneId = phoneId;
      this.ready = ready;
      this.simState = simState;
      this.rawSimState = rawSimState;
      this.identityCode = safeShort(identityCode, 64);
      this.identityIssues = identityIssues == null ? Collections.emptyList() : identityIssues;
      this.countryIso = safeShort(countryIso == null ? "" : countryIso.toUpperCase(Locale.US), 8);
      this.carrierName = safeShort(sanitizeText(carrierName), 64);
      this.simOperatorName = safeShort(sanitizeText(simOperatorName), 64);
      this.networkCountryIso =
          safeShort(networkCountryIso == null ? "" : networkCountryIso.toUpperCase(Locale.US), 8);
      this.networkOperatorName = safeShort(sanitizeText(networkOperatorName), 64);
      this.networkPlmn = validPlmn(networkPlmn) ? networkPlmn : "";
      this.homePlmn = validPlmn(homePlmn) ? homePlmn : "";
      this.carrierId = carrierId;
      this.embedded = embedded;
      this.opportunistic = opportunistic;
      this.egressKind = safeShort(egressKind, 24);
      this.selectedInterface = safeShort(selectedInterface, 32);
      this.hasPreferredNode = hasPreferredNode;
      this.radioOff = radioOff;
      this.featureAttached = featureAttached;
      this.ims = safeShort(ims, 32);
      this.registered = registered;
      this.tunnel = safeShort(tunnel, 32);
      this.callActive = callActive;
      this.callSnapshotError = callSnapshotError;
      this.voiceServiceOverride = voiceServiceOverride;
      this.independentProvider = independentProvider;
      this.providerSwitchDeferred = providerSwitchDeferred;
      this.fallbackReason = safeShort(fallbackReason, 64);
    }
  }

  public static final class ClashTelemetry {
    public final boolean enabled;
    public final boolean hasProfile;
    public final boolean running;
    public final boolean tunUp;
    public final int nodeCount;
    public final boolean externalLan;
    public final int controllerPort;
    public final String netnsId;
    public final String lastError;

    public ClashTelemetry(boolean enabled, boolean hasProfile, boolean running, boolean tunUp,
        int nodeCount, boolean externalLan, String lastError) {
      this(enabled, hasProfile, running, tunUp, nodeCount, externalLan, 19090, "", lastError);
    }

    public ClashTelemetry(boolean enabled, boolean hasProfile, boolean running, boolean tunUp,
        int nodeCount, boolean externalLan, int controllerPort, String netnsId, String lastError) {
      this.enabled = enabled;
      this.hasProfile = hasProfile;
      this.running = running;
      this.tunUp = tunUp;
      this.nodeCount = Math.max(0, nodeCount);
      this.externalLan = externalLan;
      this.controllerPort = controllerPort;
      this.netnsId = safeShort(netnsId, 32);
      this.lastError = safeShort(sanitizeText(lastError), 96);
    }
  }

  public static final class ServiceTelemetry {
    public final boolean enabled;
    public final boolean billableAllowed;
    public final String status;
    public final boolean shutdownComplete;
    public final long rootCommandCount;
    public final ClashTelemetry clash;
    public final List<SimTelemetry> sims;

    public ServiceTelemetry(boolean enabled, boolean billableAllowed, String status,
        boolean shutdownComplete, long rootCommandCount, ClashTelemetry clash,
        List<SimTelemetry> sims) {
      this.enabled = enabled;
      this.billableAllowed = billableAllowed;
      this.status = safeShort(sanitizeText(status), 96);
      this.shutdownComplete = shutdownComplete;
      this.rootCommandCount = Math.max(0, rootCommandCount);
      this.clash = clash;
      this.sims = sims == null ? new ArrayList<>() : sims;
    }
  }

  /** Backward-compatible overload for callers without raw/network logs. */
  public static String buildTelemetryPayload(String installId, String trigger, long epochMs,
      String versionName, int versionCode, DeviceInfo device, ServiceTelemetry service,
      List<EventRingBuffer.Entry> events) {
    return buildTelemetryPayload(generateLogId(epochMs), installId, trigger, epochMs, versionName,
        versionCode, device, service, null, null, events, null, null, "");
  }

  /** Builds the complete sanitized telemetry JSON (v2) with {@code [Hidden]} redaction. */
  public static String buildTelemetryPayload(
      String logId,
      String installId,
      String trigger,
      long epochMs,
      String versionName,
      int versionCode,
      DeviceInfo device,
      ServiceTelemetry service,
      Map<String, Object> diagnostics,
      List<Map<String, Object>> interfaces,
      List<EventRingBuffer.Entry> events,
      List<Map<String, Object>> networkLogs,
      List<Map<String, Object>> clashConnections,
      String rawRootLogs) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", SCHEMA);
    root.put("logId", safeLogId(logId, epochMs));
    root.put("generatedAt", ISO_UTC.format(Instant.ofEpochMilli(epochMs)));
    root.put("installId", safeUuid(installId));
    root.put("trigger", safeTrigger(trigger));

    Map<String, Object> appMap = new LinkedHashMap<>();
    appMap.put("versionName", safeShort(versionName, 32));
    appMap.put("versionCode", versionCode);
    root.put("app", appMap);

    Map<String, Object> devMap = new LinkedHashMap<>();
    if (device != null) {
      devMap.put("manufacturer", device.manufacturer);
      devMap.put("brand", device.brand);
      devMap.put("model", device.model);
      devMap.put("marketName", device.marketName);
      devMap.put("certModel", device.certModel);
      devMap.put("device", device.device);
      devMap.put("product", device.product);
      devMap.put("hardware", device.hardware);
      devMap.put("board", device.board);
      devMap.put("socManufacturer", device.socManufacturer);
      devMap.put("socModel", device.socModel);
      devMap.put("cpuCores", device.cpuCores);
      devMap.put("totalRamMb", device.totalRamMb);
      devMap.put("availRamMb", device.availRamMb);
      devMap.put("screenResolution", device.screenResolution);
      devMap.put("screenDensityDpi", device.screenDensityDpi);
      devMap.put("osVersion", device.osVersion);
      devMap.put("buildDisplay", device.buildDisplay);
      devMap.put("sdkInt", device.sdkInt);
      devMap.put("release", device.release);
      devMap.put("securityPatch", device.securityPatch);
      devMap.put("incremental", device.incremental);
      devMap.put("basebandVersion", device.basebandVersion);
      devMap.put("hardwareRevision", device.hardwareRevision);
      devMap.put("hardwareRegion", device.hardwareRegion);
      devMap.put("abi", device.abi);
      devMap.put("supportedAbis", device.supportedAbis);
      devMap.put("locale", device.locale);
      devMap.put("timezone", device.timezone);
      devMap.put("kernelVersion", device.kernelVersion);
      devMap.put("kernelFull", device.kernelFull);
      devMap.put("fingerprint", device.fingerprint);
      devMap.put("serial", HIDDEN);
      devMap.put("androidId", HIDDEN);
    }
    root.put("device", devMap);

    Map<String, Object> svcMap = new LinkedHashMap<>();
    if (service != null) {
      svcMap.put("enabled", service.enabled);
      svcMap.put("billableAllowed", service.billableAllowed);
      svcMap.put("status", service.status);
      svcMap.put("shutdownComplete", service.shutdownComplete);
      svcMap.put("rootCommandCount", service.rootCommandCount);

      Map<String, Object> clashMap = new LinkedHashMap<>();
      if (service.clash != null) {
        clashMap.put("enabled", service.clash.enabled);
        clashMap.put("hasProfile", service.clash.hasProfile);
        clashMap.put("running", service.clash.running);
        clashMap.put("tunUp", service.clash.tunUp);
        clashMap.put("nodeCount", service.clash.nodeCount);
        clashMap.put("externalLan", service.clash.externalLan);
        clashMap.put("controllerPort", service.clash.controllerPort);
        clashMap.put("netnsId", service.clash.netnsId);
        clashMap.put("currentNode", service.clash.running ? HIDDEN : "");
        clashMap.put("subscriptionUrl", service.clash.hasProfile ? HIDDEN : "");
        clashMap.put("controllerSecret", HIDDEN);
        clashMap.put("lanIpv4", service.clash.externalLan ? HIDDEN : "");
        clashMap.put("lastError", service.clash.lastError);
      }
      svcMap.put("clash", clashMap);

      List<Map<String, Object>> simList = new ArrayList<>();
      for (SimTelemetry s : service.sims) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slot", s.slot);
        row.put("subId", s.subId);
        row.put("phoneId", s.phoneId);
        row.put("ready", s.ready);
        row.put("simState", s.simState);
        row.put("rawSimState", s.rawSimState);
        row.put("identityCode", s.identityCode);
        row.put("identityIssues", s.identityIssues);
        row.put("countryIso", s.countryIso);
        row.put("carrierName", s.carrierName);
        row.put("simOperatorName", s.simOperatorName);
        row.put("networkCountryIso", s.networkCountryIso);
        row.put("networkOperatorName", s.networkOperatorName);
        row.put("networkPlmn", s.networkPlmn);
        row.put("homePlmn", s.homePlmn);
        row.put("mcc", s.homePlmn.length() >= 3 ? s.homePlmn.substring(0, 3) : "");
        row.put("mnc", s.homePlmn.length() > 3 ? s.homePlmn.substring(3) : "");
        row.put("carrierId", s.carrierId);
        row.put("isEmbedded", s.embedded);
        row.put("isOpportunistic", s.opportunistic);
        row.put("iccid", HIDDEN);
        row.put("simKey", HIDDEN);
        row.put("egressKind", s.egressKind);
        row.put("selectedInterface", s.selectedInterface);
        row.put("hasPreferredNode", s.hasPreferredNode);
        row.put("preferredClashNode", s.hasPreferredNode ? HIDDEN : "");
        row.put("radioOff", s.radioOff);
        row.put("featureAttached", s.featureAttached);
        row.put("ims", s.ims);
        row.put("registered", s.registered);
        row.put("tunnel", s.tunnel);
        row.put("callActive", s.callActive);
        row.put("callSnapshotError", s.callSnapshotError);
        row.put("voiceServiceOverride", s.voiceServiceOverride);
        row.put("independentProvider", s.independentProvider);
        row.put("providerSwitchDeferred", s.providerSwitchDeferred);
        row.put("fallbackReason", s.fallbackReason);
        simList.add(row);
      }
      svcMap.put("sims", simList);
    }
    root.put("service", svcMap);

    if (diagnostics != null) {
      root.put("diagnostics", sanitizeStructure(diagnostics));
    }
    if (interfaces != null) {
      root.put("interfaces", sanitizeStructure(interfaces));
    }

    List<Map<String, Object>> eventList = new ArrayList<>();
    if (events != null) {
      int start = Math.max(0, events.size() - 120);
      for (int i = start; i < events.size(); i++) {
        eventList.add(events.get(i).toSanitizedMap());
      }
    }
    root.put("events", eventList);

    root.put("networkLogs", networkLogs == null ? Collections.emptyList()
        : sanitizeStructure(networkLogs));
    root.put("clashConnections", clashConnections == null ? Collections.emptyList()
        : sanitizeStructure(clashConnections));

    String cleanRootLogs = rawRootLogs == null ? "" : sanitizeText(rawRootLogs);
    int maxRawChars = 240 * 1024;
    if (cleanRootLogs.length() > maxRawChars) {
      cleanRootLogs = "...(earlier logs truncated, keeping latest 240KB)...\n"
          + cleanRootLogs.substring(cleanRootLogs.length() - maxRawChars);
    }
    root.put("rawLogs", cleanRootLogs);
    return JsonCodec.encode(root);
  }

  private static boolean validPlmn(String s) {
    if (s == null || (s.length() != 5 && s.length() != 6)) return false;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
    }
    return true;
  }

  private static String safeShort(String s, int max) {
    if (s == null) return "";
    String trimmed = s.replace('\r', ' ').replace('\n', ' ').trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static String safeUuid(String s) {
    if (s != null && s.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
      return s.toLowerCase(Locale.ROOT);
    }
    return "00000000-0000-4000-8000-000000000000";
  }

  private static String safeTrigger(String s) {
    if ("manual".equals(s) || "periodic".equals(s) || "state_change".equals(s)) return s;
    return "manual";
  }
}
