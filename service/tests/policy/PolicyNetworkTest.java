import com.smartvowificall.ims.network.InterfaceParser;
import com.smartvowificall.ims.network.InterfaceSnapshot;
import com.smartvowificall.ims.network.TunEgress;
import com.smartvowificall.ims.policy.PolicyEngine;
import com.smartvowificall.ims.policy.PolicyEngine.Action;
import com.smartvowificall.ims.policy.PolicyEngine.AppliedOverride;
import com.smartvowificall.ims.policy.PolicyEngine.Kind;
import com.smartvowificall.ims.policy.PolicyEngine.Plan;
import com.smartvowificall.ims.policy.PolicyEngine.Reason;
import com.smartvowificall.ims.policy.PolicyEngine.Sim;
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
        vpn.tunDevice = true;
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
        for (String plmn : Arrays.asList("46000", "460001", "46100", "461999")) {
            check(PolicyEngine.eligibility(sim("a", 19, 1, plmn)) == Reason.ELIGIBLE, "mainland now eligible when opted in: " + plmn);
            check(PolicyEngine.isMainlandPlmn(plmn), "mainland plmn classified: " + plmn);
        }
        for (String plmn : Arrays.asList("45400", "45501", "46692", "23430", "310260", "90170")) {
            check(PolicyEngine.eligibility(sim("a", 19, 1, plmn)) == Reason.ELIGIBLE, "non-mainland numeric " + plmn);
            check(!PolicyEngine.isMainlandPlmn(plmn), "non-mainland plmn classified: " + plmn);
        }
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
        check(evaluate(sim("card-a", 19, 1, "46000"), "vpnCustom42", applied(old), false).actions.isEmpty(),
                "domestic PLMN remains eligible when slot is explicitly enabled");

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
        check(dual.actions.size() == 2 && dual.actions.get(0).kind == Kind.TAKEOVER && dual.actions.get(1).kind == Kind.TAKEOVER,
                "dual SIM simultaneous VoWiFi takeover supported for both slots");

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
        unknownVpn.entry("tun0").tunDevice = true;
        check(unknownVpn.build().preferredVpnInterface == null, "unknown VPN owner is not FlClash");
        InterfaceSnapshot.MutableEntry second = unknownVpn.entry("tunOther"); second.transports.add(4);
        second.tunDevice = true;
        second.ownerPackages.add(InterfaceSnapshot.FLCLASH_PACKAGE);
        check("tunOther".equals(unknownVpn.build().preferredVpnInterface), "correct VPN owner wins over tun0 name");
        unknownVpn.entry("tun0").ownerPackages.add(InterfaceSnapshot.FLCLASH_PACKAGE);
        check(unknownVpn.build().preferredVpnInterface == null, "ambiguous owned VPNs not guessed");
        unknownVpn.entry("tunOther").defaultNetwork = true;
        check("tunOther".equals(unknownVpn.build().preferredVpnInterface), "active owned VPN resolves ambiguity");
        unknownVpn.entry("tunOther").tunDevice = false;
        check("tun0".equals(unknownVpn.build().preferredVpnInterface), "FlClash ownership and VPN transport cannot substitute for actual TUN type");
        check(!InterfaceSnapshot.validName("../tun0") && !InterfaceSnapshot.validName("a b")
                && !InterfaceSnapshot.validName("1234567890123456") && InterfaceSnapshot.validName("odd-name.9"), "kernel interface name validation");
        InterfaceSnapshot.Builder exits = new InterfaceSnapshot.Builder();
        for (String name : Arrays.asList("rmnet_data0", "wlan0", "eth0", "tunPhysical")) {
            InterfaceSnapshot.MutableEntry e = exits.entry(name);
            e.up = true; e.address("192.0.2.7");
            Plan blocked = PolicyEngine.evaluate(Arrays.asList(foreign), selections("card-a", name), exits.build(), Collections.emptyMap(), false);
            check(blocked.actions.size() == 1 && blocked.actions.get(0).kind == Kind.TAKEOVER, "available interface can be attempted: " + name);
        }
        InterfaceSnapshot.MutableEntry tunnel = exits.entry("proxy42");
        tunnel.up = true; tunnel.address("198.18.0.1"); tunnel.tunDevice = true;
        Plan tun = PolicyEngine.evaluate(Arrays.asList(foreign), selections("card-a", "proxy42"), exits.build(), Collections.emptyMap(), false);
        check(tun.actions.size() == 1 && tun.actions.get(0).kind == Kind.TAKEOVER, "real TUN accepted without tun name prefix or physical Wi-Fi");
        Plan wrong = PolicyEngine.evaluate(Arrays.asList(foreign), selections("card-a", "rmnet_data0"), exits.build(), applied(old), false);
        check(wrong.actions.size() == 2 && wrong.actions.get(0).kind == Kind.CLEAR && wrong.actions.get(1).kind == Kind.TAKEOVER, "explicit change replaces old selection and attempts selected physical NIC");
        check(wrong.decisions.get(0).selection.interfaceName.equals("rmnet_data0"), "explicit selection retained, not silently replaced");
        for (String raw : Arrays.asList("1", "0x1001", "0x5001\n")) check(TunEgress.isTunFlags(raw), "IFF_TUN accepted");
        for (String raw : Arrays.asList(null, "", "2", "0x1002", "3", "0", "-1", "garbage", "0x100000001"))
            check(!TunEgress.isTunFlags(raw), "TAP or unknown flags rejected");
        check(!new InterfaceSnapshot.Builder().build().selection("@system").available(), "no system route cannot cut the radio for a nonexistent path");
        exits.entry("wlan0").defaultRoute = true;
        check(!exits.build().selection("@system").available(), "an unused routing-table default is not the active system network");
        exits.entry("wlan0").defaultNetwork = true;
        Plan system = PolicyEngine.evaluate(Arrays.asList(foreign), selections("card-a", "@system"), exits.build(), Collections.emptyMap(), false);
        check(system.actions.size() == 1 && system.actions.get(0).interfaceName.equals("@system"), "system routing is explicit and does not select a proxy");
        check(PolicyEngine.evaluate(Arrays.asList(foreign), selections("card-a", "@system"), exits.build(), applied(system.actions.get(0).asAppliedOverride()), false).actions.isEmpty(), "system route remains selected across policy ticks");
        check(TunEgress.selector("@system").equals("system") && TunEgress.interfaceFromSelector("system").equals("@system"), "system selector round trip");
        try { TunEgress.requireInterface("@system"); }
        catch (java.io.IOException e) { throw new AssertionError("system mode looked for a fake interface", e); }
        try { TunEgress.requireInterface("svwmissing9"); throw new AssertionError("missing explicit interface accepted"); }
        catch (java.io.IOException expected) { }
        check(PolicyEngine.evaluate(Arrays.asList(foreign), selections("card-a", "@system"), exits.build(), applied(old), true).actions.isEmpty(), "system selection respects call protection");
        check(TunEgress.selector("proxy42").equals("iface:proxy42"), "explicit selector retained");
        check(TunEgress.interfaceFromSelector("iface:proxy42").equals("proxy42"), "selector round trip");
        for (String selector : Arrays.asList("sim1", "sim2", "auto", "wifi", "route:system", "iface:", "iface:../tun0", "iface:tun0\n")) {
            try { TunEgress.interfaceFromSelector(selector); throw new AssertionError("unsafe selector accepted"); }
            catch (IllegalArgumentException expected) { check(true, "automatic or invalid selector rejected"); }
        }
        InterfaceSnapshot.Builder rootTun = new InterfaceSnapshot.Builder();
        InterfaceParser.ipOutput("IFACE\tproxy42\nTUNFLAGS\tproxy42\t0x1001\nIFACE\ttap0\nTUNFLAGS\ttap0\t0x1002\n", rootTun);
        check(rootTun.build().entries.get("proxy42").voWifiTun(), "root observation supplies real TUN type");
        check(!rootTun.build().entries.get("tap0").voWifiTun(), "TAP is not misclassified as TUN");

        InterfaceSnapshot.Builder builtin = new InterfaceSnapshot.Builder();
        InterfaceSnapshot.MutableEntry fl = builtin.entry("tun0");
        fl.up = true; fl.address("198.18.0.1"); fl.tunDevice = true; fl.transports.add(4);
        fl.ownerPackages.add(InterfaceSnapshot.FLCLASH_PACKAGE);
        InterfaceSnapshot.MutableEntry wfc = builtin.entry("wfctun0");
        wfc.up = true; wfc.address("198.19.0.1"); wfc.tunDevice = true;
        InterfaceSnapshot.MutableEntry wfc1 = builtin.entry("wfctun1");
        wfc1.up = true; wfc1.address("198.19.1.1"); wfc1.tunDevice = true;
        InterfaceSnapshot builtSnap = builtin.build();
        check("wfctun0".equals(builtSnap.preferredVpnInterface), "built-in Clash wfctun0 preferred over external FlClash when ready");
        check("内置 Clash".equals(builtSnap.entries.get("wfctun0").type()), "built-in Clash interface clearly labeled");
        check("内置 Clash 2".equals(builtSnap.entries.get("wfctun1").type()), "built-in Clash 2 interface clearly labeled");
        check(InterfaceSnapshot.isBuiltinClashTun("wfctun0") && InterfaceSnapshot.isBuiltinClashTun("wfctun1"),
                "both wfctun0 and wfctun1 recognized as built-in Clash TUNs");
        wfc.tunDevice = false;
        check("wfctun1".equals(builtin.build().preferredVpnInterface), "falls back to ready wfctun1 when wfctun0 is unavailable");
        wfc1.tunDevice = false;
        check("tun0".equals(builtin.build().preferredVpnInterface), "spoofed non-TUN wfctun0/wfctun1 falls back to verified FlClash");
        System.out.println("PASS " + checks + " policy/network assertions; no Android, root, calls or SMS executed.");
    }
}
