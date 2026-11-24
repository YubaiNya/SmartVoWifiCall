package com.smartvowificall.ims.network;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java parsers for fixed read-only root output; interface names are never commands. */
public final class InterfaceParser {
  private InterfaceParser() {}
  public static final String ROOT_LISTING_SCRIPT =
      "for p in /sys/class/net/*; do [ -e \"$p\" ] || continue; printf 'IFACE\\t%s\\n' \"${p##*/}\"; "
      + "if [ -r \"$p/tun_flags\" ]; then printf 'TUNFLAGS\\t%s\\t%s\\n' \"${p##*/}\" "
      + "\"$(cat \"$p/tun_flags\" 2>/dev/null)\"; fi; done\n"
      + "ip -o link show\nip -o addr show\nip -o route show table all\nip -o -6 route show table "
      + "all\n"
      + "for pair in clash:wfctun0 clash1:wfctun1; do "
      + "cdir=${pair%%:*}; tdev=${pair##*:}; "
      + "owner=\"/data/adb/smartvowificall_ims/$cdir/netns.owner\"; "
      + "if [ -f \"$owner\" ]; then "
      + "read -r nspid nsstart nsid < \"$owner\" 2>/dev/null || :; "
      + "case \"${nspid:-}\" in ''|*[!0-9]*) nspid='';; esac; "
      + "if [ -n \"$nspid\" ] && [ -n \"${nsid:-}\" ] && "
      + "[ \"$(readlink \"/proc/$nspid/ns/net\" 2>/dev/null)\" = \"$nsid\" ] && "
      + "[ \"$nsid\" != \"$(readlink /proc/1/ns/net 2>/dev/null)\" ]; then "
      + "nsenter --net=\"/proc/$nspid/ns/net\" -- unshare -m -- sh -c '"
      + "mount -o private none / 2>/dev/null && mount -o private none /sys 2>/dev/null && "
      + "mount -t sysfs sysfs /sys 2>/dev/null || exit 0; "
      + "if [ -r \"/sys/class/net/$1/tun_flags\" ]; then "
      + "printf \"IFACE\\t%s\\nTUNFLAGS\\t%s\\t%s\\n\" \"$1\" \"$1\" \"$(cat \"/sys/class/net/$1/tun_flags\" 2>/dev/null)\"; "
      + "ip -o link show dev \"$1\" 2>/dev/null; "
      + "ip -o addr show dev \"$1\" 2>/dev/null; "
      + "ip -o route show dev \"$1\" 2>/dev/null; fi' _ \"$tdev\" 2>/dev/null || :; fi; fi; done";
  private static final Pattern LINK = Pattern.compile("^\\d+: (.+?): <([^>]*)>.*$");
  private static final Pattern ADDRESS = Pattern.compile("^\\d+: (\\S+)\\s+inet(6)?\\s+(\\S+).*$");

  public static void ipOutput(String text, InterfaceSnapshot.Builder out) {
    if (text == null)
      return;
    // First learn exact names before interpreting ip's displayed @peer suffix.
    for (String line : text.split("\\r?\\n"))
      if (line.startsWith("IFACE\t"))
        out.entry(line.substring(6));
    for (String line : text.split("\\r?\\n")) {
      if (line.startsWith("TUNFLAGS\t")) {
        String[] fields = line.split("\t", -1);
        if (fields.length == 3) {
          InterfaceSnapshot.MutableEntry e = out.entry(fields[1]);
          if (e != null) {
            e.tunnel = true;
            e.tunDevice = TunEgress.isTunFlags(fields[2]);
          }
        }
        continue;
      }
      Matcher link = LINK.matcher(line);
      if (link.matches()) {
        InterfaceSnapshot.MutableEntry e = out.entry(linkName(link.group(1), out));
        if (e == null)
          continue;
        List<String> flags = Arrays.asList(link.group(2).split(","));
        e.up = flags.contains("UP");
        if (flags.contains("LOWER_UP"))
          e.carrier = true;
        else if (flags.contains("NO-CARRIER"))
          e.carrier = false;
        e.loopback |= flags.contains("LOOPBACK");
        continue;
      }
      Matcher address = ADDRESS.matcher(line);
      if (address.matches()) {
        InterfaceSnapshot.MutableEntry e = out.entry(linkName(address.group(1), out));
        if (e != null)
          e.address(address.group(3));
        continue;
      }
      if (line.startsWith("default ")) {
        String[] parts = line.split("\\s+");
        for (int i = 0; i + 1 < parts.length; i++) {
          if (!parts[i].equals("dev"))
            continue;
          InterfaceSnapshot.MutableEntry e = out.entry(parts[i + 1]);
          if (e != null)
            e.defaultRoute = true;
        }
      }
    }
  }

  private static String linkName(String displayed, InterfaceSnapshot.Builder out) {
    if (out.entries.containsKey(displayed))
      return displayed;
    String longest = null;
    for (String known : out.entries.keySet()) {
      if (displayed.startsWith(known + "@")
          && (longest == null || known.length() > longest.length()))
        longest = known;
    }
    if (longest != null)
      return longest;
    int at = displayed.lastIndexOf('@');
    return at > 0 ? displayed.substring(0, at) : displayed;
  }

  static String read(File file) {
    try {
      return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
    } catch (Exception ignored) {
      return "";
    }
  }

  public static void sysfs(File root, InterfaceSnapshot.Builder out) {
    String[] names = root.list();
    if (names == null) {
      out.failed("sysfs");
      return;
    }
    for (String name : names) {
      InterfaceSnapshot.MutableEntry e = out.entry(name);
      if (e == null)
        continue;
      File dir = new File(root, name);
      try {
        e.up = (Long.decode(read(new File(dir, "flags"))) & 1) != 0;
      } catch (NumberFormatException ignored) {
      }
      String carrier = read(new File(dir, "carrier"));
      if (carrier.equals("0") || carrier.equals("1"))
        e.carrier = carrier.equals("1");
      e.loopback |= read(new File(dir, "type")).equals("772");
      e.wireless |= new File(dir, "wireless").exists() || new File(dir, "phy80211").exists();
      e.bridge |= new File(dir, "bridge").exists();
      e.tunnel |= new File(dir, "tun_flags").exists();
      e.tunDevice = TunEgress.isTunFlags(read(new File(dir, "tun_flags")));
      try {
        e.virtual |= dir.getCanonicalPath().replace('\\', '/').contains("/devices/virtual/net/");
        e.usb |= new File(dir, "device")
                     .getCanonicalPath()
                     .replace('\\', '/')
                     .matches(".*/usb[0-9]+/.*");
      } catch (Exception ignored) {
      }
    }
  }

  public static void procRoutes(String v4, String v6, InterfaceSnapshot.Builder out) {
    for (String line : v4.split("\\r?\\n")) {
      String[] f = line.trim().split("\\s+");
      if (f.length >= 8 && f[1].equals("00000000") && f[7].equals("00000000")) {
        InterfaceSnapshot.MutableEntry e = out.entry(f[0]);
        if (e != null)
          e.defaultRoute = true;
      }
    }
    for (String line : v6.split("\\r?\\n")) {
      String[] f = line.trim().split("\\s+");
      if (f.length >= 10 && f[0].equals("00000000000000000000000000000000") && f[1].equals("00")) {
        InterfaceSnapshot.MutableEntry e = out.entry(f[f.length - 1]);
        if (e != null)
          e.defaultRoute = true;
      }
    }
  }
}
