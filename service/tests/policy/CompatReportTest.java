import com.smartvowificall.ims.policy.CompatReport;

public final class CompatReportTest {
  private static int checks = 0;

  private static void check(boolean cond, String msg) {
    checks++;
    if (!cond) throw new AssertionError("FAIL: " + msg);
  }

  public static void main(String[] args) {
    // 1. Android 17 full ready
    CompatReport.Input a17 = new CompatReport.Input();
    a17.apiLevel = 37;
    a17.rootAvailable = true;
    a17.daemonActive = true;
    a17.moduleInstalled = true;
    a17.engineBinaryReady = true;
    a17.imsOverrideVariant = "api35_5arg";
    a17.modemSlotVariant = "api35_3arg";
    a17.voiceOverrideVariant = "api35_3arg";
    a17.iccAuthAvailable = true;
    a17.qrtrAvailable = true;
    a17.chipsetFamily = "qualcomm";
    CompatReport r17 = CompatReport.evaluate(a17);
    check(CompatReport.MODE_READY.equals(r17.mode), "API 37 full ready");
    check(r17.items.size() == 8, "8 check items");

    // 1b. Non-privileged install (untrusted_app) -> DEGRADED_COMPAT warning
    CompatReport.Input unpriv = new CompatReport.Input();
    unpriv.apiLevel = 37;
    unpriv.rootAvailable = true;
    unpriv.daemonActive = true;
    unpriv.privilegedApp = false;
    unpriv.moduleInstalled = true;
    unpriv.engineBinaryReady = true;
    unpriv.imsOverrideVariant = "api35_5arg";
    unpriv.modemSlotVariant = "api35_3arg";
    unpriv.voiceOverrideVariant = "api35_3arg";
    unpriv.iccAuthAvailable = true;
    CompatReport rUnpriv = CompatReport.evaluate(unpriv);
    check(CompatReport.MODE_DEGRADED_COMPAT.equals(rUnpriv.mode), "Non-priv-app install warns degraded compat");

    // 2. Android 14 (API 34) MediaTek with 4-arg IMS + Binder AKA only
    CompatReport.Input a14 = new CompatReport.Input();
    a14.apiLevel = 34;
    a14.rootAvailable = true;
    a14.daemonActive = true;
    a14.moduleInstalled = true;
    a14.engineBinaryReady = true;
    a14.imsOverrideVariant = "api34_4arg";
    a14.modemSlotVariant = "api34_2arg";
    a14.voiceOverrideVariant = "";
    a14.iccAuthAvailable = true;
    a14.qrtrAvailable = false;
    a14.chipsetFamily = "mediatek";
    CompatReport r14 = CompatReport.evaluate(a14);
    check(CompatReport.MODE_DEGRADED_COMPAT.equals(r14.mode), "API 34 without voice override is degraded compat");

    // 3. No Root -> READ_ONLY_NO_ROOT degraded mode
    CompatReport.Input noRoot = new CompatReport.Input();
    noRoot.apiLevel = 35;
    noRoot.rootAvailable = false;
    CompatReport rNoRoot = CompatReport.evaluate(noRoot);
    check(CompatReport.MODE_READ_ONLY_NO_ROOT.equals(rNoRoot.mode), "No root enters read-only mode");
    check(rNoRoot.items.size() == 2, "Read-only report has API + root items");

    // 4. Below Android 14 -> UNSUPPORTED
    CompatReport.Input a13 = new CompatReport.Input();
    a13.apiLevel = 33;
    a13.rootAvailable = true;
    a13.moduleInstalled = true;
    a13.engineBinaryReady = true;
    a13.imsOverrideVariant = "api34_4arg";
    a13.iccAuthAvailable = true;
    CompatReport r13 = CompatReport.evaluate(a13);
    check(CompatReport.MODE_UNSUPPORTED.equals(r13.mode), "API 33 unsupported");

    System.out.println("PASS " + checks + " Android 14-17 compatibility report assertions.");
  }
}
