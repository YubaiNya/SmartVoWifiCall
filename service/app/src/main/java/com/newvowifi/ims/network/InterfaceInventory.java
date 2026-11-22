package com.newvowifi.ims.network;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import java.io.File;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Android adapter. Call read() on a worker thread; every call replaces the old inventory. */
public final class InterfaceInventory {
  public interface RootListingSource {
    String read() throws Exception;
  }
  private final Context context;
  private final RootListingSource rootSource;
  private final Map<String, Set<String>> verifiedVpnOwners;

  public InterfaceInventory(Context context, RootListingSource rootSource) {
    this(context, rootSource, Collections.emptyMap());
  }
  /**
   * verifiedVpnOwners must come from current VPN ownership evidence, never interface-name guesses.
   */
  public InterfaceInventory(
      Context context, RootListingSource rootSource, Map<String, Set<String>> verifiedVpnOwners) {
    this.context = context.getApplicationContext();
    this.rootSource = rootSource;
    Map<String, Set<String>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> e : verifiedVpnOwners.entrySet())
      copy.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
    this.verifiedVpnOwners = copy;
  }

  public InterfaceSnapshot read() {
    InterfaceSnapshot.Builder out = new InterfaceSnapshot.Builder();
    try {
      InterfaceParser.sysfs(new File("/sys/class/net"), out);
    } catch (RuntimeException e) {
      out.failed("sysfs");
    }
    try {
      javaInterfaces(out);
    } catch (Exception e) {
      out.failed("java-network-interfaces");
    }
    if (rootSource != null) {
      try {
        String listing = rootSource.read();
        if (listing == null || listing.trim().isEmpty())
          out.failed("root-listing");
        else
          InterfaceParser.ipOutput(listing, out);
      } catch (Exception e) {
        out.failed("root-listing");
      }
    }
    try {
      androidNetworks(out);
    } catch (RuntimeException e) {
      out.failed("android-networks");
    }
    InterfaceParser.procRoutes(InterfaceParser.read(new File("/proc/net/route")),
        InterfaceParser.read(new File("/proc/net/ipv6_route")), out);
    // Existing kernel entries are not enough to prove that an old VPN owner is still current.
    // Only apply caller evidence to interfaces currently reported as VPN by ConnectivityManager.
    for (Map.Entry<String, Set<String>> owner : verifiedVpnOwners.entrySet()) {
      InterfaceSnapshot.MutableEntry e = out.entries.get(owner.getKey());
      if (e != null && e.transports.contains(NetworkCapabilities.TRANSPORT_VPN))
        e.ownerPackages.addAll(owner.getValue());
    }
    return out.build();
  }

  private static void javaInterfaces(InterfaceSnapshot.Builder out) throws Exception {
    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
    while (interfaces != null && interfaces.hasMoreElements()) {
      NetworkInterface net = interfaces.nextElement();
      InterfaceSnapshot.MutableEntry e = out.entry(net.getName());
      if (e == null)
        continue;
      try {
        e.up = net.isUp();
        e.loopback |= net.isLoopback();
        e.virtual |= net.isVirtual();
      } catch (Exception ignored) {
      }
      Enumeration<InetAddress> addresses = net.getInetAddresses();
      while (addresses.hasMoreElements()) e.address(addresses.nextElement().getHostAddress());
    }
  }

  private void androidNetworks(InterfaceSnapshot.Builder out) {
    ConnectivityManager cm =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (cm == null) {
      out.failed("android-networks");
      return;
    }
    Network active = cm.getActiveNetwork();
    PackageManager pm = context.getPackageManager();
    for (Network network : cm.getAllNetworks()) {
      try {
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null)
          continue;
        InterfaceSnapshot.MutableEntry e = out.entry(lp.getInterfaceName());
        if (e == null)
          continue;
        e.networkHandles.add(network.getNetworkHandle());
        e.defaultNetwork |= network.equals(active);
        for (LinkAddress address : lp.getLinkAddresses())
          e.address(address.getAddress().getHostAddress());
        for (RouteInfo route : lp.getRoutes()) e.defaultRoute |= route.isDefaultRoute();
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null)
          continue;
        for (Field field : NetworkCapabilities.class.getFields()) {
          if (!field.getName().startsWith("TRANSPORT_") || field.getType() != int.class)
            continue;
          try {
            int transport = field.getInt(null);
            if (caps.hasTransport(transport))
              e.transports.add(transport);
          } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
          }
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
          int uid = caps.getOwnerUid();
          if (uid >= 0) {
            String[] packages = pm.getPackagesForUid(uid);
            if (packages != null)
              e.ownerPackages.addAll(Arrays.asList(packages));
          }
        }
      } catch (RuntimeException ignored) {
        out.failed("android-network-disappeared-or-denied");
      }
    }
  }
}
