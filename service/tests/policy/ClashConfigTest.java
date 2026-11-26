import com.smartvowificall.ims.clash.ClashConfig;
import com.smartvowificall.ims.clash.SubscriptionQrParser;
import java.util.*;

/** Pure Java Clash config sanitizer tests; no mihomo binary, root, or network executed. */
public final class ClashConfigTest {
  static int checks;
  static void check(boolean ok, String why) {
    checks++;
    if (!ok) throw new AssertionError(why);
  }

  public static void main(String[] args) {
    String secret = "0123456789abcdef0123456789abcdef";
    String sample = "\uFEFF"
        + "mixed-port: 7890\n"
        + "port: 7891\n"
        + "socks-port: 7892\n"
        + "allow-lan: true\n"
        + "mode: rule\n"
        + "external-controller: 0.0.0.0:9090\n"
        + "secret: old-secret\n"
        + "tun:\n"
        + "  enable: true\n"
        + "  device: utun\n"
        + "  auto-route: true\n"
        + "  auto-redirect: true\n"
        + "  auto-detect-interface: true\n"
        + "dns:\n"
        + "  enable: true\n"
        + "  enhanced-mode: fake-ip\n"
        + "  nameserver:\n"
        + "    - 114.114.114.114\n"
        + "proxies:\n"
        + "  - name: \"🇭🇰 HK 01\"\n"
        + "    type: ss\n"
        + "    server: hk.example.com\n"
        + "    port: 443\n"
        + "  - type: trojan\n"
        + "    name: 'UK ''London'' 02'\n"
        + "    server: uk.example.com\n"
        + "    port: 443\n"
        + "  - {name: JP-03, type: vmess, server: jp.example.com, port: 443}\n"
        + "  - { name: \"US: West, 04\", type: vless, server: us.example.com, port: 443 }\n"
        + "  - name: SG-05 # trailing comment\n"
        + "    type: ss\n"
        + "  - name: DIRECT\n"
        + "    type: ss\n"
        + "  - name: REJECT\n"
        + "    type: ss\n"
        + "proxy-groups:\n"
        + "  - name: Proxy\n"
        + "    type: select\n"
        + "    proxies:\n"
        + "      - DIRECT\n"
        + "rules:\n"
        + "  - GEOIP,CN,DIRECT\n"
        + "  - MATCH,Proxy\n";

    ClashConfig.Built built = ClashConfig.buildRuntimeConfig(sample, secret);
    check(built.proxyNames.equals(Arrays.asList(
        "🇭🇰 HK 01", "UK 'London' 02", "JP-03", "US: West, 04", "SG-05")),
        "extracted all non-reserved proxy names accurately: " + built.proxyNames);
    check(built.providerNames.isEmpty(), "no proxy providers in sample");

    String y = built.yaml;
    check(y.contains("log-level: warning\n") && !y.contains("log-level: info\n"),
        "routine proxy traffic does not generate per-connection info logs");
    check(!y.contains("mixed-port: 7890") && y.contains("mixed-port: 0\n"), "mixed-port forced 0");
    check(!y.contains("port: 7891") && y.contains("port: 0\n"), "port forced 0");
    check(!y.contains("socks-port: 7892") && y.contains("socks-port: 0\n"), "socks-port forced 0");
    check(!y.contains("allow-lan: true") && y.contains("allow-lan: false\n"), "allow-lan forced false");
    check(!y.contains("0.0.0.0:9090") && y.contains("external-controller: 127.0.0.1:19090\n"), "loopback controller enforced");
    check(!y.contains("old-secret") && y.contains("secret: \"" + secret + "\"\n"), "new secret enforced");
    check(!y.contains("auto-route: true") && y.contains("  auto-route: false\n"), "auto-route forced false");
    check(!y.contains("auto-redirect: true") && y.contains("  auto-redirect: false\n"), "auto-redirect forced false");
    check(!y.contains("auto-detect-interface: true") && y.contains("  auto-detect-interface: false\n"), "auto-detect-interface forced false");
    check(y.contains("routing-mark: 131072\n"), "Android FWMARK_PROTECTED_FROM_VPN (0x20000) bypasses system tun0 VPN");
    ClashConfig.Built bound = ClashConfig.buildRuntimeConfig(sample, secret, false, ClashConfig.CONTROLLER_PORT, "wlan0");
    check(bound.yaml.contains("interface-name: \"wlan0\"\n"), "safe physical uplink binds mihomo out of system tun0");
    ClashConfig.Built tunBlocked = ClashConfig.buildRuntimeConfig(sample, secret, false, ClashConfig.CONTROLLER_PORT, "tun0");
    check(!tunBlocked.yaml.contains("interface-name:"), "tun0 is never accepted as physical uplink");
    check(!y.contains("enhanced-mode: fake-ip") && y.contains("  enhanced-mode: redir-host\n"), "redir-host enforced");
    check(y.contains("  fake-ip-range: 198.19.0.1/16\n"), "wfctun0 subnet anchored to 198.19.0.1");
    check(!y.contains("GEOIP,CN,DIRECT"), "user rules stripped");
    check(y.contains("  device: wfctun0\n"), "wfctun0 device enforced");
    check(y.contains("    - 198.19.0.1/30\n"), "isolated 198.19.0.1/30 CIDR enforced");
    check(y.contains("    - any:53\n"), "dns-hijack on wfctun0 enforced");
    check(y.contains("https://1.1.1.1/dns-query#WFC-PROXY\n"), "DoH routed via WFC-PROXY");
    check(y.contains("tcp://8.8.8.8:53#WFC-PROXY\n"), "fallback TCP DNS routed via WFC-PROXY");
    check(y.contains("  - name: WFC-PROXY\n"), "WFC-PROXY group generated");
    check(y.contains("  - MATCH,WFC-PROXY\n"), "global MATCH,WFC-PROXY rule enforced");
    check(!y.contains("      - \"DIRECT\"\n") && !y.contains("      - \"REJECT\"\n"),
        "DIRECT/REJECT never added to WFC-PROXY");
    check(y.contains("      - \"UK 'London' 02\"\n"), "escaped single-quote name preserved");
    check(y.contains("      - \"US: West, 04\"\n"), "flow-style name with colon and comma preserved");

    // Provider-only subscription
    String providerOnly = "proxy-providers:\n"
        + "  airport-hk:\n"
        + "    type: http\n"
        + "    url: https://example.invalid/sub\n"
        + "    path: ./providers/hk.yaml\n"
        + "  airport-uk:\n"
        + "    type: file\n"
        + "    path: ./providers/uk.yaml\n";
    ClashConfig.Built prov = ClashConfig.buildRuntimeConfig(providerOnly, secret);
    check(prov.proxyNames.isEmpty(), "provider-only has no inline proxies");
    check(prov.providerNames.equals(Arrays.asList("airport-hk", "airport-uk")), "extracted proxy-providers");
    check(prov.yaml.contains("    use:\n      - \"airport-hk\"\n      - \"airport-uk\"\n"), "WFC-PROXY uses providers");
    check(!prov.yaml.contains("    proxies:\n"), "empty proxies omitted when provider-only");

    // Reject empty / invalid / no-proxy profiles
    for (String bad : new String[] {null, "", "   \n# comment only", "mode: rule\nrules:\n  - MATCH,DIRECT\n",
        "proxies:\n  - name: DIRECT\n    type: ss\n"}) {
      try {
        ClashConfig.buildRuntimeConfig(bad, secret);
        throw new AssertionError("invalid profile accepted: " + bad);
      } catch (IllegalArgumentException expected) {
        check(true, "invalid/empty profile rejected");
      }
    }
    try {
      ClashConfig.buildRuntimeConfig(providerOnly, "short");
      throw new AssertionError("weak secret accepted");
    } catch (IllegalArgumentException expected) {
      check(true, "weak controller secret rejected");
    }

    // External controller LAN mode for debugging (e.g., MetaCubeXD / Zashboard):
    // must keep secret, port:0, allow-lan:false, auto-route:false, and add CORS.
    ClashConfig.Built lan = ClashConfig.buildRuntimeConfig(sample, secret, true, 19095);
    check(lan.yaml.contains("external-controller: 0.0.0.0:19095\n"), "LAN controller bind applied");
    check(lan.yaml.contains("external-controller-cors:\n  allow-origins:\n    - \"*\"\n  allow-private-network: true\n"),
        "LAN controller CORS enabled for web dashboards");
    check(lan.yaml.contains("secret: \"" + secret + "\"\n"), "secret still enforced in LAN mode");
    check(lan.yaml.contains("port: 0\n") && lan.yaml.contains("mixed-port: 0\n")
        && lan.yaml.contains("allow-lan: false\n") && lan.yaml.contains("  auto-route: false\n"),
        "proxy ports and auto-route remain strictly disabled in LAN debug mode");
    for (int badPort : new int[] {0, 80, 1023, 65536, -1}) {
      try {
        ClashConfig.buildRuntimeConfig(sample, secret, true, badPort);
        throw new AssertionError("invalid controller port accepted: " + badPort);
      } catch (IllegalArgumentException expected) {
        check(true, "invalid controller port rejected: " + badPort);
      }
    }

    // Second built-in Clash instance (wfctun1) uses isolated subnet, DNS port, and controller port
    check(ClashConfig.instanceForTun("wfctun0") == 0, "wfctun0 maps to instance 0");
    check(ClashConfig.instanceForTun("wfctun1") == 1, "wfctun1 maps to instance 1");
    check(ClashConfig.instanceForTun("tun0") == -1, "tun0 is not built-in");
    check(ClashConfig.isBuiltinTun("wfctun0") && ClashConfig.isBuiltinTun("wfctun1"), "both built-in TUNs recognized");
    ClashConfig.Built inst1 = ClashConfig.buildRuntimeConfigForInstance(
        1, sample, secret, false, ClashConfig.CONTROLLER_PORT_1, "wlan0");
    check(inst1.yaml.contains("  device: wfctun1\n"), "instance 1 uses wfctun1");
    check(inst1.yaml.contains("    - 198.19.1.1/30\n"), "instance 1 uses isolated 198.19.1.1/30 CIDR");
    check(inst1.yaml.contains("  fake-ip-range: 198.19.1.1/16\n"), "instance 1 uses 198.19.1.1/16");
    check(inst1.yaml.contains("  listen: 127.0.0.1:1054\n"), "instance 1 uses isolated DNS port 1054");
    check(inst1.yaml.contains("external-controller: 127.0.0.1:19091\n"), "instance 1 uses controller port 19091");
    check(inst1.yaml.contains("interface-name: \"wlan0\"\n"), "instance 1 binds physical uplink wlan0");

    // QR subscription URL parsing: plain HTTPS, clash://install-config, sub:// base64, invalid text
    check("https://sub.example.com/api/v1?token=abc".equals(
        SubscriptionQrParser.extractSubscriptionUrl("https://sub.example.com/api/v1?token=abc")),
        "plain https QR parsed");
    check("https://sub.example.com/clash?k=1&b=2".equals(
        SubscriptionQrParser.extractSubscriptionUrl(
            "clash://install-config?url=https%3A%2F%2Fsub.example.com%2Fclash%3Fk%3D1%26b%3D2&name=Test")),
        "clash://install-config deep link parsed");
    check("https://sub.example.com/meta".equals(
        SubscriptionQrParser.extractSubscriptionUrl(
            "clashmeta://install-config?name=Demo&url=https%3A%2F%2Fsub.example.com%2Fmeta")),
        "clashmeta:// deep link parsed");
    String b64 = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("https://sub.example.com/from-sub".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    check("https://sub.example.com/from-sub".equals(
        SubscriptionQrParser.extractSubscriptionUrl("sub://" + b64)),
        "sub:// base64 QR parsed");
    check(SubscriptionQrParser.extractSubscriptionUrl("not a link") == null, "plain text rejected");
    check(SubscriptionQrParser.extractSubscriptionUrl("javascript:alert(1)") == null, "javascript scheme rejected");

    System.out.println("PASS " + checks + " built-in Clash config sanitization assertions; no mihomo or network executed.");
  }
}
