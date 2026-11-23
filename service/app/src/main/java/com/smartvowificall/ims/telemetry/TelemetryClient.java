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
import java.util.Map;

/**
 * Pure-Java HTTPS uploader for sanitized telemetry payloads targeting the Cloudflare Worker relay.
 * Never embeds any GitHub token and strictly validates endpoint scheme and payload size.
 */
public final class TelemetryClient {
  public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
  private static final int CONNECT_TIMEOUT_MS = 10000;
  private static final int READ_TIMEOUT_MS = 15000;

  public static final class UploadResult {
    public final boolean ok;
    public final int httpStatus;
    public final String code;
    public final String recordPath;

    public UploadResult(boolean ok, int httpStatus, String code, String recordPath) {
      this.ok = ok;
      this.httpStatus = httpStatus;
      this.code = code == null ? "" : code;
      this.recordPath = recordPath == null ? "" : recordPath;
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
    String urlText = normalizeAndValidateEndpoint(endpointUrl);
    if (payloadJson == null || payloadJson.isEmpty()) {
      throw new IllegalArgumentException("TELEMETRY_PAYLOAD_EMPTY");
    }
    byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
    if (body.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("TELEMETRY_PAYLOAD_TOO_LARGE");
    }

    HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
    conn.setInstanceFollowRedirects(false);
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("User-Agent", "SmartVoWifiCall-Telemetry/1.0");
    conn.setRequestProperty("X-SmartVoWifiCall-Schema", LogSanitizer.SCHEMA);
    if (optionalKey != null && !optionalKey.trim().isEmpty()) {
      conn.setRequestProperty("X-Telemetry-Key", optionalKey.trim());
    }

    try {
      try (OutputStream out = conn.getOutputStream()) {
        out.write(body);
      }
      int status = conn.getResponseCode();
      InputStream stream = status >= 200 && status < 400
          ? conn.getInputStream() : conn.getErrorStream();
      String responseText = stream == null ? "" : readLimited(stream, 8192);
      String code = status >= 200 && status < 300 ? "TELEMETRY_STORED" : "HTTP_" + status;
      String path = "";
      if (!responseText.isEmpty()) {
        try {
          Map<String, Object> parsed = JsonCodec.decodeObject(responseText);
          Object c = parsed.get("code");
          if (c instanceof String && !((String) c).isEmpty()) code = (String) c;
          Object p = parsed.get("path");
          if (p instanceof String) path = (String) p;
        } catch (IOException ignored) {
        }
      }
      boolean ok = status >= 200 && status < 300;
      return new UploadResult(ok, status, code, path);
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
