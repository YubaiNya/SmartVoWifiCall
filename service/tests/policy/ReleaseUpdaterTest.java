package com.smartvowificall.ims.update;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ReleaseUpdaterTest {
  private static int passed = 0;

  private static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError("FAIL: " + msg);
    passed++;
  }

  public static void main(String[] args) throws Exception {
    // 1. Version comparison
    check(ReleaseUpdater.compareVersions("v0.6.1-dev", "0.6.0-dev") > 0, "0.6.1-dev > 0.6.0-dev");
    check(ReleaseUpdater.compareVersions("v0.6.0", "0.6.0-dev") > 0, "stable > prerelease");
    check(ReleaseUpdater.compareVersions("v0.6.0-dev", "0.6.0-dev") == 0, "equal dev versions");
    check(ReleaseUpdater.compareVersions("v0.5.0-dev", "0.6.0-dev") < 0, "older dev < newer dev");
    check(ReleaseUpdater.compareVersions("v0.6.0-dev.10", "v0.6.0-dev.2") > 0, "numeric prerelease 10 > 2");
    check(ReleaseUpdater.compareVersions("v1.0.0", "0.9.9") > 0, "major bump 1.0.0 > 0.9.9");
    check(ReleaseUpdater.isNewerVersion("v0.6.1-dev", "0.6.0-dev"), "isNewerVersion true");
    check(!ReleaseUpdater.isNewerVersion("v0.6.0-dev", "0.6.0-dev"), "isNewerVersion false when equal");

    // 2. Trusted GitHub URL enforcement
    check(ReleaseUpdater.isTrustedGitHubUrl("https://api.github.com/repos/YubaiNya/SmartVoWifiCall/releases"), "api.github.com");
    check(ReleaseUpdater.isTrustedGitHubUrl("https://github.com/YubaiNya/SmartVoWifiCall/releases/download/v0.6.0-dev/SmartVoWifiCall-module.zip"), "github.com download");
    check(ReleaseUpdater.isTrustedGitHubUrl("https://objects.githubusercontent.com/github-production-release-asset/123"), "objects.githubusercontent.com");
    check(!ReleaseUpdater.isTrustedGitHubUrl("http://github.com/YubaiNya/SmartVoWifiCall"), "reject plain http");
    check(!ReleaseUpdater.isTrustedGitHubUrl("https://evil.example.com/github.com/module.zip"), "reject untrusted host");

    // 3. Parsing releases: skips draft, picks highest semver (including prerelease), picks module zip over source/apk
    String sampleJson = "["
        + "{\"tag_name\":\"v0.9.9\",\"name\":\"Draft\",\"draft\":true,\"prerelease\":false,\"body\":\"draft\",\"html_url\":\"https://github.com/YubaiNya/SmartVoWifiCall/releases/tag/v0.9.9\",\"assets\":[]},"
        + "{\"tag_name\":\"v0.5.0-dev\",\"name\":\"v0.5.0-dev\",\"draft\":false,\"prerelease\":false,\"body\":\"old\",\"html_url\":\"https://github.com/YubaiNya/SmartVoWifiCall/releases/tag/v0.5.0-dev\",\"assets\":[]},"
        + "{\"tag_name\":\"v0.6.1-dev\",\"name\":\"SmartVoWifiCall v0.6.1-dev\",\"draft\":false,\"prerelease\":true,\"published_at\":\"2026-11-25T01:00:00Z\",\"body\":\"- 修复 giffgaff IKE_AUTH\\n- 新增检查更新\",\"html_url\":\"https://github.com/YubaiNya/SmartVoWifiCall/releases/tag/v0.6.1-dev\",\"assets\":["
        + "{\"name\":\"SmartVoWifiCall.apk\",\"size\":1000,\"browser_download_url\":\"https://github.com/YubaiNya/SmartVoWifiCall/releases/download/v0.6.1-dev/SmartVoWifiCall.apk\"},"
        + "{\"name\":\"SmartVoWifiCall-source.zip\",\"size\":2000,\"browser_download_url\":\"https://github.com/YubaiNya/SmartVoWifiCall/releases/download/v0.6.1-dev/SmartVoWifiCall-source.zip\"},"
        + "{\"name\":\"SmartVoWifiCall-module.zip\",\"size\":32941528,\"browser_download_url\":\"https://github.com/YubaiNya/SmartVoWifiCall/releases/download/v0.6.1-dev/SmartVoWifiCall-module.zip\"}"
        + "]}"
        + "]";
    ReleaseUpdater.ReleaseInfo parsed = ReleaseUpdater.parseLatestRelease(sampleJson);
    check(parsed != null, "parsed non-null");
    check("v0.6.1-dev".equals(parsed.tagName), "picks highest non-draft release");
    check(parsed.prerelease, "captures prerelease flag");
    check("SmartVoWifiCall-module.zip".equals(parsed.moduleAssetName), "picks module zip");
    check(parsed.moduleSizeBytes == 32941528L, "captures size");
    check("SmartVoWifiCall-module-v0.6.1-dev.zip".equals(ReleaseUpdater.buildDownloadFileName(parsed)), "embeds tag in download filename");

    // 4. Loopback end-to-end API fetch + 302 redirect + ZIP download + non-ZIP rejection + cancellation
    byte[] fakeZip = new byte[40 * 1024];
    fakeZip[0] = 0x50; fakeZip[1] = 0x4B; fakeZip[2] = 0x03; fakeZip[3] = 0x04;
    for (int i = 4; i < fakeZip.length; i++) fakeZip[i] = (byte) (i & 0xFF);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = server.getAddress().getPort();
    String base = "http://127.0.0.1:" + port;

    server.createContext("/releases", ex -> {
      String payload = "[{\"tag_name\":\"v0.7.0\",\"name\":\"v0.7.0\",\"draft\":false,\"prerelease\":false,"
          + "\"published_at\":\"2026-11-25T02:00:00Z\",\"body\":\"更新测试内容\","
          + "\"html_url\":\"" + base + "/web\","
          + "\"assets\":[{\"name\":\"SmartVoWifiCall-module.zip\",\"size\":" + fakeZip.length + ","
          + "\"browser_download_url\":\"" + base + "/download-redirect\"}]}]";
      byte[] b = payload.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, b.length);
      try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    });
    server.createContext("/download-redirect", ex -> {
      ex.getResponseHeaders().add("Location", base + "/download-final");
      ex.sendResponseHeaders(302, -1);
      ex.close();
    });
    server.createContext("/download-final", ex -> {
      ex.getResponseHeaders().add("Content-Type", "application/zip");
      ex.sendResponseHeaders(200, fakeZip.length);
      try (OutputStream os = ex.getResponseBody()) { os.write(fakeZip); }
    });
    server.createContext("/bad-html", ex -> {
      byte[] html = "<html><body>Not Found</body></html>".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, html.length);
      try (OutputStream os = ex.getResponseBody()) { os.write(html); }
    });
    server.start();
    try {
      ReleaseUpdater.CheckResult checkRes =
          ReleaseUpdater.fetchLatestRelease(base + "/releases", "0.6.0-dev", true);
      check(checkRes.ok && checkRes.hasUpdate && checkRes.latest != null, "fetch detects update");
      check("v0.7.0".equals(checkRes.latest.tagName), "fetched tag v0.7.0");

      ByteArrayOutputStream sink = new ByteArrayOutputStream();
      AtomicLong lastDl = new AtomicLong();
      AtomicInteger callbacks = new AtomicInteger();
      long written = ReleaseUpdater.downloadModuleToStream(
          checkRes.latest.moduleDownloadUrl,
          "0.6.0-dev",
          checkRes.latest.moduleSizeBytes,
          sink,
          new AtomicBoolean(false),
          (dl, total) -> { lastDl.set(dl); callbacks.incrementAndGet(); },
          true);
      check(written == fakeZip.length, "downloaded exact byte count");
      check(sink.size() == fakeZip.length, "sink size matches");
      check(lastDl.get() == fakeZip.length && callbacks.get() >= 1, "progress callback fired");

      // Non-ZIP rejection
      boolean rejectedHtml = false;
      try {
        ReleaseUpdater.downloadModuleToStream(
            base + "/bad-html", "0.6.0-dev", 0L, new ByteArrayOutputStream(), new AtomicBoolean(false), null, true);
      } catch (IOException expected) {
        rejectedHtml = true;
      }
      check(rejectedHtml, "rejects non-ZIP response");

      // Cancellation check
      boolean cancelledThrown = false;
      try {
        ReleaseUpdater.downloadModuleToStream(
            base + "/download-final", "0.6.0-dev", fakeZip.length, new ByteArrayOutputStream(), new AtomicBoolean(true), null, true);
      } catch (IOException expected) {
        cancelledThrown = true;
      }
      check(cancelledThrown, "cancels download cleanly");
    } finally {
      server.stop(0);
    }

    System.out.println("PASS " + passed + " GitHub release updater & direct download assertions; loopback only.");
  }
}
