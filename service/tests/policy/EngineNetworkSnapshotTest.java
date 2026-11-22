import com.newvowifi.ims.network.EngineNetworkSnapshot;
import com.newvowifi.ims.engine.JsonCodec;
import java.net.InetAddress;
import java.util.*;

/** Literal addresses only. No network access, Android, external app, or phone. */
public final class EngineNetworkSnapshotTest {
  private static int checks;
  private static void check(boolean value, String label) {
    checks++; if (!value) throw new AssertionError(label);
  }
  private static List<InetAddress> ips(String... input) throws Exception {
    List<InetAddress> result = new ArrayList<>();
    for (String address : input) result.add(InetAddress.getByName(address));
    return result;
  }
  public static void main(String[] args) throws Exception {
    String boot = "00000000-0000-0000-0000-000000000001";
    long handle = (132L << 32) | 0xcafed00dL;
    Map<String, Object> vpn = EngineNetworkSnapshot.entry(handle, "tun0", "vpn", true,
        ips("198.18.0.1", "fe80::1", "127.0.0.1", "224.0.0.1", "0.0.0.0", "255.255.255.255"),
        ips("198.18.0.2", "::1"), Collections.emptyList());
    check(vpn != null, "real VPN snapshot eligible");
    check(vpn.get("handle").equals(handle), "full 64-bit Android handle preserved");
    check(vpn.get("addresses").equals(Arrays.asList("198.18.0.1")), "filter link-local and non-unicast addresses");
    check(vpn.get("dns").equals(Arrays.asList("198.18.0.2")), "filter unusable DNS");
    Map<String, Object> doc = EngineNetworkSnapshot.document(boot, 25000, handle, Arrays.asList(vpn));
    Map<String, Object> parsed = JsonCodec.decodeObject(JsonCodec.encode(doc));
    check(((Number) parsed.get("version")).intValue() == 2, "version2 permits VPN transport");
    check(((Number) parsed.get("defaultHandle")).longValue() == handle, "handle remains exact through JSON");
    check(((Number) parsed.get("observedMs")).longValue() == 25000, "boot monotonic timestamp");
    check(parsed.get("bootId").equals(boot), "boot identity retained");
    for (String invalid : new String[] {"", "../../etc", "interface_name_long", "tun 0", "tun0\n"})
      check(EngineNetworkSnapshot.entry(handle, invalid, "vpn", true, ips("198.18.0.1"),
          ips("198.18.0.2"), Collections.emptyList()) == null, "invalid interface rejected");
    check(EngineNetworkSnapshot.entry(0, "tun0", "vpn", true, ips("198.18.0.1"), ips("198.18.0.2"), Collections.emptyList()) == null, "zero handle rejected");
    check(EngineNetworkSnapshot.entry(handle, "tun0", "bad", true, ips("198.18.0.1"), ips("198.18.0.2"), Collections.emptyList()) == null, "unknown transport rejected");
    check(EngineNetworkSnapshot.entry(handle, "tun0", "vpn", true, ips("fe80::1"), ips("198.18.0.2"), Collections.emptyList()) == null, "no usable source address");
    check(EngineNetworkSnapshot.entry(handle, "tun0", "vpn", true, ips("198.18.0.1"), Collections.emptyList(), Collections.emptyList()) == null, "no DNS does not invent a resolver");
    check(EngineNetworkSnapshot.entry(handle, "tun0", "vpn", true, ips("198.18.0.1"), ips("198.18.0.2"), Arrays.asList(1)) == null, "SIM slots only on cellular");
    check(EngineNetworkSnapshot.entry(handle, "rmnet0", "cellular", false, ips("10.1.2.3"), ips("10.1.2.1"), Arrays.asList(1, 1)) == null, "duplicate slot rejected");
    check(EngineNetworkSnapshot.entry(handle, "rmnet0", "cellular", false, ips("10.1.2.3"), ips("10.1.2.1"), Arrays.asList(2)) == null, "unsupported slot rejected");
    Map<String, Object> cell = EngineNetworkSnapshot.entry(handle, "rmnet0", "cellular", false,
        ips("2001:db8::1"), ips("2001:db8::2"), Arrays.asList(0, 1));
    check(cell != null && Boolean.FALSE.equals(cell.get("validated")), "preserve actual validation flag");
    check(cell.get("slots").equals(Arrays.asList(0, 1)), "cellular slots preserved");
    try { EngineNetworkSnapshot.document(boot, 1, handle, Arrays.asList(vpn, vpn)); throw new AssertionError(); }
    catch (IllegalArgumentException expected) { check(true, "duplicate handle rejected"); }
    try { EngineNetworkSnapshot.document(boot, -1, handle, Arrays.asList(vpn)); throw new AssertionError(); }
    catch (IllegalArgumentException expected) { check(true, "negative monotonic time rejected"); }
    try { EngineNetworkSnapshot.document("old-boot", 1, handle, Arrays.asList(vpn)); throw new AssertionError(); }
    catch (IllegalArgumentException expected) { check(true, "invalid boot identity rejected"); }
    List<InetAddress> tooMany = new ArrayList<>();
    for (int n = 1; n <= 17; n++) tooMany.add(InetAddress.getByName("10.0.0." + n));
    check(EngineNetworkSnapshot.entry(handle, "wlan0", "wifi", true, tooMany, ips("10.0.0.1"), Collections.emptyList()) == null, "native address count bound");
    check(EngineNetworkSnapshot.entry(handle, "wlan0", "wifi", true, ips("10.0.0.1"), tooMany, Collections.emptyList()) == null, "native DNS count bound");
    System.out.println("PASS " + checks + " standalone network registry assertions; no live network used.");
  }
}
