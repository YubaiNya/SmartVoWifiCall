package com.smartvowificall.ims.network;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A fresh, immutable inventory. Interface visibility is independent of usability. */
public final class InterfaceSnapshot {
  public static final String FLCLASH_PACKAGE = "com.follow.clash";
  public static final String BUILTIN_CLASH_TUN = "wfctun0";
  public static final String BUILTIN_CLASH_TUN_1 = "wfctun1";
  public static final String BUILTIN_CLASH_IPV4 = "198.19.0.1";
  public static final String BUILTIN_CLASH_IPV4_1 = "198.19.1.1";
  public static boolean isBuiltinClashTun(String name) {
    return BUILTIN_CLASH_TUN.equals(name) || BUILTIN_CLASH_TUN_1.equals(name);
  }
  public final Map<String, Entry> entries;
  public final List<String> failures;
  /** Prefers built-in Clash wfctun0 when UP with its dedicated IP; otherwise verified FlClash VPN. */
  public final String preferredVpnInterface;

  private InterfaceSnapshot(Builder builder) {
    Map<String, Entry> copy = new LinkedHashMap<>();
    for (MutableEntry item : builder.entries.values()) copy.put(item.name, new Entry(item));
    entries = Collections.unmodifiableMap(copy);
    failures = Collections.unmodifiableList(new ArrayList<>(builder.failures));
    preferredVpnInterface = findPreferredVpn(copy.values());
  }

  private static String findPreferredVpn(Collection<Entry> entries) {
    for (Entry e : entries) {
      if (BUILTIN_CLASH_TUN.equals(e.name) && e.available() && e.voWifiTun()
          && e.addresses.contains(BUILTIN_CLASH_IPV4))
        return e.name;
    }
    for (Entry e : entries) {
      if (BUILTIN_CLASH_TUN_1.equals(e.name) && e.available() && e.voWifiTun()
          && e.addresses.contains(BUILTIN_CLASH_IPV4_1))
        return e.name;
    }
    List<Entry> candidates = new ArrayList<>();
    for (Entry e : entries) {
      if (e.voWifiTun() && e.transports.contains(4) && e.ownerPackages.contains(FLCLASH_PACKAGE))
        candidates.add(e);
    }
    if (candidates.size() == 1)
      return candidates.get(0).name;
    Entry active = null;
    for (Entry e : candidates) {
      if (!e.defaultNetwork)
        continue;
      if (active != null)
        return null;
      active = e;
    }
    return active == null ? null : active.name;
  }

  public List<Entry> sortedEntries() {
    List<Entry> result = new ArrayList<>(entries.values());
    result.sort(Comparator.comparingInt((Entry e)
                                            -> e.defaultNetwork ? 0
                                  : e.available()               ? 1
                                                                : 2)
                    .thenComparing(e -> e.name));
    return Collections.unmodifiableList(result);
  }

  public enum SelectionState {
    NOT_SELECTED,
    INVALID_NAME,
    MISSING,
    DOWN,
    STATE_UNKNOWN,
    NO_ADDRESS,
    LOOPBACK,
    AVAILABLE
  }

  public static final class Selection {
    /** Preserved exactly, including a disappeared selection. */
    public final String interfaceName;
    public final SelectionState state;
    public final Entry entry;
    private Selection(String name, SelectionState state, Entry entry) {
      this.interfaceName = name;
      this.state = state;
      this.entry = entry;
    }
    public boolean available() {
      return state == SelectionState.AVAILABLE;
    }
  }

  public Selection selection(String name) {
    if (TunEgress.isSystemRoute(name)) {
      boolean route = entries.values().stream().anyMatch(e -> e.available()
          && !isBuiltinClashTun(e.name) && e.defaultNetwork);
      return new Selection(name, route ? SelectionState.AVAILABLE : SelectionState.MISSING, null);
    }
    if (name == null || name.isEmpty())
      return new Selection(name, SelectionState.NOT_SELECTED, null);
    if (!validName(name))
      return new Selection(name, SelectionState.INVALID_NAME, null);
    Entry e = entries.get(name);
    if (e == null)
      return new Selection(name, SelectionState.MISSING, null);
    SelectionState state = e.loopback ? SelectionState.LOOPBACK
        : e.up == null                ? SelectionState.STATE_UNKNOWN
        : !e.up                       ? SelectionState.DOWN
        : e.addresses.isEmpty()       ? SelectionState.NO_ADDRESS
                                      : SelectionState.AVAILABLE;
    return new Selection(name, state, e);
  }

