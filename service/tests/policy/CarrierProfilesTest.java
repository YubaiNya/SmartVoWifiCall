import com.smartvowificall.ims.policy.CarrierProfiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class CarrierProfilesTest {
  private static int checks = 0;

  private static void check(boolean cond, String msg) {
    checks++;
    if (!cond) throw new AssertionError("FAIL: " + msg);
  }

  public static void main(String[] args) throws Exception {
    Path asset = Paths.get("service/app/src/main/assets/carrier_profiles.json");
    if (!Files.isRegularFile(asset)) {
      asset = Paths.get("../../app/src/main/assets/carrier_profiles.json");
    }
    String raw = new String(Files.readAllBytes(asset), StandardCharsets.UTF_8);
    CarrierProfiles builtIn = CarrierProfiles.parseJson(raw);

    check(builtIn.carrierCount() >= 20, "at least 20 overseas carriers built-in");
    check(builtIn.oemCount() >= 5, "at least 5 OEM quirk profiles built-in");

    CarrierProfiles.Carrier hk3 = builtIn.findByPlmn("45403");
    check(hk3 != null, "3HK found");
    check(hk3.strictHomeIp, "3HK strict home IP");
    check("epdg.epc.mnc003.mcc454.pub.3gppnetwork.org".equals(hk3.epdgFqdn), "3HK ePDG FQDN");
    check(hk3.uiSummary().contains("香港"), "3HK summary recommends HK");

    CarrierProfiles.Carrier threeUk = builtIn.findByPlmn("23420");
    check(threeUk != null && threeUk.strictHomeIp, "Three UK found & strict");

    CarrierProfiles.OemQuirk xiaomi = builtIn.findOem("Xiaomi");
    check(xiaomi != null && "enableModemForSlot".equals(xiaomi.radioPolicy), "Xiaomi OEM quirk");

    // Overlay merges updates and refuses mainland China MCC 460/461 entries
    String overlay = "{\"carriers\":["
        + "{\"plmn\":\"45403\",\"name\":\"3HK Custom\",\"region\":\"香港 (HK)\",\"strictHomeIp\":true,\"epdgFqdn\":\"custom.epdg.3hk.example\",\"paniPolicy\":\"sanitize\",\"note\":\"override\"},"
        + "{\"plmn\":\"46000\",\"name\":\"Mainland\",\"region\":\"CN\",\"strictHomeIp\":false}"
        + "]}";
    CarrierProfiles merged = CarrierProfiles.parseAndMerge(raw, overlay);
    check("3HK Custom".equals(merged.findByPlmn("45403").name), "overlay updates carrier");
    check("custom.epdg.3hk.example".equals(merged.findByPlmn("45403").epdgFqdn), "overlay updates ePDG");
    check(merged.findByPlmn("46000") == null, "mainland PLMN excluded from carrier registry");

    System.out.println("PASS " + checks + " carrier & OEM compatibility registry assertions.");
  }
}
