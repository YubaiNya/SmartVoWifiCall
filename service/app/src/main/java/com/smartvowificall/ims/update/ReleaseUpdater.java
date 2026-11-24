package com.smartvowificall.ims.update;

import com.smartvowificall.ims.engine.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks GitHub Releases for newer SmartVoWifiCall versions and streams module ZIP assets
 * directly over HTTPS without launching an external browser.
 */
public final class ReleaseUpdater {
  public static final String REPO_OWNER = "YubaiNya";
  public static final String REPO_NAME = "SmartVoWifiCall";
  public static final String DEFAULT_RELEASES_API =
      "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases?per_page=10";
  public static final String REPO_RELEASES_WEB =
      "https://github.com/" + REPO_OWNER + "/" + REPO_NAME + "/releases";

  private static final int MAX_REDIRECTS = 5;
  private static final int MAX_API_BODY_BYTES = 512 * 1024;
  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int API_READ_TIMEOUT_MS = 20_000;
  private static final int DOWNLOAD_READ_TIMEOUT_MS = 30_000;

  private ReleaseUpdater() {}

  public interface ProgressListener {
    void onProgress(long downloadedBytes, long totalBytes);
  }

  public static final class ReleaseInfo {
    public final String tagName;
    public final String name;
    public final String body;
    public final String htmlUrl;
    public final String publishedAt;
    public final boolean prerelease;
    public final String moduleAssetName;
    public final String moduleDownloadUrl;
    public final long moduleSizeBytes;

    public ReleaseInfo(
        String tagName,
        String name,
        String body,
        String htmlUrl,
        String publishedAt,
        boolean prerelease,
        String moduleAssetName,
        String moduleDownloadUrl,
        long moduleSizeBytes) {
      this.tagName = tagName == null ? "" : tagName.trim();
      this.name = name == null ? "" : name.trim();
      this.body = body == null ? "" : body.trim();
      this.htmlUrl = htmlUrl == null ? "" : htmlUrl.trim();
      this.publishedAt = publishedAt == null ? "" : publishedAt.trim();
      this.prerelease = prerelease;
      this.moduleAssetName = moduleAssetName == null ? "" : moduleAssetName.trim();
      this.moduleDownloadUrl = moduleDownloadUrl == null ? "" : moduleDownloadUrl.trim();
      this.moduleSizeBytes = Math.max(0L, moduleSizeBytes);
    }

    public boolean hasModuleAsset() {
      return !moduleDownloadUrl.isEmpty();
    }
  }

  public static final class CheckResult {
    public final boolean ok;
    public final boolean hasUpdate;
    public final String currentVersion;
    public final ReleaseInfo latest;
    public final String error;

    public CheckResult(
        boolean ok, boolean hasUpdate, String currentVersion, ReleaseInfo latest, String error) {
      this.ok = ok;
      this.hasUpdate = hasUpdate;
      this.currentVersion = currentVersion == null ? "" : currentVersion.trim();
      this.latest = latest;
      this.error = error == null ? "" : error.trim();
    }

    public static CheckResult failure(String currentVersion, String error) {
      return new CheckResult(false, false, currentVersion, null, error);
    }
  }

  public static boolean isTrustedGitHubUrl(String rawUrl) {
    return isAllowedUrl(rawUrl, false);
  }

