package com.newvowifi.ims.network;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Version 2 network observations consumed by the bundled native engine. */
public final class EngineNetworkSnapshot {
  private EngineNetworkSnapshot() { }

  public static Map<String, Object> entry(long handle, String iface, String transport,
      boolean validated, List<InetAddress> addresses, List<InetAddress> dns, List<Integer> slots) {
    if (handle == 0 || iface == null || !iface.matches("[A-Za-z0-9_.-]{1,15}")
        || !Arrays.asList("wifi", "cellular", "vpn", "other").contains(transport)) return null;
    List<String> ips = unicast(addresses), servers = unicast(dns);
    if (ips.isEmpty() || ips.size() > 16 || servers.isEmpty() || servers.size() > 8) return null;
    if (slots == null || slots.size() > 2 || new LinkedHashSet<>(slots).size() != slots.size()
        || (!slots.isEmpty() && !transport.equals("cellular"))) return null;
    for (Integer slot : slots) if (slot == null || slot < 0 || slot > 1) return null;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("handle", handle);
    result.put("interface", iface);
    result.put("transport", transport);
    result.put("validated", validated);
    result.put("addresses", ips);
    result.put("dns", servers);
    if (!slots.isEmpty()) result.put("slots", new ArrayList<>(slots));
    return result;
  }

  public static Map<String, Object> document(String bootId, long elapsedMs, long defaultHandle,
      List<Map<String, Object>> networks) {
    if (bootId == null || !bootId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        || elapsedMs < 0 || networks == null || networks.size() > 16)
      throw new IllegalArgumentException("NETWORK_SNAPSHOT_INVALID");
    LinkedHashSet<Long> handles = new LinkedHashSet<>();
    for (Map<String, Object> network : networks)
      if (network == null || !(network.get("handle") instanceof Long)
          || !handles.add((Long) network.get("handle")))
        throw new IllegalArgumentException("NETWORK_SNAPSHOT_INVALID");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", 2);
    result.put("bootId", bootId);
    result.put("observedMs", elapsedMs);
    result.put("defaultHandle", defaultHandle);
    result.put("networks", new ArrayList<>(networks));
    return result;
  }

  private static List<String> unicast(List<InetAddress> addresses) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (addresses != null) for (InetAddress address : addresses) {
      if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
          || address.isLinkLocalAddress() || address.isMulticastAddress()) continue;
      byte[] bytes = address.getAddress();
      if (bytes.length == 4 && bytes[0] == -1 && bytes[1] == -1 && bytes[2] == -1 && bytes[3] == -1)
        continue;
      String literal = address.getHostAddress();
      // The engine's ParseAddr rejects zone-qualified observations.
      if (literal != null && literal.indexOf('%') < 0) result.add(literal);
    }
    return new ArrayList<>(result);
  }
}
