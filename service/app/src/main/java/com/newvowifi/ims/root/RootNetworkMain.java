package com.newvowifi.ims.root;

import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Read-only app_process network ownership snapshot. No Context, application-side connectivity
 * cache, IP addresses, routes, full capability dumps, or persistent ownership cache is used.
 *
 * The API37 device DEX defines IConnectivityManager.getAllNetworks(), getLinkProperties(Network),
 * getNetworkCapabilities(Network,String,String), and IPackageManager.getPackagesForUid(int).
 * The consuming policy must still require current VPN + matching interface + exactly one owner
 * package before selecting its preferred VPN. Missing/redacted ownership is never guessed.
 */
public final class RootNetworkMain {
  private static final String CONNECTIVITY = "android.net.IConnectivityManager";
  private static final String PACKAGES = "android.content.pm.IPackageManager";
  private static final String VPN = "android.net.IVpnManager";
  private RootNetworkMain() {}

  public static void main(String[] args) {
    JSONObject result;
    int exitCode = 0;
    try {
      if (Process.myUid() != 0) throw new Failure("ROOT_REQUIRED");
      String mode = args.length == 0 ? "snapshot" : args[0];
      if (args.length > 1) throw new Failure("INVALID_ARGUMENT");
      if (mode.equals("diagnostics")) {
        result = new JSONObject().put("ok", true).put("code", "NETWORK_API_DIAGNOSTICS")
                     .put("methods", apiDiagnostics());
      } else if (mode.equals("snapshot")) {
        result = snapshot();
      } else throw new Failure("INVALID_ARGUMENT");
    } catch (Throwable error) {
      exitCode = 1;
      result = new JSONObject();
      try {
        result.put("ok", false).put("code", safeCode(error)).put("networks", new JSONArray());
        if (unwrap(error) instanceof NoSuchMethodException || unwrap(error) instanceof ClassNotFoundException
            || unwrap(error) instanceof NoSuchFieldException)
          result.put("methods", apiDiagnostics());
      } catch (Exception ignored) {}
    }
    System.out.println(result.toString());
    System.out.flush();
    System.exit(exitCode);
  }

  private static JSONObject snapshot() throws Exception {
    BoundService connectivity = new BoundService("connectivity", CONNECTIVITY);
    BoundService packages = new BoundService("package", PACKAGES);
    Method all = connectivity.type.getMethod("getAllNetworks");
    Method links = connectivity.type.getMethod("getLinkProperties", Network.class);
    Method caps = connectivity.type.getMethod("getNetworkCapabilities", Network.class, String.class, String.class);
    Method names = packages.type.getMethod("getPackagesForUid", int.class);
    Object value = all.invoke(connectivity.proxy);
    if (!(value instanceof Network[])) throw new Failure("NETWORK_LIST_UNAVAILABLE");
    Network[] networks = (Network[]) value;
    List<JSONObject> rows = new ArrayList<>();
    Set<String> issues = new LinkedHashSet<>();
    BoundService vpnManager = null;
    VpnOwner configuredVpn = null;
    try {
      vpnManager = new BoundService("vpn_management", VPN);
      configuredVpn = readVpnOwner(vpnManager, packages);
    } catch (Exception error) {
      issues.add("VPN_MANAGER_" + safeCode(error));
    }
    int skipped = 0;
    for (Network network : networks) {
      try {
        if (network == null) { skipped++; continue; }
        LinkProperties link = (LinkProperties) links.invoke(connectivity.proxy, network);
        NetworkCapabilities capability = (NetworkCapabilities) caps.invoke(connectivity.proxy, network, "root", null);
        if (link == null || capability == null || link.getInterfaceName() == null) {
          skipped++; issues.add("NETWORK_DISAPPEARED"); continue;
        }
        String iface = link.getInterfaceName();
        boolean vpn = capability.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        int reportedOwnerUid = capability.getOwnerUid();
        int ownerUid = reportedOwnerUid;
        String ownerSource = ownerUid >= 0 ? "NETWORK_CAPABILITIES" : "UNAVAILABLE";
        TreeSet<String> owners = new TreeSet<>();
        if (ownerUid >= 0) {
          Object packageValue = names.invoke(packages.proxy, ownerUid);
          if (packageValue instanceof String[]) {
            for (String name : (String[]) packageValue) if (name != null && !name.isEmpty()) owners.add(name);
          }
        }
        // A vanished/replaced VPN must not supply an owner for a reused interface name.
        LinkProperties latestLink = (LinkProperties) links.invoke(connectivity.proxy, network);
        NetworkCapabilities latestCaps = (NetworkCapabilities) caps.invoke(connectivity.proxy, network, "root", null);
        if (latestLink == null || latestCaps == null || !iface.equals(latestLink.getInterfaceName())
            || vpn != latestCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            || reportedOwnerUid != latestCaps.getOwnerUid()) {
          skipped++; issues.add("NETWORK_CHANGED_DURING_READ"); continue;
        }
        if (vpn && configuredVpn != null && iface.equals(configuredVpn.iface)) {
          VpnOwner latestOwner = readVpnOwner(vpnManager, packages);
          if (!configuredVpn.sameAs(latestOwner)) {
            skipped++; issues.add("VPN_CHANGED_DURING_READ"); continue;
          }
          if (reportedOwnerUid >= 0 && reportedOwnerUid != configuredVpn.uid) {
            skipped++; issues.add("VPN_OWNER_UID_MISMATCH"); continue;
          }
          // Public NetworkCapabilities is redacted even for root on this device. CONTROL_VPN
          // supplies the actual current package/interface; PackageManager verifies its UID.
          ownerUid = configuredVpn.uid;
          owners = new TreeSet<>(configuredVpn.packages);
          ownerSource = "VPN_MANAGER";
        } else if (vpn && reportedOwnerUid < 0) {
          issues.add("VPN_OWNER_UNAVAILABLE");
        }
        rows.add(new JSONObject().put("interface", iface).put("vpn", vpn).put("ownerUid", ownerUid)
                     .put("ownerPackages", new JSONArray(owners))
                     .put("networkHandle", network.getNetworkHandle())
                     .put("ownerSource", ownerSource)
                     .put("ownerVerified", vpn && ownerUid >= 0 && !owners.isEmpty()));
      } catch (Exception error) {
        skipped++;
        issues.add(safeCode(error));
      }
    }
    rows.sort(Comparator.comparingLong(row -> row.optLong("networkHandle", 0)));
    return new JSONObject().put("ok", true).put("code", "NETWORK_SNAPSHOT")
        .put("networks", new JSONArray(rows)).put("skippedNetworks", skipped)
        .put("complete", skipped == 0).put("issues", new JSONArray(issues));
  }