  static boolean isAllowedUrl(String rawUrl, boolean allowLoopbackHttp) {
    if (rawUrl == null) return false;
    String trimmed = rawUrl.trim();
    if (trimmed.isEmpty()) return false;
    try {
      URI uri = URI.create(trimmed);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
      if (host.isEmpty()) return false;
      if (allowLoopbackHttp && "http".equals(scheme)
          && ("127.0.0.1".equals(host) || "localhost".equals(host))) {
        return true;
      }
      if (!"https".equals(scheme)) return false;
      return "github.com".equals(host)
          || "api.github.com".equals(host)
          || host.endsWith(".github.com")
          || host.endsWith(".githubusercontent.com");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Compares two semantic version strings (with optional leading 'v' and pre-release tags).
   * Returns positive if a > b, 0 if equal, negative if a < b.
   * Examples: 0.6.0 > 0.6.0-dev; 0.6.1-dev > 0.6.0; 0.6.0-dev.10 > 0.6.0-dev.2.
   */
  public static int compareVersions(String a, String b) {
    ParsedVersion va = parseVersion(a);
    ParsedVersion vb = parseVersion(b);
    int maxCore = Math.max(va.core.size(), vb.core.size());
    for (int i = 0; i < maxCore; i++) {
      int na = i < va.core.size() ? va.core.get(i) : 0;
      int nb = i < vb.core.size() ? vb.core.get(i) : 0;
      if (na != nb) return Integer.compare(na, nb);
    }
    boolean aHasPre = !va.pre.isEmpty();
    boolean bHasPre = !vb.pre.isEmpty();
    if (!aHasPre && !bHasPre) return 0;
    if (!aHasPre) return 1; // stable > prerelease
    if (!bHasPre) return -1;
    int maxPre = Math.max(va.pre.size(), vb.pre.size());
    for (int i = 0; i < maxPre; i++) {
      if (i >= va.pre.size()) return -1;
      if (i >= vb.pre.size()) return 1;
      String pa = va.pre.get(i);
      String pb = vb.pre.get(i);
      if (pa.equals(pb)) continue;
      boolean aNum = isDigits(pa);
      boolean bNum = isDigits(pb);
      if (aNum && bNum) {
        int cmp = compareNumericStrings(pa, pb);
        if (cmp != 0) return cmp;
      } else if (aNum != bNum) {
        return aNum ? -1 : 1;
      } else {
        int cmp = pa.compareTo(pb);
        if (cmp != 0) return cmp;
      }
    }
    return 0;
  }

  public static boolean isNewerVersion(String remoteTag, String currentVersion) {
    if (remoteTag == null || remoteTag.trim().isEmpty()) return false;
    if (currentVersion == null || currentVersion.trim().isEmpty()) return true;
    return compareVersions(remoteTag, currentVersion) > 0;
  }

  /**
   * Builds a clean filename for saving under Download/, embedding the release tag when the
   * upstream asset name does not already include it (e.g. SmartVoWifiCall-module-v0.7.0.zip).
   */
  public static String buildDownloadFileName(ReleaseInfo release) {
    String rawName = release != null ? release.moduleAssetName : "";
    String safeName = sanitizeFileSegment(rawName);
    if (safeName.isEmpty()) safeName = "SmartVoWifiCall-module.zip";
    if (!safeName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
      safeName = safeName + ".zip";
    }
    String tag = release != null ? sanitizeFileSegment(release.tagName) : "";
    if (tag.isEmpty()) return safeName;
    String normalizedTag = tag.startsWith("v") || tag.startsWith("V") ? tag : "v" + tag;
    String lowerName = safeName.toLowerCase(Locale.ROOT);
    if (lowerName.contains(normalizedTag.toLowerCase(Locale.ROOT))
        || lowerName.contains(tag.toLowerCase(Locale.ROOT))) {
      return safeName;
    }
    String base = safeName.substring(0, safeName.length() - 4);
    return base + "-" + normalizedTag + ".zip";
  }

  public static ReleaseInfo parseLatestRelease(String jsonArrayText) throws IOException {
    return parseLatestRelease(jsonArrayText, false);
  }

  @SuppressWarnings("unchecked")
  static ReleaseInfo parseLatestRelease(String jsonArrayText, boolean allowLoopbackHttp)
      throws IOException {
    if (jsonArrayText == null || jsonArrayText.trim().isEmpty()) {
      throw new IOException("GitHub 返回内容为空");
    }
    try {
      Object decoded = JsonCodec.decode(jsonArrayText.trim());
      List<?> items;
      if (decoded instanceof List) {
        items = (List<?>) decoded;
      } else if (decoded instanceof Map) {
        items = Collections.singletonList(decoded);
      } else {
        throw new IOException("JSON 顶层格式无效");
      }
      ReleaseInfo best = null;
      for (Object obj : items) {
        if (!(obj instanceof Map)) continue;
        Map<String, Object> item = (Map<String, Object>) obj;
        if (optBool(item, "draft")) continue;
        String tag = optStr(item, "tag_name");
        if (tag.isEmpty()) continue;
        String name = optStr(item, "name");
        if (name.isEmpty()) name = tag;
        String body = optStr(item, "body");
        String htmlUrl = optStr(item, "html_url");
        if (!isAllowedUrl(htmlUrl, allowLoopbackHttp)) {
          htmlUrl = REPO_RELEASES_WEB;
        }
        String publishedAt = optStr(item, "published_at");
        boolean prerelease = optBool(item, "prerelease");

        String assetName = "";
        String assetUrl = "";
        long assetSize = 0L;
        int assetRank = -1;
        Object assetsObj = item.get("assets");
        if (assetsObj instanceof List) {
          for (Object aObj : (List<?>) assetsObj) {
            if (!(aObj instanceof Map)) continue;
            Map<String, Object> a = (Map<String, Object>) aObj;
            String aname = optStr(a, "name");
            String aurl = optStr(a, "browser_download_url");
            if (!isAllowedUrl(aurl, allowLoopbackHttp)) continue;
            int rank = rankModuleAsset(aname);
            if (rank > assetRank) {
              assetRank = rank;
              assetName = aname;
              assetUrl = aurl;
              assetSize = Math.max(0L, optLong(a, "size"));
            }
          }
        }

        ReleaseInfo candidate = new ReleaseInfo(
            tag, name, body, htmlUrl, publishedAt, prerelease, assetName, assetUrl, assetSize);
        if (best == null || compareVersions(candidate.tagName, best.tagName) > 0) {
          best = candidate;
        }
      }
      return best;
    } catch (Exception e) {
      throw new IOException("解析 Release 信息失败：" + e.getMessage(), e);
    }
  }

  private static String optStr(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v instanceof String ? ((String) v).trim() : "";
  }

  private static boolean optBool(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v instanceof Boolean && (Boolean) v;
  }

  private static long optLong(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v instanceof Number ? ((Number) v).longValue() : 0L;
  }

  public static CheckResult fetchLatestRelease(String apiUrl, String currentVersion) {
    return fetchLatestRelease(apiUrl, currentVersion, false);
  }

  static CheckResult fetchLatestRelease(
      String apiUrl, String currentVersion, boolean allowLoopbackHttp) {
    if (!isAllowedUrl(apiUrl, allowLoopbackHttp)) {
      return CheckResult.failure(currentVersion, "无效的更新地址");
    }
    HttpURLConnection conn = null;
    try {
      conn = openCheckedConnection(apiUrl, currentVersion, true, allowLoopbackHttp);
      int code = conn.getResponseCode();
      if (code == 403 || code == 429) {
        return CheckResult.failure(currentVersion, "GitHub API 访问频率受限，请稍后重试或直接前往 GitHub");
      }
      if (code == 404) {
        return CheckResult.failure(currentVersion, "未找到 GitHub Release 仓库");
      }
      if (code < 200 || code >= 300) {
        return CheckResult.failure(currentVersion, "GitHub 返回 HTTP " + code);
      }
      String body = readUtf8Limited(conn.getInputStream(), MAX_API_BODY_BYTES);
      ReleaseInfo latest = parseLatestRelease(body, allowLoopbackHttp);
      if (latest == null) {
        return new CheckResult(true, false, currentVersion, null, "暂无已发布的 Release");
      }
      boolean newer = isNewerVersion(latest.tagName, currentVersion);
      return new CheckResult(true, newer, currentVersion, latest, "");
    } catch (IOException e) {
      String msg = e.getMessage();
      return CheckResult.failure(
          currentVersion, msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg);
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  public static long downloadModuleToStream(
      String downloadUrl,
      String currentVersion,
      long expectedSizeBytes,
      OutputStream output,
      AtomicBoolean cancelled,
      ProgressListener listener) throws IOException {
    return downloadModuleToStream(
        downloadUrl, currentVersion, expectedSizeBytes, output, cancelled, listener, false);
  }

  static long downloadModuleToStream(
      String downloadUrl,
      String currentVersion,
      long expectedSizeBytes,
      OutputStream output,
      AtomicBoolean cancelled,
      ProgressListener listener,
      boolean allowLoopbackHttp) throws IOException {
    if (!isAllowedUrl(downloadUrl, allowLoopbackHttp)) {
      throw new IOException("下载地址不受信任");
    }
    HttpURLConnection conn = null;
    try {
      conn = openCheckedConnection(downloadUrl, currentVersion, false, allowLoopbackHttp);
      int code = conn.getResponseCode();
      if (code < 200 || code >= 300) {
        throw new IOException("下载失败：HTTP " + code);
      }
      long contentLength = conn.getContentLengthLong();
      long totalBytes = contentLength > 0L ? contentLength : Math.max(0L, expectedSizeBytes);

      byte[] buf = new byte[16 * 1024];
      byte[] magic = new byte[4];
      int magicRead = 0;
      long downloaded = 0L;
      long lastNotifyMs = 0L;
      try (InputStream in = conn.getInputStream()) {
        int n;
        while ((n = in.read(buf)) != -1) {
          if (cancelled != null && cancelled.get()) {
            throw new IOException("已取消下载");
          }
          if (magicRead < 4) {
            int copy = Math.min(4 - magicRead, n);
            System.arraycopy(buf, 0, magic, magicRead, copy);
            magicRead += copy;
            if (magicRead == 4 && !isZipHeader(magic)) {
              throw new IOException("下载内容不是有效的模块 ZIP 包");
            }
          }
          output.write(buf, 0, n);
          downloaded += n;
          long now = System.currentTimeMillis();
          if (listener != null && (now - lastNotifyMs >= 80L || (totalBytes > 0 && downloaded >= totalBytes))) {
            lastNotifyMs = now;
            listener.onProgress(downloaded, totalBytes);
          }
        }
      }
      if (cancelled != null && cancelled.get()) {
        throw new IOException("已取消下载");
      }
      if (magicRead < 4 || !isZipHeader(magic)) {
        throw new IOException("下载文件不完整或格式无效");
      }
      if (contentLength > 0L && downloaded != contentLength) {
        throw new IOException("下载中断：文件大小不匹配（" + downloaded + "/" + contentLength + "）");
      }
      output.flush();
      if (listener != null) {
        listener.onProgress(downloaded, totalBytes > 0 ? totalBytes : downloaded);
      }
      return downloaded;
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private static HttpURLConnection openCheckedConnection(
      String initialUrl, String currentVersion, boolean isApi, boolean allowLoopbackHttp)
      throws IOException {
    String current = initialUrl;
    String ua = "SmartVoWifiCall/" + (currentVersion == null || currentVersion.isEmpty() ? "dev" : currentVersion);
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      if (!isAllowedUrl(current, allowLoopbackHttp)) {
        throw new IOException("重定向目标不受信任");
      }
      HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
      conn.setInstanceFollowRedirects(false);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(isApi ? API_READ_TIMEOUT_MS : DOWNLOAD_READ_TIMEOUT_MS);
      conn.setRequestProperty("User-Agent", ua);
      if (isApi) {
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
      } else {
        conn.setRequestProperty("Accept", "application/octet-stream");
      }
      int status = conn.getResponseCode();
      if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
        String loc = conn.getHeaderField("Location");
        conn.disconnect();
        if (loc == null || loc.trim().isEmpty()) {
          throw new IOException("重定向缺少目标地址");
        }
        current = URI.create(current).resolve(loc.trim()).toString();
        continue;
      }
      return conn;
    }
    throw new IOException("重定向次数过多");
  }

  private static String readUtf8Limited(InputStream in, int maxBytes) throws IOException {
    try (InputStream s = in) {
      byte[] buf = new byte[8192];
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      int n;
      while ((n = s.read(buf)) != -1) {
        if (out.size() + n > maxBytes) {
          throw new IOException("响应体过大");
        }
        out.write(buf, 0, n);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static int rankModuleAsset(String assetName) {
    if (assetName == null) return -1;
    String lower = assetName.trim().toLowerCase(Locale.ROOT);
    if (!lower.endsWith(".zip") || lower.contains("source")) return -1;
    if (lower.equals("smartvowificall-module.zip")) return 100;
    if (lower.contains("module")) return 80;
    return 40;
  }

  private static boolean isZipHeader(byte[] header) {
    return header.length >= 4
        && header[0] == 0x50
        && header[1] == 0x4B
        && header[2] == 0x03
        && header[3] == 0x04;
  }

  private static String sanitizeFileSegment(String raw) {
    if (raw == null) return "";
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_') {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static final class ParsedVersion {
    final List<Integer> core = new ArrayList<>();
    final List<String> pre = new ArrayList<>();
  }

  private static ParsedVersion parseVersion(String raw) {
    ParsedVersion pv = new ParsedVersion();
    if (raw == null) return pv;
    String s = raw.trim();
    if (s.startsWith("v") || s.startsWith("V")) {
      s = s.substring(1).trim();
    }
    int plus = s.indexOf('+');
    if (plus >= 0) s = s.substring(0, plus);
    int dash = s.indexOf('-');
    String corePart = dash >= 0 ? s.substring(0, dash) : s;
    String prePart = dash >= 0 ? s.substring(dash + 1) : "";
    for (String seg : corePart.split("\\.")) {
      String t = seg.trim();
      if (t.isEmpty()) continue;
      if (isDigits(t)) {
        try {
          pv.core.add(Integer.parseInt(t));
        } catch (NumberFormatException ignored) {
          pv.core.add(Integer.MAX_VALUE);
        }
      } else {
        // Extract leading digits if present, keep remainder as pre-release qualifier.
        int i = 0;
        while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
        if (i > 0) {
          try {
            pv.core.add(Integer.parseInt(t.substring(0, i)));
          } catch (NumberFormatException ignored) {
            pv.core.add(Integer.MAX_VALUE);
          }
        }
        if (i < t.length()) {
          pv.pre.add(t.substring(i).toLowerCase(Locale.ROOT));
        }
      }
    }
    if (!prePart.isEmpty()) {
      for (String seg : prePart.split("[.-]")) {
        String t = seg.trim().toLowerCase(Locale.ROOT);
        if (!t.isEmpty()) pv.pre.add(t);
      }
    }
    return pv;
  }

  private static boolean isDigits(String s) {
    if (s == null || s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') return false;
    }
    return true;
  }

  private static int compareNumericStrings(String a, String b) {
    String sa = stripLeadingZeros(a);
    String sb = stripLeadingZeros(b);
    if (sa.length() != sb.length()) return Integer.compare(sa.length(), sb.length());
    return sa.compareTo(sb);
  }

  private static String stripLeadingZeros(String s) {
    int i = 0;
    while (i < s.length() - 1 && s.charAt(i) == '0') i++;
    return s.substring(i);
  }
}
