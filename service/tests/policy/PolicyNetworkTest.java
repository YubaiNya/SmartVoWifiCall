import com.newvowifi.ims.network.InterfaceParser;
import com.newvowifi.ims.network.InterfaceSnapshot;
import com.newvowifi.ims.policy.PolicyEngine;
import com.newvowifi.ims.policy.PolicyEngine.Action;
import com.newvowifi.ims.policy.PolicyEngine.AppliedOverride;
import com.newvowifi.ims.policy.PolicyEngine.Kind;
import com.newvowifi.ims.policy.PolicyEngine.Plan;
import com.newvowifi.ims.policy.PolicyEngine.Reason;
import com.newvowifi.ims.policy.PolicyEngine.Sim;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host-only regression scenarios. All subscriptions, identities and addresses are synthetic. */
public final class PolicyNetworkTest {
    private static int checks;
    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
    private static Sim sim(String key, int sub, int slot, String plmn) { return new Sim(key, sub, slot, true, plmn); }
    private static InterfaceSnapshot inventory() {
        InterfaceSnapshot.Builder b = new InterfaceSnapshot.Builder();
        InterfaceSnapshot.MutableEntry vpn = b.entry("vpnCustom42");
        vpn.up = true; vpn.address("192.0.2.7/32"); vpn.transports.add(4);
        vpn.ownerPackages.add(InterfaceSnapshot.FLCLASH_PACKAGE); vpn.defaultNetwork = true;
        b.entry("downWithoutIp").up = false;
        b.entry("unknownState");
        b.entry("upWithoutIp").up = true;
        InterfaceSnapshot.MutableEntry loopback = b.entry("lo");
        loopback.up = true; loopback.loopback = true; loopback.address("127.0.0.1");
        return b.build();
    }
    private static Map<String, String> selections(String key, String iface) {
        Map<String, String> result = new LinkedHashMap<>(); result.put(key, iface); return result;
    }
    private static Map<Integer, AppliedOverride> applied(AppliedOverride... records) {
        Map<Integer, AppliedOverride> result = new LinkedHashMap<>();
        for (AppliedOverride record : records) result.put(record.slotId, record);
        return result;
    }
    private static Plan evaluate(Sim sim, String iface, Map<Integer, AppliedOverride> previous, boolean call) {
        return PolicyEngine.evaluate(Collections.singletonList(sim), selections(sim.simKey, iface), inventory(), previous, call);
    }

