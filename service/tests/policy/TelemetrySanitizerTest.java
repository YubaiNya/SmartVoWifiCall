import com.smartvowificall.ims.engine.JsonCodec;
import com.smartvowificall.ims.telemetry.EventRingBuffer;
import com.smartvowificall.ims.telemetry.LogSanitizer;
import com.smartvowificall.ims.telemetry.TelemetryClient;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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

  public static void main(String[] args) throws Exception {
    // 1. Free-text redaction tests
    String dirty = "iccid=8944305293606853618 imsi=234330000000019 imei=351234567890123 "
        + "peer=+447700900123 sip:234330000000019@ims.mnc033.mcc234.3gppnetwork.org "
        + "tel:+8613800138000 url=https://sub.example.com/secret-token/profile.yaml "
        + "currentNode=\"英国WiFicall（家庭）X2\" controllerSecret=\"abc123def456\" "
        + "lan=192.168.1.55 tun=198.19.0.1 loop=127.0.0.1 v6=2a00:23ee:fa48:846c::1";
    String clean = LogSanitizer.sanitizeText(dirty);
    for (String forbidden : new String[] {
        "8944305293606853618", "234330000000019", "351234567890123",
        "+447700900123", "+8613800138000", "sub.example.com", "secret-token",
        "英国WiFicall", "abc123def456", "192.168.1.55", "2a00:23ee:fa48:846c::1"}) {
      check(!clean.contains(forbidden), "redacted: " + forbidden);
    }
    check(clean.contains("198.19.0.1"), "wfctun0 local IP preserved");
    check(clean.contains("127.0.0.1"), "loopback preserved");

    // 2. Ring buffer eviction and sanitization
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
    check(dumped.contains("registered"), "non-sensitive message kept");

    // 3. Strict allowlist telemetry payload
    LogSanitizer.DeviceInfo dev = new LogSanitizer.DeviceInfo("Xiaomi", "24129PN74C", 37, "17", "arm64-v8a");
    LogSanitizer.ClashTelemetry clash = new LogSanitizer.ClashTelemetry(true, true, true, true, 42, false, "");
    LogSanitizer.SimTelemetry sim = new LogSanitizer.SimTelemetry(
        1, true, "23433", LogSanitizer.classifyEgress("wfctun0", true),
        true, true, true, "REGISTERED", true, "CONNECTED", false, 0, true, true, false, "");
    LogSanitizer.ServiceTelemetry svc = new LogSanitizer.ServiceTelemetry(
        true, true, "网络通话已就绪", false, 18, clash, Collections.singletonList(sim));
    String uuid = "123e4567-e89b-42d3-a456-426614174000";
    String payload = LogSanitizer.buildTelemetryPayload(
        uuid, "manual", 1763890000000L, "0.4.6-dev", 17, dev, svc, buf.snapshot(60));
    Map<String, Object> parsed = JsonCodec.decodeObject(payload);
    eq(LogSanitizer.SCHEMA, parsed.get("schema"), "schema");
    eq(uuid, parsed.get("installId"), "installId");
    eq("manual", parsed.get("trigger"), "trigger");
    check(!payload.contains("iccid") && !payload.contains("simKey")
        && !payload.contains("currentNode") && !payload.contains("subscriptionUrl")
        && !payload.contains("controllerSecret"), "no sensitive keys in allowlist payload");

    // 4. Endpoint validation
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

    // 5. Loopback HTTP upload end-to-end test
    AtomicReference<String> seenBody = new AtomicReference<>("");
    AtomicReference<String> seenSchema = new AtomicReference<>("");
    AtomicReference<String> seenKey = new AtomicReference<>("");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/telemetry", exchange -> {
      seenSchema.set(exchange.getRequestHeaders().getFirst("X-SmartVoWifiCall-Schema"));
      seenKey.set(exchange.getRequestHeaders().getFirst("X-Telemetry-Key"));
      try (InputStream in = exchange.getRequestBody()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        int n;
        while ((n = in.read(tmp)) != -1) out.write(tmp, 0, n);
        seenBody.set(new String(out.toByteArray(), StandardCharsets.UTF_8));
      }
      byte[] resp = "{\"ok\":true,\"code\":\"TELEMETRY_STORED\",\"path\":\"logs/2026/11/23/test.json\"}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, resp.length);
      try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      TelemetryClient.UploadResult res = TelemetryClient.upload(url, payload, "k-test");
      check(res.ok, "loopback upload ok");
      eq(200, res.httpStatus, "http 200");
      eq("TELEMETRY_STORED", res.code, "result code");
      eq("logs/2026/11/23/test.json", res.recordPath, "record path");
      eq(LogSanitizer.SCHEMA, seenSchema.get(), "schema header");
      eq("k-test", seenKey.get(), "key header");
      eq(payload, seenBody.get(), "exact payload posted");
    } finally {
      server.stop(0);
    }

    System.out.println("PASS " + passed + " telemetry sanitizer & upload assertions; loopback only.");
  }
}
