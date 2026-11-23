package com.smartvowificall.ims.network;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Select an actual Linux TUN device, never a name prefix or a physical NIC fallback. */
public final class TunEgress {
  private TunEgress() { }
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
    if (!validName(name)) throw new IllegalArgumentException("INVALID_TUN_INTERFACE");
    return "iface:" + name;
  }
  public static String interfaceFromSelector(String transport) {
    if (transport == null || !transport.startsWith("iface:"))
      throw new IllegalArgumentException("EXPLICIT_TUN_REQUIRED");
    String name = transport.substring(6);
    selector(name);
    return name;
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
