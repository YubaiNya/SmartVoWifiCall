package com.newvowifi.ims.clash;

import java.util.*;

/**
 * Pure Java sanitizer/builder that turns a user Clash YAML profile into a fail-closed
 * runtime config for mihomo:
 * - No system VPN, no auto-route, no auto-redirect, no open proxy ports.
 * - Dedicated Linux TUN device wfctun0 (198.19.0.1/30) with dns-hijack on wfctun0 only.
 * - All traffic entering wfctun0 matches MATCH,WFC-PROXY (never defaults to DIRECT).
 * - Outbound proxy sockets follow the normal Android system routing table.
 */
public final class ClashConfig {
  public static final String TUN_DEVICE = "wfctun0";
  public static final String TUN_IPV4 = "198.19.0.1";
  public static final String TUN_CIDR = "198.19.0.1/30";
  public static final String CONTROLLER_HOST = "127.0.0.1";
  public static final int CONTROLLER_PORT = 19090;
  public static final String CONTROLLER_ADDR = CONTROLLER_HOST + ":" + CONTROLLER_PORT;
  public static final String PROXY_GROUP = "WFC-PROXY";
  public static final String ENGINE_DNS_TARGET = "1.1.1.1";
  public static final int ROUTE_TABLE = 209;
  public static final int RULE_PREF_OIF = 1050;
  public static final int RULE_PREF_SRC = 1051;

  private static final Set<String> RESERVED_TARGETS = new HashSet<>(Arrays.asList(
      "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL"));

  private static final Set<String> STRIPPED_TOP_KEYS = new HashSet<>(Arrays.asList(
      "port", "socks-port", "redir-port", "tproxy-port", "mixed-port",
      "allow-lan", "bind-address", "mode", "log-level", "ipv6",
      "external-controller", "external-controller-tls", "external-controller-unix",
      "external-controller-pipe", "external-ui", "external-ui-name", "external-ui-url",
      "secret", "interface-name", "routing-mark",
      "tun", "ebpf", "dns", "sniffer", "profile",
      "rules", "sub-rules", "rule-providers", "proxy-groups",
      "listeners", "tunnels",
      "geodata-mode", "geodata-loader", "geo-auto-update", "geo-update-interval", "geox-url",
      "find-process-mode", "unified-delay", "tcp-concurrent", "hosts", "ntp", "experimental"));

  public static final class Built {
    public final String yaml;
    public final List<String> proxyNames;
    public final List<String> providerNames;
    Built(String yaml, List<String> proxyNames, List<String> providerNames) {
      this.yaml = yaml;
      this.proxyNames = Collections.unmodifiableList(proxyNames);
      this.providerNames = Collections.unmodifiableList(providerNames);
    }
  }

  private ClashConfig() {}

  public static Built buildRuntimeConfig(String profileYaml, String secret) {
    if (profileYaml == null || profileYaml.trim().isEmpty())
      throw new IllegalArgumentException("CLASH_PROFILE_EMPTY");
    if (secret == null || !secret.matches("[A-Za-z0-9_-]{16,128}"))
      throw new IllegalArgumentException("CLASH_SECRET_INVALID");

    String text = profileYaml;
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    text = text.replace("\r\n", "\n").replace("\r", "\n");
    String[] lines = text.split("\n", -1);

    StringBuilder kept = new StringBuilder();
    List<String> proxyNames = new ArrayList<>();
    Set<String> seenProxies = new LinkedHashSet<>();
    List<String> providerNames = new ArrayList<>();
    Set<String> seenProviders = new LinkedHashSet<>();

    String currentTopKey = null;
    boolean skipping = false;
    int providerChildIndent = -1;
    int proxyItemIndent = -1;
    boolean inProxyItem = false;

    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.equals("...")) continue;
      if (trimmed.equals("---")) {
        if (currentTopKey == null) continue;
        // Stop at a second YAML document marker.
        break;
      }

