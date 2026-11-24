package com.smartvowificall.ims.clash;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java parser extracting an HTTP(S) Clash subscription URL from QR code contents.
 * Supports plain URLs, Clash/Mihomo/FlClash install-config deep links, Base64 {@code sub://} links,
 * and multi-line text containing a subscription URL.
 */
public final class SubscriptionQrParser {
  private static final Pattern HTTP_URL_RE =
      Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

  private SubscriptionQrParser() {}

  /**
   * Returns a normalized HTTP(S) subscription URL extracted from {@code rawQr}, or {@code null}
   * when the QR payload does not contain a valid Clash subscription URL.
   */
  public static String extractSubscriptionUrl(String rawQr) {
    if (rawQr == null) return null;
    String text = rawQr.trim();
    if (text.isEmpty() || text.length() > 8192) return null;

    String lower = text.toLowerCase(Locale.US);
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
      return isValidHttpUrl(text) ? text : null;
    }

    // Handle clash://install-config?url=..., clashmeta://..., mihomo://..., flclash://...
    if (lower.startsWith("clash://") || lower.startsWith("clashmeta://")
        || lower.startsWith("mihomo://") || lower.startsWith("flclash://")) {
      String fromQuery = extractQueryParam(text, "url");
      if (fromQuery != null && isValidHttpUrl(fromQuery)) {
        return fromQuery;
      }
    }

    // Handle sub://<base64-encoded-url>
    if (lower.startsWith("sub://")) {
      String encoded = text.substring(6).trim();
      int q = encoded.indexOf('?');
      if (q >= 0) encoded = encoded.substring(0, q);
      int hash = encoded.indexOf('#');
      if (hash >= 0) encoded = encoded.substring(0, hash);
      String decoded = decodeBase64Url(encoded);
      if (decoded != null && isValidHttpUrl(decoded)) {
        return decoded;
      }
    }

    // Fallback: extract an embedded http(s):// URL from plain text
    Matcher m = HTTP_URL_RE.matcher(text);
    if (m.find()) {
      String candidate = m.group().trim();
      if (isValidHttpUrl(candidate)) return candidate;
    }
    return null;
  }

  private static String extractQueryParam(String rawUri, String key) {
    int q = rawUri.indexOf('?');
    if (q < 0 || q + 1 >= rawUri.length()) return null;
    String query = rawUri.substring(q + 1);
    int hash = query.indexOf('#');
    if (hash >= 0) query = query.substring(0, hash);
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      String k = pair.substring(0, eq).trim();
      if (!key.equalsIgnoreCase(k)) continue;
      String rawVal = pair.substring(eq + 1);
      try {
        return URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name()).trim();
      } catch (Exception ignored) {
        return rawVal.trim();
      }
    }
    return null;
  }

  private static String decodeBase64Url(String raw) {
    if (raw == null || raw.isEmpty()) return null;
    String normalized = raw.replace('-', '+').replace('_', '/');
    int mod = normalized.length() % 4;
    if (mod == 2) normalized += "==";
    else if (mod == 3) normalized += "=";
    else if (mod == 1) return null;
    try {
      return new String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8).trim();
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static boolean isValidHttpUrl(String candidate) {
    if (candidate == null || candidate.isEmpty() || candidate.length() > 4096) return false;
    String lower = candidate.toLowerCase(Locale.US);
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
    if (candidate.contains("\n") || candidate.contains("\r") || candidate.contains(" ")) return false;
    try {
      URI u = URI.create(candidate);
      String host = u.getHost();
      return host != null && !host.isEmpty();
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
