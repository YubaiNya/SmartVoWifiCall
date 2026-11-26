package com.smartvowificall.ims.network;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Explicit transport selectors plus TUN checks for isolated built-in proxy networks. */
public final class TunEgress {
  public static final String SYSTEM_ROUTE = "@system";
  private TunEgress() { }
  public static boolean isSystemRoute(String name) { return SYSTEM_ROUTE.equals(name); }
  public static boolean validSelection(String name) { return isSystemRoute(name) || validName(name); }
  public static boolean isTunFlags(String value) {
    if (value == null || value.trim().isEmpty()) return false;
    try {
      long flags = Long.decode(value.trim());
      return flags >= 0 && flags <= 0xffffffffL && (flags & 3) == 1; // IFF_TUN, not IFF_TAP
    } catch (NumberFormatException error) { return false; }
  }
  public static boolean validName(String name) {
    return name != null && name.matches("[A-Za-z0-9_.-]{1,15}")
        && !name.equals(".") && !name.equals("..");
  }
  public static String selector(String name) {
    if (isSystemRoute(name)) return "system";
    if (!validName(name)) throw new IllegalArgumentException("INVALID_TUN_INTERFACE");
    return "iface:" + name;
  }
  public static String interfaceFromSelector(String transport) {
    if ("system".equals(transport)) return SYSTEM_ROUTE;
    if (transport == null || !transport.startsWith("iface:"))
      throw new IllegalArgumentException("TRANSPORT_INVALID");
    String name = transport.substring(6);
    selector(name);
    return name;
  }
  /** Interface type does not decide whether a carrier can be reached. Never changes link state. */
  public static void requireInterface(String name) throws IOException {
    if (isSystemRoute(name)) return; // The actual attempt resolves and uses the current system route.
    if (!validName(name)) throw new IOException("INVALID_INTERFACE");
    java.net.NetworkInterface iface = java.net.NetworkInterface.getByName(name);
    if (iface == null || !iface.isUp() || iface.isLoopback() || !iface.getInetAddresses().hasMoreElements())
      throw new IOException("INTERFACE_UNAVAILABLE");
  }
  /** Root-side recheck immediately before config commit / engine startup. */
  public static void requireDevice(String name) throws IOException {
    if (!validName(name)) throw new IOException("INVALID_TUN_INTERFACE");
    String flags;
    try {
      flags = new String(Files.readAllBytes(new File("/sys/class/net/" + name + "/tun_flags").toPath()),
          StandardCharsets.US_ASCII);
    } catch (IOException error) { throw new IOException("TUN_UNAVAILABLE"); }
    if (!isTunFlags(flags)) throw new IOException("TUN_REQUIRED");
  }
}
