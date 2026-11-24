import com.smartvowificall.ims.engine.JsonCodec;
import com.smartvowificall.ims.telemetry.EventRingBuffer;
import com.smartvowificall.ims.telemetry.LogSanitizer;
import com.smartvowificall.ims.telemetry.NetworkLogBuffer;
import com.smartvowificall.ims.telemetry.TelemetryClient;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class TelemetrySanitizerTest {
  static int passed;

  static void check(boolean cond, String name) {
    if (!cond) throw new AssertionError("FAILED: " + name);
    passed++;
  }

  static void eq(Object expected, Object actual, String name) {
    if (expected == null ? actual != null : !expected.equals(actual))
      throw new AssertionError(name + ": expected " + expected + " got " + actual);
    passed++;
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    // 1. Free-text redaction uses uniform [Hidden] while preserving non-sensitive diagnostics
    String dirty = "iccid=8944305293606853618 imsi=234330000000019 imei=351234567890123 "
        + "peer=+447700900123 sip:234330000000019@ims.mnc033.mcc234.3gppnetwork.org "
        + "tel:+8613800138000 url=https://sub.example.com/secret-token/profile.yaml "
        + "currentNode=\"英国WiFicall（家庭）X2\" controllerSecret=\"abc123def456\" "
        + "Authorization: Bearer superSecretToken123 "
        + "lan=192.168.1.55 tun=198.19.0.1 loop=127.0.0.1 v6=2a00:23ee:fa48:846c::1";
    String clean = LogSanitizer.sanitizeText(dirty);
    for (String forbidden : new String[] {
        "8944305293606853618", "234330000000019", "351234567890123",
        "+447700900123", "+8613800138000", "sub.example.com", "secret-token",
        "英国WiFicall", "abc123def456", "superSecretToken123", "192.168.1.55",
        "2a00:23ee:fa48:846c::1"}) {
      check(!clean.contains(forbidden), "redacted: " + forbidden);
    }
    check(clean.contains(LogSanitizer.HIDDEN), "uses [Hidden] marker");
    check(clean.contains("sip:[Hidden]@ims.mnc033.mcc234.3gppnetwork.org"), "keeps public IMS realm");
    check(clean.contains("198.19.0.1"), "wfctun0 local IP preserved");
    check(clean.contains("127.0.0.1"), "loopback preserved");

    // 2. Deep structure preservation with [Hidden]
    Map<String, Object> reqHdrs = new LinkedHashMap<>();
    reqHdrs.put("Authorization", "Bearer my-clash-secret-1234");
    reqHdrs.put("Content-Type", "application/json");
    Map<String, Object> reqBody = new LinkedHashMap<>();
    reqBody.put("name", "HK-Private-Node");
    reqBody.put("iccid", "8944305293606853618");
    reqBody.put("carrierName", "EE");
    Map<String, Object> proxiesMap = new LinkedHashMap<>();
    proxiesMap.put("HK-Private-Node", 45);
    proxiesMap.put("DIRECT", 1);
    Map<String, Object> respBody = new LinkedHashMap<>();
    respBody.put("now", "HK-Private-Node");
    respBody.put("delays", proxiesMap);
    respBody.put("chains", Arrays.asList("HK-Private-Node", "VoWiFi", "DIRECT"));

    NetworkLogBuffer netBuf = new NetworkLogBuffer(10);
    netBuf.recordHttp("clash_http", "PUT", "http://127.0.0.1:19090/proxies/VoWiFi", 204, 12L,
        reqHdrs, reqBody, Collections.singletonMap("Server", "mihomo"), respBody, null);
    Map<String, Object> rpcReq = new LinkedHashMap<>();
    rpcReq.put("id", 7);
    rpcReq.put("method", "dial");
    rpcReq.put("params", Collections.singletonMap("number", "+447700900123"));
    Map<String, Object> rpcResp = new LinkedHashMap<>();
    rpcResp.put("id", 7);
    rpcResp.put("result", Collections.singletonMap("stage", "DIALING"));
    netBuf.recordRpc(1, "dial", 7L, 25L, rpcReq, rpcResp, null);
    eq(2, netBuf.size(), "network log buffer count");
    String netJson = JsonCodec.encode(netBuf.snapshot(10));
    check(!netJson.contains("my-clash-secret-1234"), "auth header hidden");
    check(!netJson.contains("HK-Private-Node"), "proxy node hidden in body/delays/chains");
    check(!netJson.contains("+447700900123"), "RPC dialed number hidden");
    check(netJson.contains("\"iccid\":\"[Hidden]\""), "iccid key preserved as [Hidden]");
    check(netJson.contains("\"number\":\"[Hidden]\""), "RPC param key preserved as [Hidden]");
    check(netJson.contains("\"stage\":\"DIALING\""), "RPC response structure preserved");
    check(netJson.contains("\"carrierName\":\"EE\""), "carrierName preserved");
    check(netJson.contains("http://127.0.0.1:19090/proxies/VoWiFi"), "local Clash URL kept");

    // 3. Ring buffer eviction and sanitization
    EventRingBuffer buf = new EventRingBuffer(3);
    buf.recordAt(1000L, "I", "policy", "evt1");
    buf.recordAt(2000L, "W", "esim", "switched peer=+447700900123");
    buf.recordAt(3000L, "I", "ims", "registered");
    buf.recordAt(4000L, "E", "clash", "failed url https://sub.example.com/key");
    eq(3, buf.size(), "ring buffer size capped");
    String dumped = buf.formatAll(true);
    check(!dumped.contains("evt1"), "oldest evicted");
    check(!dumped.contains("+447700900123"), "ring buffer phone redacted");
    check(!dumped.contains("sub.example.com"), "ring buffer url redacted");
    check(dumped.contains(LogSanitizer.HIDDEN), "ring buffer uses [Hidden]");
    check(dumped.contains("registered"), "non-sensitive message kept");

    // 4. Full v2 telemetry payload with logId, SIM country/carrier, network logs & rawLogs
    String logId = LogSanitizer.generateLogId(1763890000000L);
    check(logId.matches("SVW-\\d{8}-[0-9A-F]{6}"), "logId format: " + logId);
    LogSanitizer.DeviceInfo dev = new LogSanitizer.DeviceInfo(
        "Xiaomi", "Xiaomi", "24129PN74C", "Xiaomi 15", "24129PN74C",
        "dada", "dada", "qcom", "kalama", "Qualcomm", "SM8750", 8, 16384L, 6200L,
        "1200x2670", 480, "HyperOS OS4.0 (4.0.4.0.XOCCNXM.D02)", "AQ3A.250226.002",
        37, "17", "2026-11-05", "4.0.4.0.XOCCNXM.D02",
        "MPSS.DE.7.0.c1-00012", "1.19.0", "CN",
        "arm64-v8a", Collections.singletonList("arm64-v8a"),
        "zh-CN", "Asia/Shanghai", "6.6.30-android17",
        "Linux version 6.6.30-android17-g123456 (user@example.com) #1 SMP PREEMPT",
        "Xiaomi/dada/dada:17/release-keys");
    LogSanitizer.ClashTelemetry clash = new LogSanitizer.ClashTelemetry(
        true, true, true, true, 42, false, 19090, "netns-1", "");
    LogSanitizer.SimTelemetry sim = new LogSanitizer.SimTelemetry(
        1, 5, 1, true, 5, 10, "IDENTITY_READY", Collections.emptyList(),
        "gb", "EE", "EE WiFiCall", "cn", "China Mobile", "46000", "23433", 1839,
        true, false, LogSanitizer.classifyEgress("wfctun0", true), "wfctun0",
        true, true, true, "REGISTERED", true, "CONNECTED", false, 0, true, true, false, "");
    LogSanitizer.ServiceTelemetry svc = new LogSanitizer.ServiceTelemetry(
        true, true, "网络通话已就绪", false, 18, clash, Collections.singletonList(sim));
    String uuid = "123e4567-e89b-42d3-a456-426614174000";
    String rawLogs = "=== ENGINE LOGS ===\nIKE_SA_INIT ok iccid=8944305293606853618 peer=203.0.113.9\n";
    String payload = LogSanitizer.buildTelemetryPayload(
        logId, uuid, "manual", 1763890000000L, "0.4.6-dev", 17, dev, svc,
        Collections.singletonMap("iccid", "8944305293606853618"),
        Collections.emptyList(), buf.snapshot(60), netBuf.snapshot(10),
        Collections.emptyList(), rawLogs);
    Map<String, Object> parsed = JsonCodec.decodeObject(payload);
    eq(LogSanitizer.SCHEMA, parsed.get("schema"), "schema v2");
    eq(logId, parsed.get("logId"), "logId preserved");
    eq(uuid, parsed.get("installId"), "installId");
    eq("manual", parsed.get("trigger"), "trigger");
    Map<String, Object> devOut = (Map<String, Object>) parsed.get("device");
    eq("Xiaomi 15", devOut.get("marketName"), "marketName");
    eq("24129PN74C", devOut.get("certModel"), "certModel");
    eq("Qualcomm", devOut.get("socManufacturer"), "socManufacturer");
    eq("SM8750", devOut.get("socModel"), "socModel");
    eq(8L, devOut.get("cpuCores"), "cpuCores");
    eq(16384L, devOut.get("totalRamMb"), "totalRamMb");
    eq("1200x2670", devOut.get("screenResolution"), "screenResolution");
    eq(480L, devOut.get("screenDensityDpi"), "screenDensityDpi");
    eq("HyperOS OS4.0 (4.0.4.0.XOCCNXM.D02)", devOut.get("osVersion"), "osVersion");
    eq("MPSS.DE.7.0.c1-00012", devOut.get("basebandVersion"), "basebandVersion");
    eq("1.19.0", devOut.get("hardwareRevision"), "hardwareRevision");
    eq("CN", devOut.get("hardwareRegion"), "hardwareRegion");
    check(String.valueOf(devOut.get("kernelFull")).contains("Linux version 6.6.30"), "kernelFull preserved");
    check(!String.valueOf(devOut.get("kernelFull")).contains("user@example.com"), "email in kernel string sanitized");
    Map<String, Object> svcOut = (Map<String, Object>) parsed.get("service");
    List<Map<String, Object>> simsOut = (List<Map<String, Object>>) svcOut.get("sims");
    eq("GB", simsOut.get(0).get("countryIso"), "SIM countryIso");
    eq("EE", simsOut.get(0).get("carrierName"), "SIM carrierName");
    eq("EE WiFiCall", simsOut.get(0).get("simOperatorName"), "SIM operator name");
    eq("CN", simsOut.get(0).get("networkCountryIso"), "network countryIso");
    eq(true, simsOut.get(0).get("isEmbedded"), "SIM isEmbedded");
    eq(LogSanitizer.HIDDEN, simsOut.get(0).get("iccid"), "SIM iccid displayed as [Hidden]");
    check(!payload.contains("8944305293606853618"), "no raw iccid in payload");
    check(!payload.contains("203.0.113.9"), "no raw public IP in rawLogs");
    check(String.valueOf(parsed.get("rawLogs")).contains("IKE_SA_INIT ok"), "rawLogs preserved");
    check(String.valueOf(parsed.get("rawLogs")).contains("[Hidden]"), "rawLogs redacts with [Hidden]");

    // 5. Endpoint validation
    eq("https://example.workers.dev/v1/telemetry",
        TelemetryClient.normalizeAndValidateEndpoint("https://example.workers.dev"), "auto append path");
    eq("https://example.workers.dev/v1/telemetry",
        TelemetryClient.normalizeAndValidateEndpoint("https://example.workers.dev/"), "auto append slash path");
    eq("https://example.workers.dev/custom",
        TelemetryClient.normalizeAndValidateEndpoint("https://example.workers.dev/custom"), "preserve explicit path");
    for (String bad : new String[] {"", "   ", "http://example.com/v1/telemetry",
        "ftp://example.com/v1", "https://user:pass@example.com/v1/telemetry"}) {
      boolean threw = false;
      try { TelemetryClient.normalizeAndValidateEndpoint(bad); }
      catch (IllegalArgumentException expected) { threw = true; }
      check(threw, "rejected bad endpoint: " + bad);
    }

    // 6. Loopback HTTP upload end-to-end test verifying logId header & response parsing
    AtomicReference<String> seenBody = new AtomicReference<>("");
    AtomicReference<String> seenSchema = new AtomicReference<>("");
    AtomicReference<String> seenLogId = new AtomicReference<>("");
    AtomicReference<String> seenKey = new AtomicReference<>("");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/telemetry", exchange -> {
      seenSchema.set(exchange.getRequestHeaders().getFirst("X-SmartVoWifiCall-Schema"));
      seenLogId.set(exchange.getRequestHeaders().getFirst("X-SmartVoWifiCall-Log-Id"));
      seenKey.set(exchange.getRequestHeaders().getFirst("X-Telemetry-Key"));
      try (InputStream in = exchange.getRequestBody()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        int n;
        while ((n = in.read(tmp)) != -1) out.write(tmp, 0, n);
        seenBody.set(new String(out.toByteArray(), StandardCharsets.UTF_8));
      }
      byte[] resp = ("{\"ok\":true,\"code\":\"TELEMETRY_STORED\",\"logId\":\"" + logId
          + "\",\"path\":\"logs/2026/11/23/" + logId + ".json\"}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, resp.length);
      try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      TelemetryClient.UploadResult res = TelemetryClient.upload(url, payload, "k-test", logId);
      check(res.ok, "loopback upload ok");
      eq(200, res.httpStatus, "http 200");
      eq("TELEMETRY_STORED", res.code, "result code");
      eq(logId, res.logId, "returned logId");
      eq("logs/2026/11/23/" + logId + ".json", res.recordPath, "record path");
      eq(LogSanitizer.SCHEMA, seenSchema.get(), "schema header");
      eq(logId, seenLogId.get(), "logId header");
      eq("k-test", seenKey.get(), "key header");
      eq(payload, seenBody.get(), "exact payload posted");
    } finally {
      server.stop(0);
    }

    System.out.println("PASS " + passed + " telemetry sanitizer & upload assertions; loopback only.");
  }
}