      if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
        int indent = leadingSpaces(line);
        if (indent == 0) {
          String key = parseTopKey(line);
          if (key != null) {
            currentTopKey = key;
            skipping = STRIPPED_TOP_KEYS.contains(key);
            providerChildIndent = -1;
            proxyItemIndent = -1;
            inProxyItem = false;
            if (!skipping) {
              kept.append(line).append('\n');
            }
            continue;
          }
        }
      }

      if (skipping) continue;
      kept.append(line).append('\n');

      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

      if ("proxies".equals(currentTopKey)) {
        int indent = leadingSpaces(line);
        if (trimmed.startsWith("-")) {
          inProxyItem = true;
          proxyItemIndent = indent;
          String afterDash = trimmed.substring(1).trim();
          if (afterDash.startsWith("{")) {
            String name = extractFlowField(afterDash, "name");
            addProxyName(name, seenProxies, proxyNames);
          } else if (afterDash.startsWith("name:") || afterDash.startsWith("\"name\":")
              || afterDash.startsWith("'name':")) {
            int colon = afterDash.indexOf(':');
            String name = parseYamlScalar(afterDash.substring(colon + 1));
            addProxyName(name, seenProxies, proxyNames);
          }
        } else if (inProxyItem && indent > proxyItemIndent) {
          if (trimmed.startsWith("name:") || trimmed.startsWith("\"name\":")
              || trimmed.startsWith("'name':")) {
            int colon = trimmed.indexOf(':');
            String name = parseYamlScalar(trimmed.substring(colon + 1));
            addProxyName(name, seenProxies, proxyNames);
          }
        }
      } else if ("proxy-providers".equals(currentTopKey)) {
        int indent = leadingSpaces(line);
        if (indent > 0) {
          if (providerChildIndent < 0) providerChildIndent = indent;
          if (indent == providerChildIndent) {
            String pkey = parseKeyName(trimmed);
            if (pkey != null && !pkey.isEmpty() && seenProviders.add(pkey))
              providerNames.add(pkey);
          }
        }
      }
    }

    if (proxyNames.isEmpty() && providerNames.isEmpty())
      throw new IllegalArgumentException("CLASH_NO_PROXIES");

    StringBuilder out = new StringBuilder();
    out.append("# Auto-generated by NewVoWiFi. Edit profile.yaml, not runtime.yaml.\n");
    out.append(kept);
    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
    out.append("\n# --- NewVoWiFi Enforced Wi-Fi Calling Isolation ---\n");
    out.append("mode: rule\n");
    out.append("log-level: info\n");
    out.append("allow-lan: false\n");
    out.append("bind-address: 127.0.0.1\n");
    out.append("port: 0\n");
    out.append("socks-port: 0\n");
    out.append("redir-port: 0\n");
    out.append("tproxy-port: 0\n");
    out.append("mixed-port: 0\n");
    out.append("ipv6: false\n");
    out.append("unified-delay: true\n");
    out.append("external-controller: ").append(CONTROLLER_ADDR).append('\n');
    out.append("secret: ").append(quoteYaml(secret)).append('\n');
    out.append("profile:\n");
    out.append("  store-selected: true\n");
    out.append("  store-fake-ip: false\n");
    out.append("dns:\n");
    out.append("  enable: true\n");
    out.append("  listen: 127.0.0.1:1053\n");
    out.append("  enhanced-mode: redir-host\n");
    out.append("  respect-rules: false\n");
    out.append("  default-nameserver:\n");
    out.append("    - 223.5.5.5\n");
    out.append("    - 119.29.29.29\n");
    out.append("  proxy-server-nameserver:\n");
    out.append("    - 223.5.5.5\n");
    out.append("    - 119.29.29.29\n");
    out.append("  nameserver:\n");
    out.append("    - https://1.1.1.1/dns-query#").append(PROXY_GROUP).append('\n');
    out.append("    - tcp://8.8.8.8:53#").append(PROXY_GROUP).append('\n');
    out.append("tun:\n");
    out.append("  enable: true\n");
    out.append("  device: ").append(TUN_DEVICE).append('\n');
    out.append("  stack: gvisor\n");
    out.append("  auto-route: false\n");
    out.append("  auto-redirect: false\n");
    out.append("  auto-detect-interface: false\n");
    out.append("  strict-route: false\n");
    out.append("  dns-hijack:\n");
    out.append("    - any:53\n");
    out.append("  inet4-address:\n");
    out.append("    - ").append(TUN_CIDR).append('\n');
    out.append("  mtu: 1400\n");
    out.append("proxy-groups:\n");
    out.append("  - name: ").append(PROXY_GROUP).append('\n');
    out.append("    type: select\n");
    if (!proxyNames.isEmpty()) {
      out.append("    proxies:\n");
      for (String name : proxyNames)
        out.append("      - ").append(quoteYaml(name)).append('\n');
    }
    if (!providerNames.isEmpty()) {
      out.append("    use:\n");
      for (String provider : providerNames)
        out.append("      - ").append(quoteYaml(provider)).append('\n');
    }
    out.append("rules:\n");
    out.append("  - MATCH,").append(PROXY_GROUP).append('\n');
    return new Built(out.toString(), proxyNames, providerNames);
  }

  private static void addProxyName(String name, Set<String> seen, List<String> list) {
    if (name == null) return;
    String clean = name.trim();
    if (clean.isEmpty() || RESERVED_TARGETS.contains(clean.toUpperCase(Locale.ROOT))) return;
    if (seen.add(clean)) list.add(clean);
  }

  private static int leadingSpaces(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
    return i;
  }

  private static String parseTopKey(String line) {
    return parseKeyName(line);
  }

  private static String parseKeyName(String trimmedOrLine) {
    if (trimmedOrLine.startsWith("-") || trimmedOrLine.startsWith("#")) return null;
    int colon = findUnquotedColon(trimmedOrLine);
    if (colon <= 0) return null;
    // Key must be followed by EOL or whitespace after ':'.
    if (colon + 1 < trimmedOrLine.length()) {
      char next = trimmedOrLine.charAt(colon + 1);
      if (next != ' ' && next != '\t' && next != '\r' && next != '\n') return null;
    }
    String raw = trimmedOrLine.substring(0, colon).trim();
    return unquoteKey(raw);
  }

  private static int findUnquotedColon(String s) {
    char quote = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (quote != 0) {
        if (quote == '"' && c == '\\' && i + 1 < s.length()) { i++; continue; }
        if (c == quote) quote = 0;
      } else {
        if (c == '\'' || c == '"') quote = c;
        else if (c == ':') return i;
      }
    }
    return -1;
  }

  private static String unquoteKey(String raw) {
    if (raw.length() >= 2 && ((raw.startsWith("\"") && raw.endsWith("\""))
        || (raw.startsWith("'") && raw.endsWith("'")))) {
      return raw.substring(1, raw.length() - 1);
    }
    return raw;
  }

  static String extractFlowField(String flow, String targetKey) {
    int i = flow.indexOf('{');
    if (i < 0) return null;
    i++;
    int n = flow.length();
    int depth = 1;
    while (i < n && depth > 0) {
      while (i < n && (flow.charAt(i) == ' ' || flow.charAt(i) == '\t' || flow.charAt(i) == ',')) i++;
      if (i >= n || flow.charAt(i) == '}') break;
      // Read key
      int keyStart = i;
      char q = 0;
      while (i < n) {
        char c = flow.charAt(i);
        if (q != 0) {
          if (q == '"' && c == '\\' && i + 1 < n) { i += 2; continue; }
          if (c == q) q = 0;
          i++;
        } else {
          if (c == '\'' || c == '"') { q = c; i++; }
          else if (c == ':' || c == '}') break;
          else i++;
        }
      }
      if (i >= n || flow.charAt(i) != ':') break;
      String key = unquoteKey(flow.substring(keyStart, i).trim());
      i++; // skip ':'
      while (i < n && (flow.charAt(i) == ' ' || flow.charAt(i) == '\t')) i++;
      if (i >= n) break;
      // Read value
      int valStart = i;
      int nested = 0;
      q = 0;
      while (i < n) {
        char c = flow.charAt(i);
        if (q != 0) {
          if (q == '"' && c == '\\' && i + 1 < n) { i += 2; continue; }
          if (q == '\'' && c == '\'' && i + 1 < n && flow.charAt(i + 1) == '\'') { i += 2; continue; }
          if (c == q) q = 0;
          i++;
        } else {
          if (c == '\'' || c == '"') { q = c; i++; }
          else if (c == '{' || c == '[') { nested++; i++; }
          else if (c == '}' || c == ']') {
            if (nested == 0) break;
            nested--;
            i++;
          } else if (c == ',' && nested == 0) break;
          else i++;
        }
      }
      String rawVal = flow.substring(valStart, i);
      if (targetKey.equals(key)) return parseYamlScalar(rawVal);
    }
    return null;
  }

  static String parseYamlScalar(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty()) return null;
    if (s.startsWith("'")) {
      StringBuilder sb = new StringBuilder();
      for (int i = 1; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '\'') {
          if (i + 1 < s.length() && s.charAt(i + 1) == '\'') { sb.append('\''); i++; }
          else return sb.toString();
        } else sb.append(c);
      }
      return sb.toString();
    }
    if (s.startsWith("\"")) {
      StringBuilder sb = new StringBuilder();
      for (int i = 1; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '\\' && i + 1 < s.length()) {
          char next = s.charAt(++i);
          if (next == 'n') sb.append('\n');
          else if (next == 't') sb.append('\t');
          else if (next == 'r') sb.append('\r');
          else if (next == '"' || next == '\\' || next == '/') sb.append(next);
          else if (next == 'u' && i + 4 < s.length()) {
            try {
              sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
              i += 4;
            } catch (NumberFormatException ignored) { sb.append("\\u"); }
          } else sb.append(next);
        } else if (c == '"') return sb.toString();
        else sb.append(c);
      }
      return sb.toString();
    }
    // Unquoted scalar: strip trailing inline comment preceded by whitespace.
    for (int i = 1; i < s.length(); i++) {
      if (s.charAt(i) == '#' && (s.charAt(i - 1) == ' ' || s.charAt(i - 1) == '\t')) {
        return s.substring(0, i).trim();
      }
    }
    return s;
  }

  public static String quoteYaml(String value) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\') sb.append("\\\\");
      else if (c == '"') sb.append("\\\"");
      else if (c == '\n') sb.append("\\n");
      else if (c == '\r') sb.append("\\r");
      else if (c == '\t') sb.append("\\t");
      else sb.append(c);
    }
    return sb.append('"').toString();
  }
}