    public static void main(String[] ignored) {
        for (String plmn : Arrays.asList("46000", "460001", "46100", "461999"))
            check(PolicyEngine.eligibility(sim("a", 19, 1, plmn)) == Reason.MAINLAND_HOME, "mainland " + plmn);
        for (String plmn : Arrays.asList("45400", "45501", "46692", "23430", "310260", "90170"))
            check(PolicyEngine.eligibility(sim("a", 19, 1, plmn)) == Reason.ELIGIBLE, "non-mainland numeric " + plmn);
        for (String plmn : Arrays.asList(null, "", "234", "2343000", " 23430", "2343A", "２３４３０"))
            check(PolicyEngine.eligibility(sim("a", 19, 1, plmn)) == Reason.INVALID_HOME_PLMN, "invalid home PLMN");
        check(PolicyEngine.eligibility(new Sim("a", 19, 1, false, "23430")) == Reason.SIM_NOT_READY, "not ready stays OEM");
        check(PolicyEngine.eligibility(sim(null, 19, 1, "23430")) == Reason.IDENTITY_UNAVAILABLE, "unknown identity stays OEM");
        check(PolicyEngine.eligibility(sim("a", -1, 1, "23430")) == Reason.INVALID_SUBSCRIPTION, "invalid subscription");

        InterfaceSnapshot inventory = inventory();
        check("vpnCustom42".equals(inventory.preferredVpnInterface), "FlClash discovered from owner, not tun0");
        check(inventory.entries.size() == 5, "DOWN/no-IP/loopback retained");
        check(inventory.selection("vpnCustom42").available(), "selected VPN usable");
        check(inventory.selection("vanishedVpn").state == InterfaceSnapshot.SelectionState.MISSING, "vanished selection invalid");
        check("vanishedVpn".equals(inventory.selection("vanishedVpn").interfaceName), "vanished selection preserved");
        check(inventory.selection("downWithoutIp").state == InterfaceSnapshot.SelectionState.DOWN, "down remains selectable but unavailable");
        check(inventory.selection("upWithoutIp").state == InterfaceSnapshot.SelectionState.NO_ADDRESS, "no address unavailable");
        check(inventory.selection("unknownState").state == InterfaceSnapshot.SelectionState.STATE_UNKNOWN, "unknown state unavailable");
        check(inventory.selection("lo").state == InterfaceSnapshot.SelectionState.LOOPBACK, "loopback listed not used");

        Sim foreign = sim("card-a", 19, 1, "23430");
        AppliedOverride old = new AppliedOverride("card-a", 19, 1, "vpnCustom42");
        Plan start = evaluate(foreign, "vpnCustom42", Collections.emptyMap(), false);
        check(start.actions.size() == 1 && start.actions.get(0).kind == Kind.TAKEOVER, "new eligible SIM takeover");
        check(evaluate(foreign, "vpnCustom42", applied(old), false).actions.isEmpty(), "idempotent stable binding");
        Plan missing = evaluate(foreign, "vanishedVpn", applied(old), false);
        check(missing.actions.size() == 1 && missing.actions.get(0).kind == Kind.CLEAR, "missing selected interface restores OEM");
        check("vanishedVpn".equals(missing.decisions.get(0).selection.interfaceName), "no fallback to available FlClash interface");
        Plan busy = evaluate(foreign, "vanishedVpn", applied(old), true);
        check(busy.actions.isEmpty() && busy.deferredForCall, "active call prevents switching");
        check(!busy.decisions.get(0).shouldTakeOver, "desired unavailable state still exposed while deferred");
        Plan afterCall = evaluate(foreign, "vanishedVpn", applied(old), false);
        check(afterCall.actions.get(0).kind == Kind.CLEAR, "call completion allows OEM restoration");
        check(evaluate(foreign, "vpnCustom42", Collections.emptyMap(), true).actions.isEmpty(), "new takeover also waits during calls");
        check(evaluate(new Sim("card-a", 19, 1, false, null), "vpnCustom42", applied(old), false).actions.get(0).kind == Kind.CLEAR,
                "SIM becomes unreadable restores OEM");
        check(evaluate(sim("card-a", 19, 1, "46000"), "vpnCustom42", applied(old), false).actions.get(0).kind == Kind.CLEAR,
                "current home identity changes to mainland restores OEM");

        Plan subChanged = evaluate(sim("card-a", 25, 1, "23430"), "vpnCustom42", applied(old), false);
        check(subChanged.actions.size() == 2 && subChanged.actions.get(0).kind == Kind.CLEAR
                && subChanged.actions.get(0).subId == 19 && subChanged.actions.get(1).subId == 25, "new subId clears old first");
        Plan identityChanged = evaluate(sim("card-b", 19, 1, "23430"), "vpnCustom42", applied(old), false);
        check(identityChanged.actions.size() == 2 && identityChanged.actions.get(0).kind == Kind.CLEAR
                && identityChanged.actions.get(1).simKey.equals("card-b"), "new ICCID identity clears old first");
        Plan moved = evaluate(sim("card-a", 26, 0, "23430"), "vpnCustom42", applied(old), false);
        check(moved.actions.size() == 2 && moved.actions.get(0).slotId == 1 && moved.actions.get(0).kind == Kind.CLEAR
                && moved.actions.get(1).slotId == 0 && moved.actions.get(1).kind == Kind.TAKEOVER, "slot migration cleans old slot first");
        Plan removed = PolicyEngine.evaluate(Collections.emptyList(), Collections.emptyMap(), inventory, applied(old), false);
        check(removed.actions.size() == 1 && removed.actions.get(0).kind == Kind.CLEAR, "removed SIM clears owned override");
        Map<String, String> bothSelections = selections("card-a", "vpnCustom42"); bothSelections.put("mainland", "vpnCustom42");
        Plan dual = PolicyEngine.evaluate(Arrays.asList(foreign, sim("mainland", 20, 0, "46000")), bothSelections, inventory, Collections.emptyMap(), false);
        check(dual.actions.size() == 1 && dual.actions.get(0).slotId == 1, "mainland other slot untouched");

        InterfaceSnapshot.Builder parsed = new InterfaceSnapshot.Builder();
        InterfaceParser.ipOutput("IFACE\tcustom@literal\nIFACE\trmnet_data0\nIFACE\tdummy0\n"
                + "1: custom@literal: <UP,LOWER_UP> mtu 1500\n"
                + "2: rmnet_data0@if5: <BROADCAST> mtu 1500\n"
                + "3: dummy0: <BROADCAST> mtu 1500\n"
                + "1: custom@literal inet 192.0.2.8/32 scope global\n"
                + "1: custom@literal inet6 2001:db8::8/64 scope global\n"
                + "default via 192.0.2.1 dev custom@literal table 123\n", parsed);
        InterfaceSnapshot parsedSnapshot = parsed.build();
        check(parsedSnapshot.entries.size() == 3, "no name/IP/UP filtering");
        check(parsedSnapshot.entries.containsKey("custom@literal") && !parsedSnapshot.entries.containsKey("custom"), "literal @ name retained");
        check(parsedSnapshot.entries.containsKey("rmnet_data0") && !parsedSnapshot.entries.containsKey("rmnet_data0@if5"), "displayed peer suffix stripped");
        check(parsedSnapshot.entries.get("custom@literal").addresses.size() == 2, "IPv4 and IPv6 merged");
        check(parsedSnapshot.entries.get("custom@literal").defaultRoute, "default route annotated");
        check(!parsedSnapshot.entries.get("dummy0").available(), "DOWN zero-IP entry retained but unavailable");
        parsed.entry("dummy0").up = true;
        check(Boolean.FALSE.equals(parsedSnapshot.entries.get("dummy0").up), "snapshot detached from builder");
        check(new InterfaceSnapshot.Builder().build().entries.isEmpty(), "fresh inventory drops disappeared entries");

        InterfaceSnapshot.Builder unknownVpn = new InterfaceSnapshot.Builder();
        unknownVpn.entry("tun0").transports.add(4);
        check(unknownVpn.build().preferredVpnInterface == null, "unknown VPN owner is not FlClash");
        InterfaceSnapshot.MutableEntry second = unknownVpn.entry("tunOther"); second.transports.add(4);
        second.ownerPackages.add(InterfaceSnapshot.FLCLASH_PACKAGE);
        check("tunOther".equals(unknownVpn.build().preferredVpnInterface), "correct VPN owner wins over tun0 name");
        unknownVpn.entry("tun0").ownerPackages.add(InterfaceSnapshot.FLCLASH_PACKAGE);
        check(unknownVpn.build().preferredVpnInterface == null, "ambiguous owned VPNs not guessed");
        unknownVpn.entry("tunOther").defaultNetwork = true;
        check("tunOther".equals(unknownVpn.build().preferredVpnInterface), "active owned VPN resolves ambiguity");
        check(!InterfaceSnapshot.validName("../tun0") && !InterfaceSnapshot.validName("a b")
                && !InterfaceSnapshot.validName("1234567890123456") && InterfaceSnapshot.validName("odd-name.9"), "kernel interface name validation");
        System.out.println("PASS " + checks + " policy/network assertions; no Android, root, calls or SMS executed.");
    }
}