  public static boolean validName(String name) {
    if (name == null || name.isEmpty() || name.equals(".") || name.equals(".."))
      return false;
    byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 15)
      return false;
    for (byte b : bytes) {
      int c = b & 255;
      if (c == '/' || c == ':' || c == 0 || c == 32 || (c >= 9 && c <= 13))
        return false;
    }
    return true;
  }

  public static final class Entry {
    public final String name;
    public final Boolean up, carrier;
    public final Set<String> addresses, ownerPackages;
    public final Set<Integer> transports;
    public final Set<Long> networkHandles;
    public final boolean defaultNetwork, defaultRoute, wireless, bridge, tunnel, tunDevice, virtual, usb,
        loopback;
    private Entry(MutableEntry e) {
      name = e.name;
      up = e.up;
      carrier = e.carrier;
      addresses = immutableSet(e.addresses);
      ownerPackages = immutableSet(e.ownerPackages);
      transports = immutableSet(e.transports);
      networkHandles = immutableSet(e.networkHandles);
      defaultNetwork = e.defaultNetwork;
      defaultRoute = e.defaultRoute;
      wireless = e.wireless;
      bridge = e.bridge;
      tunnel = e.tunnel;
      tunDevice = e.tunDevice;
      virtual = e.virtual;
      usb = e.usb;
      loopback = e.loopback;
    }
    public boolean available() {
      return Boolean.TRUE.equals(up) && !addresses.isEmpty() && !loopback;
    }
    public boolean voWifiTun() {
      return !loopback && tunDevice && TunEgress.validName(name);
    }
    public String type() {
      if (BUILTIN_CLASH_TUN.equals(name) && voWifiTun())
        return "内置 Clash";
      if (BUILTIN_CLASH_TUN_1.equals(name) && voWifiTun())
        return "内置 Clash 2";
      if (transports.contains(4))
        return "VPN";
      if (loopback)
        return "回环";
      if (transports.contains(0))
        return "移动网络";
      if (transports.contains(8) || usb)
        return "USB";
      if (transports.contains(1) || wireless)
        return "Wi-Fi";
      if (transports.contains(2))
        return "蓝牙";
      if (bridge)
        return "网桥";
      if (tunnel)
        return "隧道";
      if (transports.contains(3))
        return "以太网";
      return virtual ? "虚拟接口" : "其他接口";
    }
    public String label() {
      return name + " · " + type() + " · "
          + (up == null ? "状态未知"
                  : up  ? "UP"
                        : "DOWN")
          + (addresses.isEmpty() ? " · 无 IP" : " · " + String.join(", ", addresses));
    }
  }

  private static <T> Set<T> immutableSet(Collection<T> values) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(values));
  }

  public static final class MutableEntry {
    public final String name;
    public Boolean up, carrier;
    public final Set<String> addresses = new LinkedHashSet<>(),
                             ownerPackages = new LinkedHashSet<>();
    public final Set<Integer> transports = new LinkedHashSet<>();
    public final Set<Long> networkHandles = new LinkedHashSet<>();
    public boolean defaultNetwork, defaultRoute, wireless, bridge, tunnel, tunDevice, virtual, usb, loopback;
    private MutableEntry(String name) {
      this.name = name;
    }
    public void address(String address) {
      if (address != null && !address.isEmpty())
        addresses.add(address.split("/", 2)[0]);
    }
  }

  public static final class Builder {
    final Map<String, MutableEntry> entries = new LinkedHashMap<>();
    final List<String> failures = new ArrayList<>();
    public MutableEntry entry(String name) {
      if (!validName(name))
        return null;
      MutableEntry e = entries.get(name);
      if (e == null) {
        e = new MutableEntry(name);
        entries.put(name, e);
      }
      return e;
    }
    /** Source identifier only; exception text may contain unrelated device data. */
    public void failed(String source) {
      if (!failures.contains(source))
        failures.add(source);
    }
    public InterfaceSnapshot build() {
      return new InterfaceSnapshot(this);
    }
  }
}
