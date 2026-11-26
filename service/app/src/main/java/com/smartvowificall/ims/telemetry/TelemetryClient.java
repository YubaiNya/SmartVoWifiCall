package com.smartvowificall.ims.telemetry;

import com.smartvowificall.ims.engine.JsonCodec;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pure-Java HTTPS uploader for sanitized telemetry payloads targeting the Cloudflare Worker relay.
 * Supports both normal system routing ({@link #upload}) and isolated Wi-Fi Calling netns routing
 * ({@link #uploadViaDns}) with direct UDP DNS resolution and full TLS hostname verification.
 */
public final class TelemetryClient {
  public static final int MAX_PAYLOAD_BYTES = 512 * 1024;
  private static final int CONNECT_TIMEOUT_MS = 12000;
  private static final int READ_TIMEOUT_MS = 25000;
  private static final int DNS_TIMEOUT_MS = 4000;
  private static final SecureRandom RANDOM = new SecureRandom();

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
    byte[] body = validateBody(payloadJson);

    long started = System.nanoTime();
    HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
    conn.setInstanceFollowRedirects(false);
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("User-Agent", "SmartVoWifiCall-Telemetry/3.0");
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
      return buildResult(status, responseText, respHeaders, logIdHint, body.length, durationMs);
    } finally {
      conn.disconnect();
    }
  }

  /**
   * Uploads directly over TCP/TLS using an explicit UDP DNS resolver (e.g. {@code 1.1.1.1:53}
   * hijacked by mihomo inside the isolated Wi-Fi Calling netns), bypassing Android's root-netns
   * {@code netd} resolver while preserving full TLS SNI and hostname certificate verification.
   */
  public static UploadResult uploadViaDns(String endpointUrl, String payloadJson,
      String optionalKey, String logIdHint, String dnsServerIp, int dnsPort) throws IOException {
    String urlText = normalizeAndValidateEndpoint(endpointUrl);
    byte[] body = validateBody(payloadJson);
    URI uri = URI.create(urlText);
    boolean https = "https".equalsIgnoreCase(uri.getScheme());
    String host = uri.getHost();
    int port = uri.getPort() > 0 ? uri.getPort() : (https ? 443 : 80);
    String path = uri.getRawPath();
    if (path == null || path.isEmpty()) path = "/";
    if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
      path = path + "?" + uri.getRawQuery();
    }

    long started = System.nanoTime();
    String targetIp;
    if ("localhost".equalsIgnoreCase(host)) {
      targetIp = "127.0.0.1";
    } else if (isIpv4Literal(host)) {
      targetIp = host;
    } else {
      targetIp = resolveAOverUdp(host, dnsServerIp, dnsPort, DNS_TIMEOUT_MS);
    }

    Socket rawSocket = new Socket();
    Socket ioSocket = rawSocket;
    try {
      try {
        rawSocket.connect(new InetSocketAddress(InetAddress.getByName(targetIp), port),
            CONNECT_TIMEOUT_MS);
      } catch (java.net.SocketTimeoutException e) {
        throw new IOException("CONNECT_TIMEOUT", e);
      }
      rawSocket.setSoTimeout(READ_TIMEOUT_MS);
      if (https) {
        try {
          SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
              .createSocket(rawSocket, host, port, true);
          SSLParameters params = ssl.getSSLParameters();
          params.setEndpointIdentificationAlgorithm("HTTPS");
          if (!isIpv4Literal(host)) {
            params.setServerNames(Collections.singletonList(new SNIHostName(host)));
          }
          ssl.setSSLParameters(params);
          ssl.startHandshake();
          ioSocket = ssl;
        } catch (java.net.SocketTimeoutException e) {
          throw new IOException("TLS_TIMEOUT", e);
        } catch (javax.net.ssl.SSLException e) {
          throw new IOException("TLS_HANDSHAKE_FAILED", e);
        }
      }

      String hostHeader = (port == (https ? 443 : 80)) ? host : host + ":" + port;
      StringBuilder req = new StringBuilder(512);
      req.append("POST ").append(path).append(" HTTP/1.1\r\n");
      req.append("Host: ").append(hostHeader).append("\r\n");
      req.append("Content-Type: application/json; charset=utf-8\r\n");
      req.append("Content-Length: ").append(body.length).append("\r\n");
      req.append("Accept: application/json\r\n");
      req.append("User-Agent: SmartVoWifiCall-Telemetry/3.0\r\n");
      req.append("X-SmartVoWifiCall-Schema: ").append(LogSanitizer.SCHEMA).append("\r\n");
      if (logIdHint != null && !logIdHint.trim().isEmpty()) {
        req.append("X-SmartVoWifiCall-Log-Id: ").append(logIdHint.trim()).append("\r\n");
      }
      if (optionalKey != null && !optionalKey.trim().isEmpty()) {
        req.append("X-Telemetry-Key: ").append(optionalKey.trim()).append("\r\n");
      }
      req.append("Connection: close\r\n\r\n");

      OutputStream out = ioSocket.getOutputStream();
      out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
      out.write(body);
      out.flush();

      BufferedInputStream in = new BufferedInputStream(ioSocket.getInputStream());
      String statusLine;
      try {
        statusLine = readHttpLine(in);
      } catch (java.net.SocketTimeoutException e) {
        throw new IOException("READ_TIMEOUT", e);
      }
      if (statusLine == null || !statusLine.startsWith("HTTP/")) {
        throw new IOException("TELEMETRY_HTTP_INVALID");
      }
      String[] statusParts = statusLine.split("\\s+");
      int status;
      try {
        status = statusParts.length >= 2 ? Integer.parseInt(statusParts[1]) : 0;
      } catch (NumberFormatException e) {
        throw new IOException("TELEMETRY_HTTP_INVALID");
      }
      Map<String, Object> respHeaders = new LinkedHashMap<>();
      boolean chunked = false;
      int contentLength = -1;
      String line;
      while ((line = readHttpLine(in)) != null && !line.isEmpty()) {
        int colon = line.indexOf(':');
        if (colon <= 0) continue;
        String k = line.substring(0, colon).trim();
        String v = line.substring(colon + 1).trim();
        respHeaders.put(k, v);
        String kl = k.toLowerCase(Locale.US);
        if ("transfer-encoding".equals(kl) && v.toLowerCase(Locale.US).contains("chunked")) {
          chunked = true;
        } else if ("content-length".equals(kl)) {
          try { contentLength = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
        }
      }
      String responseText = chunked ? readChunkedBody(in, 8192)
          : readFixedOrEofBody(in, contentLength, 8192);
      long durationMs = (System.nanoTime() - started) / 1_000_000L;
      return buildResult(status, responseText, respHeaders, logIdHint, body.length, durationMs);
    } finally {
      try { ioSocket.close(); } catch (IOException ignored) {}
      if (ioSocket != rawSocket) {
        try { rawSocket.close(); } catch (IOException ignored) {}
      }
    }
  }

  /** Minimal RFC 1035 DNS A-record resolver over UDP so resolution follows the active netns. */
  public static String resolveAOverUdp(String host, String dnsServerIp, int dnsPort, int timeoutMs)
      throws IOException {
    if (host == null || host.isEmpty()) throw new IOException("DNS_INVALID_HOST");
    int txId = RANDOM.nextInt(0x10000);
    ByteArrayOutputStream q = new ByteArrayOutputStream();
    q.write((txId >> 8) & 0xFF);
    q.write(txId & 0xFF);
    q.write(0x01); q.write(0x00); // RD=1
    q.write(0x00); q.write(0x01); // QDCOUNT=1
    q.write(0x00); q.write(0x00); // ANCOUNT=0
    q.write(0x00); q.write(0x00); // NSCOUNT=0
    q.write(0x00); q.write(0x00); // ARCOUNT=0
    for (String label : host.split("\\.")) {
      byte[] lb = label.getBytes(StandardCharsets.US_ASCII);
      if (lb.length == 0 || lb.length > 63) throw new IOException("DNS_INVALID_HOST");
      q.write(lb.length);
      q.write(lb);
    }
    q.write(0x00);
    q.write(0x00); q.write(0x01); // QTYPE=A
    q.write(0x00); q.write(0x01); // QCLASS=IN
    byte[] query = q.toByteArray();

    IOException lastErr = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try (DatagramSocket sock = new DatagramSocket()) {
        sock.setSoTimeout(timeoutMs);
        InetAddress dnsAddr = InetAddress.getByName(dnsServerIp);
        sock.send(new DatagramPacket(query, query.length, dnsAddr, dnsPort));
        byte[] buf = new byte[1024];
        DatagramPacket resp = new DatagramPacket(buf, buf.length);
        sock.receive(resp);
        int len = resp.getLength();
        if (len < 12) throw new IOException("DNS_RESPONSE_SHORT");
        int respId = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
        if (respId != txId) throw new IOException("DNS_TXID_MISMATCH");
        int rcode = buf[3] & 0x0F;
        if (rcode != 0) throw new IOException("DNS_RCODE_" + rcode);
        int qdCount = ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF);
        int anCount = ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
        int off = 12;
        for (int i = 0; i < qdCount; i++) {
          off = skipDnsName(buf, off, len);
          off += 4;
          if (off > len) throw new IOException("DNS_PARSE_ERROR");
        }
        for (int i = 0; i < anCount; i++) {
          off = skipDnsName(buf, off, len);
          if (off + 10 > len) throw new IOException("DNS_PARSE_ERROR");
          int type = ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
          int cls = ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
          int rdLen = ((buf[off + 8] & 0xFF) << 8) | (buf[off + 9] & 0xFF);
          off += 10;
          if (off + rdLen > len) throw new IOException("DNS_PARSE_ERROR");
          if (type == 1 && cls == 1 && rdLen == 4) {
            byte[] ipBytes = new byte[] {buf[off], buf[off + 1], buf[off + 2], buf[off + 3]};
            return Inet4Address.getByAddress(ipBytes).getHostAddress();
          }
          off += rdLen;
        }
        throw new IOException("DNS_NO_A_RECORD");
      } catch (java.net.SocketTimeoutException e) {
        lastErr = new IOException("DNS_TIMEOUT", e);
      } catch (IOException e) {
        lastErr = e;
        if (attempt == 1) throw e;
      }
    }
    throw lastErr != null ? lastErr : new IOException("DNS_TIMEOUT");
  }

  private static int skipDnsName(byte[] buf, int off, int len) throws IOException {
    int guard = 0;
    while (off < len && guard++ < 128) {
      int b = buf[off] & 0xFF;
      if (b == 0) return off + 1;
      if ((b & 0xC0) == 0xC0) {
        if (off + 2 > len) throw new IOException("DNS_PARSE_ERROR");
        return off + 2;
      }
      off += 1 + b;
    }
    throw new IOException("DNS_PARSE_ERROR");
  }

  private static boolean isIpv4Literal(String host) {
    return host != null && host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
  }

  private static byte[] validateBody(String payloadJson) {
    if (payloadJson == null || payloadJson.isEmpty()) {
      throw new IllegalArgumentException("TELEMETRY_PAYLOAD_EMPTY");
    }
    byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
    if (body.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("TELEMETRY_PAYLOAD_TOO_LARGE");
    }
    return body;
  }

  private static UploadResult buildResult(int status, String responseText,
      Map<String, Object> respHeaders, String logIdHint, int payloadBytes, long durationMs) {
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
    return new UploadResult(ok, status, code, resolvedLogId, path, payloadBytes, durationMs,
        respHeaders, responseText);
  }

  private static String readHttpLine(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int b;
    boolean sawAny = false;
    while ((b = in.read()) != -1) {
      sawAny = true;
      if (b == '\n') break;
      if (b != '\r' && out.size() < 8192) out.write(b);
    }
    if (!sawAny) return null;
    return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
  }

  private static String readFixedOrEofBody(InputStream in, int contentLength, int maxBytes)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[1024];
    int remaining = contentLength >= 0 ? Math.min(contentLength, maxBytes) : maxBytes;
    while (remaining > 0) {
      int n = in.read(buf, 0, Math.min(buf.length, remaining));
      if (n == -1) break;
      out.write(buf, 0, n);
      remaining -= n;
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
  }

  private static String readChunkedBody(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[1024];
    while (true) {
      String sizeLine = readHttpLine(in);
      if (sizeLine == null) break;
      int semi = sizeLine.indexOf(';');
      String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
      if (hex.isEmpty()) continue;
      int chunkSize;
      try { chunkSize = Integer.parseInt(hex, 16); } catch (NumberFormatException e) { break; }
      if (chunkSize <= 0) break;
      int left = chunkSize;
      while (left > 0) {
        int n = in.read(buf, 0, Math.min(buf.length, left));
        if (n == -1) break;
        int space = maxBytes - out.size();
        if (space > 0) out.write(buf, 0, Math.min(n, space));
        left -= n;
      }
      readHttpLine(in); // trailing CRLF
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
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
