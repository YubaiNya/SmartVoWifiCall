package com.newvowifi.ims.root;

/** Pure validators only: main(), Android APIs, subprocesses and config writes are never executed. */
public final class RootIdentityValidationTest {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        check(RootIdentityMain.validOutputPath("/data/adb/newvowifi_ims/config/19.json"), "own numeric config allowed");
        for (String path : new String[]{null, "", "/data/adb/softvowifi/config/19.json", "/data/adb/newvowifi_ims/config/../19.json",
                "/data/adb/newvowifi_ims/config/.json", "/data/adb/newvowifi_ims/config/1/19.json", "/data/adb/newvowifi_ims/config/19.json/",
                "/data/adb/newvowifi_ims/config/a.json", "/data/adb/newvowifi_ims/config/１９.json", "/data/adb/newvowifi_ims/config/19.json\n"})
            check(!RootIdentityMain.validOutputPath(path), "non-owned or invalid path rejected");
        String iccid = "8900000000000000019";
        check(iccid.equals(RootIdentityMain.normalizeIccid(iccid + "FF")), "ICCID padding normalization");
        check(RootIdentityMain.normalizeIccid("old-value").isEmpty(), "arbitrary identity rejected");
        check(RootIdentityMain.normalizeIccid(" " + iccid).isEmpty(), "no silent whitespace identity rewrite");
        check(RootIdentityMain.normalizeIccid(null).isEmpty(), "missing ICCID unknown");
        String home = "23430", imsi = "234300000000019", imei = "000000000000019";
        check(RootIdentityMain.validPreparedIdentity(iccid, home, 1, iccid, "234", "30", 1, imsi, imei), "matching current identity accepted");
        check(!RootIdentityMain.validPreparedIdentity(iccid, home, 1, "8900000000000000020", "234", "30", 1, imsi, imei), "other SIM rejected");
        check(!RootIdentityMain.validPreparedIdentity(iccid, home, 1, iccid, "234", "30", 0, imsi, imei), "other slot rejected");
        check(!RootIdentityMain.validPreparedIdentity(iccid, home, 1, iccid, "460", "30", 1, imsi, imei), "MCC mismatch rejected");
        check(!RootIdentityMain.validPreparedIdentity(iccid, home, 1, iccid, "234", "030", 1, imsi, imei), "MNC length preserved");
        check(!RootIdentityMain.validPreparedIdentity(iccid, home, 1, iccid, "234", "30", 1, "460000000000019", imei), "stale IMSI rejected");
        check(!RootIdentityMain.validPreparedIdentity(iccid, home, 1, iccid, "234", "30", 1, imsi, ""), "missing real IMEI rejected");
        check(!RootIdentityMain.validPreparedIdentity("", home, 1, "", "234", "30", 1, imsi, imei), "empty identity never matches itself");
        System.out.println("PASS " + checks + " identity/path validation assertions; Android and subprocess entry points not executed.");
    }
}