  private static final class VpnOwner {
    final String iface, packageName;
    final int uid;
    final long startTime;
    final TreeSet<String> packages;
    VpnOwner(String iface, String packageName, int uid, long startTime, TreeSet<String> packages) {
      this.iface = iface; this.packageName = packageName; this.uid = uid;
      this.startTime = startTime; this.packages = packages;
    }
    boolean sameAs(VpnOwner other) {
      return other != null && iface.equals(other.iface) && packageName.equals(other.packageName)
          && uid == other.uid && startTime == other.startTime && packages.equals(other.packages);
    }
  }

  private static VpnOwner readVpnOwner(BoundService vpnManager, BoundService packages) throws Exception {
    // The policy applies to the current owner user 0, not a work profile or a legacy VPN key.
    Object config = vpnManager.type.getMethod("getVpnConfig", int.class).invoke(vpnManager.proxy, 0);
    if (config == null) return null;
    Class<?> type = Class.forName("com.android.internal.net.VpnConfig");
    if (!type.isInstance(config) || type.getField("legacy").getBoolean(config)) return null;
    Object user = type.getField("user").get(config), link = type.getField("interfaze").get(config);
    if (!(user instanceof String) || !(link instanceof String)) return null;
    String packageName = (String) user, iface = (String) link;
    if (packageName.isEmpty() || iface.isEmpty()) return null;
    int uid = (Integer) packages.type.getMethod("getPackageUid", String.class, long.class, int.class)
                            .invoke(packages.proxy, packageName, 0L, 0);
    if (uid < 0) throw new Failure("VPN_PACKAGE_UID_UNAVAILABLE");
    Object names = packages.type.getMethod("getPackagesForUid", int.class).invoke(packages.proxy, uid);
    TreeSet<String> verifiedPackages = new TreeSet<>();
    if (names instanceof String[]) {
      for (String name : (String[]) names) if (name != null && !name.isEmpty()) verifiedPackages.add(name);
    }
    if (!verifiedPackages.contains(packageName)) throw new Failure("VPN_PACKAGE_UID_MISMATCH");
    return new VpnOwner(iface, packageName, uid, type.getField("startTime").getLong(config), verifiedPackages);
  }

  private static final class BoundService {
    final Class<?> type;
    final Object proxy;
    BoundService(String serviceName, String interfaceName) throws Exception {
      IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                          .getMethod("getService", String.class).invoke(null, serviceName);
      if (binder == null || !binder.isBinderAlive()) throw new Failure("NETWORK_SERVICE_UNAVAILABLE");
      type = Class.forName(interfaceName);
      proxy = Class.forName(interfaceName + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
      if (proxy == null) throw new Failure("NETWORK_SERVICE_UNAVAILABLE");
    }
  }

  /** Only read-only method signatures are exposed; no Binder payload or exception text. */
  private static JSONArray apiDiagnostics() {
    JSONArray result = new JSONArray();
    for (String typeName : new String[] {CONNECTIVITY, PACKAGES, VPN}) {
      try {
        List<String> signatures = new ArrayList<>();
        for (Method method : Class.forName(typeName).getMethods()) {
          if (!Arrays.asList("getAllNetworks", "getLinkProperties", "getNetworkCapabilities",
                  "getPackagesForUid", "getPackageUid", "getVpnConfig")
                   .contains(method.getName())) continue;
          StringBuilder signature = new StringBuilder(method.getName()).append('(');
          Class<?>[] types = method.getParameterTypes();
          for (int i = 0; i < types.length; i++) {
            if (i != 0) signature.append(',');
            signature.append(types[i].getName());
          }
          signatures.add(signature.append("):").append(method.getReturnType().getName()).toString());
        }
        java.util.Collections.sort(signatures);
        result.put(new JSONObject().put("interface", typeName).put("signatures", new JSONArray(signatures)));
      } catch (Exception ignored) {
        try { result.put(new JSONObject().put("interface", typeName).put("code", "NETWORK_API_UNAVAILABLE")); }
        catch (Exception ignoredAgain) {}
      }
    }
    return result;
  }

  private static Throwable unwrap(Throwable error) {
    while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
    return error;
  }
  private static String safeCode(Throwable error) {
    error = unwrap(error);
    if (error instanceof Failure) return ((Failure) error).code;
    if (error instanceof NoSuchMethodException || error instanceof ClassNotFoundException
        || error instanceof NoSuchFieldException)
      return "NETWORK_API_UNAVAILABLE";
    if (error instanceof SecurityException) return "NETWORK_PERMISSION_DENIED";
    if (error instanceof RemoteException) return "NETWORK_REMOTE_FAILURE";
    return "NETWORK_READ_FAILED";
  }
  private static final class Failure extends Exception {
    final String code;
    Failure(String code) { super(code); this.code = code; }
  }
}
