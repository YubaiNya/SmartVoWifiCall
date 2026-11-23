package com.smartvowificall.ims.telemetry;

import com.smartvowificall.ims.engine.JsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java HTTPS uploader for sanitized telemetry payloads targeting the Cloudflare Worker relay.
 * Never embeds any GitHub token and strictly validates endpoint scheme and payload size.
 */
public final class TelemetryClient {
  public static final int MAX_PAYLOAD_BYTES = 512 * 1024;
  private static final int CONNECT_TIMEOUT_MS = 10000;
  private static final int READ_TIMEOUT_MS = 20000;

  public static final class UploadResult {
    public final boolean ok;
    public final int httpStatus;
    public final String code;
    public final String logId;
    public final String recordPath;
    public final int payloadBytes;
    public final long durationMs;
    public final Map<String, Object> responseHeaders;
    public final String responseBody;

    public UploadResult(boolean ok, int httpStatus, String code, String logId, String recordPath,
        int payloadBytes, long durationMs, Map<String, Object> responseHeaders,
        String responseBody) {
      this.ok = ok;
      this.httpStatus = httpStatus;
      this.code = code == null ? "" : code;
      this.logId = logId == null ? "" : logId;
      this.recordPath = recordPath == null ? "" : recordPath;
      this.payloadBytes = payloadBytes;
      this.durationMs = durationMs;
      this.responseHeaders = responseHeaders == null ? new LinkedHashMap<>() : responseHeaders;
      this.responseBody = responseBody == null ? "" : responseBody;
    }
  }

  private TelemetryClient() {}

  /** Only HTTPS endpoints (plus loopback http://127.0.0.1 for local tests) are accepted. */
  public static String normalizeAndValidateEndpoint(String raw) {
    if (raw == null) throw new IllegalArgumentException("TELEMETRY_ENDPOINT_EMPTY");
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) throw new IllegalArgumentException("TELEMETRY_ENDPOINT_EMPTY");
    if (trimmed.length() > 512) throw new IllegalArgumentException("TELEMETRY_ENDPOINT_TOO_LONG");
    URI uri;
    try {
      uri = URI.create(trimmed);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("TELEMETRY_ENDPOINT_INVALID");
    }
    String scheme = uri.getScheme();
    String host = uri.getHost();
    if (scheme == null || host == null || host.isEmpty()) {
      throw new IllegalArgumentException("TELEMETRY_ENDPOINT_INVALID");
    }
    boolean https = "https".equalsIgnoreCase(scheme);
    boolean localHttp = "http".equalsIgnoreCase(scheme)
        && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
    if (!https && !localHttp) {
      throw new IllegalArgumentException("TELEMETRY_HTTPS_REQUIRED");
    }
    if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isEmpty()) {
      throw new IllegalArgumentException("TELEMETRY_ENDPOINT_INVALID");
    }
    String path = uri.getRawPath();
    if (path == null || path.isEmpty() || "/".equals(path)) {
      trimmed = trimmed.endsWith("/") ? trimmed + "v1/telemetry" : trimmed + "/v1/telemetry";
    }
    return trimmed;
  }

  public static UploadResult upload(String endpointUrl, String payloadJson, String optionalKey)
      throws IOException {
    return upload(endpointUrl, payloadJson, optionalKey, "");
  }

  public static UploadResult upload(String endpointUrl, String payloadJson, String optionalKey,
      String logIdHint) throws IOException {
    String urlText = normalizeAndValidateEndpoint(endpointUrl);
    if (payloadJson == null || payloadJson.isEmpty()) {
      throw new IllegalArgumentException("TELEMETRY_PAYLOAD_EMPTY");
    }
    byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
    if (body.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("TELEMETRY_PAYLOAD_TOO_LARGE");
    }

    long started = System.nanoTime();
    HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
    conn.setInstanceFollowRedirects(false);
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("User-Agent", "SmartVoWifiCall-Telemetry/2.0");
    conn.setRequestProperty("X-SmartVoWifiCall-Schema", LogSanitizer.SCHEMA);
    if (logIdHint != null && !logIdHint.trim().isEmpty()) {
      conn.setRequestProperty("X-SmartVoWifiCall-Log-Id", logIdHint.trim());
    }
    if (optionalKey != null && !optionalKey.trim().isEmpty()) {
      conn.setRequestProperty("X-Telemetry-Key", optionalKey.trim());
    }

    try {
      try (OutputStream out = conn.getOutputStream()) {
        out.write(body);
      }
      int status = conn.getResponseCode();
      long durationMs = (System.nanoTime() - started) / 1_000_000L;
      Map<String, Object> respHeaders = new LinkedHashMap<>();
      Map<String, List<String>> rawHeaders = conn.getHeaderFields();
      if (rawHeaders != null) {
        for (Map.Entry<String, List<String>> e : rawHeaders.entrySet()) {
          if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
          respHeaders.put(e.getKey(), String.join(", ", e.getValue()));
        }
      }
      InputStream stream = status >= 200 && status < 400
          ? conn.getInputStream() : conn.getErrorStream();
      String responseText = stream == null ? "" : readLimited(stream, 8192);
      String code = status >= 200 && status < 300 ? "TELEMETRY_STORED" : "HTTP_" + status;
      String path = "";
      String resolvedLogId = logIdHint == null ? "" : logIdHint;
      if (!responseText.isEmpty()) {
        try {
          Map<String, Object> parsed = JsonCodec.decodeObject(responseText);
          Object c = parsed.get("code");
          if (c instanceof String && !((String) c).isEmpty()) code = (String) c;
          Object p = parsed.get("path");
          if (p instanceof String) path = (String) p;
          Object id = parsed.get("logId");
          if (id instanceof String && !((String) id).isEmpty()) resolvedLogId = (String) id;
        } catch (IOException ignored) {
        }
      }
      boolean ok = status >= 200 && status < 300;
      return new UploadResult(ok, status, code, resolvedLogId, path, body.length, durationMs,
          respHeaders, responseText);
    } finally {
      conn.disconnect();
    }
  }

  private static String readLimited(InputStream in, int maxBytes) throws IOException {
    try (InputStream stream = in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[1024];
      int n;
      while ((n = stream.read(buf)) != -1) {
        int remaining = maxBytes - out.size();
        if (remaining <= 0) break;
        out.write(buf, 0, Math.min(n, remaining));
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
    }
  }
}
