package com.smartvowificall.ims.telemetry;

import com.smartvowificall.ims.engine.JsonCodec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure-Java log sanitizer and strict allowlist telemetry payload builder.
 *
 * <p>Telemetry uploads MUST go through {@link #buildTelemetryPayload} so that subscriber IDs,
 * ICCID, IMSI, IMEI, phone numbers, SIP URIs, proxy node names, subscription URLs, controller
 * secrets, LAN IPs, and device serials are excluded by construction.
 */
public final class LogSanitizer {
  public static final String SCHEMA = "smartvowificall.telemetry.v1";

  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private static final Pattern ICCID_RE =
      Pattern.compile("\\b89\\d{13,20}[0-9A-Fa-f]{0,2}\\b");
  private static final Pattern URL_RE =
      Pattern.compile("\\bhttps?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern SIP_URI_RE =
      Pattern.compile("\\bsip:[^@\\s\"'<>]+@", Pattern.CASE_INSENSITIVE);
  private static final Pattern TEL_URI_RE =
      Pattern.compile("\\btel:\\+?[0-9][0-9\\-().\\s]{4,18}", Pattern.CASE_INSENSITIVE);
  private static final Pattern KV_SECRET_RE = Pattern.compile(
      "(?i)(\"?(?:iccid|imsi|imei|msisdn|phone|phoneNumber|number|peer|caller|callee|simKey|"
          + "subscriptionUrl|controllerSecret|secret|token|password|authorization|nonce|autn|"
          + "rand|res|ik|ck|privateKey|private_key|impi|impu|sipImpu|currentNode|savedNode|"
          + "preferredClashNode|lanIpv4|serial|androidId)\"?\\s*[:=]\\s*)"
          + "(\"[^\"]*\"|'[^']*'|[^\\s,;}\\]\\n]+)");
  private static final Pattern IPV4_RE =
      Pattern.compile("\\b(?!127\\.0\\.0\\.1\\b)(?!198\\.19\\.0\\.\\d+\\b)(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
  private static final Pattern IPV6_RE =
      Pattern.compile("\\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{0,4}\\b");
  private static final Pattern LONG_DIGITS_RE =
      Pattern.compile("\\b\\d{14,22}\\b");
  private static final Pattern PHONE_PLUS_RE =
      Pattern.compile("(?<!\\w)\\+[1-9]\\d{6,14}\\b");

  private LogSanitizer() {}

  /** Sanitizes arbitrary free-form text or log lines, removing personal/SIM/network secrets. */
  public static String sanitizeText(String input) {
    if (input == null || input.isEmpty()) return "";
    String s = input;
    s = KV_SECRET_RE.matcher(s).replaceAll("$1\"[REDACTED]\"");
    s = ICCID_RE.matcher(s).replaceAll("[REDACTED_ICCID]");
    s = URL_RE.matcher(s).replaceAll("[REDACTED_URL]");
    s = SIP_URI_RE.matcher(s).replaceAll("sip:[REDACTED]@");
    s = TEL_URI_RE.matcher(s).replaceAll("tel:[REDACTED]");
    s = PHONE_PLUS_RE.matcher(s).replaceAll("[REDACTED_PHONE]");
    s = IPV4_RE.matcher(s).replaceAll("[REDACTED_IP]");
    s = IPV6_RE.matcher(s).replaceAll("[REDACTED_IP6]");
    s = LONG_DIGITS_RE.matcher(s).replaceAll("[REDACTED_ID]");
    return s;
  }

  public static String classifyEgress(String selectedInterface, boolean isVoWifiTun) {
    if (selectedInterface == null || selectedInterface.isEmpty()) return "none";
    if ("wfctun0".equals(selectedInterface)) return "builtin_clash";
    return isVoWifiTun ? "external_tun" : "non_tun";
  }

  public static final class DeviceInfo {
    public final String manufacturer;
    public final String model;
    public final int sdkInt;
    public final String release;
    public final String abi;

    public DeviceInfo(String manufacturer, String model, int sdkInt, String release, String abi) {
      this.manufacturer = safeShort(manufacturer, 48);
      this.model = safeShort(model, 64);
      this.sdkInt = sdkInt;
      this.release = safeShort(release, 24);
      this.abi = safeShort(abi, 24);
    }
  }

  public static final class SimTelemetry {
    public final int slot;
    public final boolean ready;
    public final String homePlmn;
    public final String egressKind;
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
      this.slot = slot;
      this.ready = ready;
      this.homePlmn = validPlmn(homePlmn) ? homePlmn : "";
      this.egressKind = safeShort(egressKind, 24);
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
    public final String lastError;

    public ClashTelemetry(boolean enabled, boolean hasProfile, boolean running, boolean tunUp,
        int nodeCount, boolean externalLan, String lastError) {
      this.enabled = enabled;
      this.hasProfile = hasProfile;
      this.running = running;
      this.tunUp = tunUp;
      this.nodeCount = Math.max(0, nodeCount);
      this.externalLan = externalLan;
      this.lastError = safeShort(sanitizeText(lastError), 64);
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

  /** Builds a strictly allowlisted, sanitized telemetry JSON string matching schema v1. */
  public static String buildTelemetryPayload(String installId, String trigger, long epochMs,
      String versionName, int versionCode, DeviceInfo device, ServiceTelemetry service,
      List<EventRingBuffer.Entry> events) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", SCHEMA);
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
      devMap.put("model", device.model);
      devMap.put("sdkInt", device.sdkInt);
      devMap.put("release", device.release);
      devMap.put("abi", device.abi);
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
        clashMap.put("lastError", service.clash.lastError);
      }
      svcMap.put("clash", clashMap);

      List<Map<String, Object>> simList = new ArrayList<>();
      for (SimTelemetry s : service.sims) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slot", s.slot);
        row.put("ready", s.ready);
        row.put("homePlmn", s.homePlmn);
        row.put("egressKind", s.egressKind);
        row.put("hasPreferredNode", s.hasPreferredNode);
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

    List<Map<String, Object>> eventList = new ArrayList<>();
    if (events != null) {
      int start = Math.max(0, events.size() - 60);
      for (int i = start; i < events.size(); i++) {
        eventList.add(events.get(i).toSanitizedMap());
      }
    }
    root.put("events", eventList);
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
