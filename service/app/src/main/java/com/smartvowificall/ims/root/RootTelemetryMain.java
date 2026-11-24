package com.smartvowificall.ims.root;

import com.smartvowificall.ims.clash.ClashConfig;
import com.smartvowificall.ims.telemetry.TelemetryClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;

/**
 * One-shot root app_process helper invoked inside the isolated Wi-Fi Calling Clash netns
 * (`nsenter --net=/proc/<nspid>/ns/net`) so telemetry uploads travel exclusively through
 * `wfctun0` (with UDP DNS to 1.1.1.1:53 hijacked by mihomo) without requiring a system VPN.
 */
public final class RootTelemetryMain {
  private RootTelemetryMain() {}

  public static void main(String[] args) {
    int exitCode = 0;
    JSONObject out = new JSONObject();
    try {
      if (args.length < 2) throw new IllegalArgumentException("INVALID_ARGUMENT");
      String endpoint = args[0];
      String logId = args[1];
      String payload = readAllLimited(System.in, TelemetryClient.MAX_PAYLOAD_BYTES);
      TelemetryClient.UploadResult r = TelemetryClient.uploadViaDns(
          endpoint, payload, "", logId, ClashConfig.ENGINE_DNS_TARGET, 53);
      JSONObject headers = new JSONObject();
      for (Map.Entry<String, Object> e : r.responseHeaders.entrySet()) {
        headers.put(e.getKey(), String.valueOf(e.getValue()));
      }
      out.put("ok", r.ok)
          .put("httpStatus", r.httpStatus)
          .put("code", r.code)
          .put("logId", r.logId)
          .put("recordPath", r.recordPath)
          .put("payloadBytes", r.payloadBytes)
          .put("durationMs", r.durationMs)
          .put("responseHeaders", headers)
          .put("responseBody", r.responseBody);
    } catch (Throwable t) {
      exitCode = 1;
      String msg = t.getMessage();
      String code = (msg != null && msg.matches("[A-Z0-9_]{3,80}"))
          ? msg : t.getClass().getSimpleName();
      try {
        out.put("ok", false).put("code", code);
      } catch (Exception ignored) {
      }
    }
    System.out.println(out.toString());
    System.out.flush();
    System.exit(exitCode);
  }

  private static String readAllLimited(InputStream in, int maxBytes) throws Exception {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int n;
    while ((n = in.read(chunk)) != -1) {
      if (buf.size() + n > maxBytes) throw new IllegalArgumentException("TELEMETRY_PAYLOAD_TOO_LARGE");
      buf.write(chunk, 0, n);
    }
    return new String(buf.toByteArray(), StandardCharsets.UTF_8);
  }
}
