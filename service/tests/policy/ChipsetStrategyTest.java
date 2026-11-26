import com.smartvowificall.ims.policy.ChipsetStrategy;

public final class ChipsetStrategyTest {
  private static int checks = 0;

  private static void check(boolean cond, String msg) {
    checks++;
    if (!cond) throw new AssertionError("FAIL: " + msg);
  }

  public static void main(String[] args) {
    check(ChipsetStrategy.detect("qcom", "sun", "sun", "Qualcomm") == ChipsetStrategy.Family.QUALCOMM, "Snapdragon 8 Elite");
    check(ChipsetStrategy.detect("qcom", "kalama", "sm8550", "QTI") == ChipsetStrategy.Family.QUALCOMM, "Snapdragon 8 Gen 2");
    check(ChipsetStrategy.detect("mt6989", "k6989v1", "mt6989", "Mediatek") == ChipsetStrategy.Family.MEDIATEK, "Dimensity 9300");
    check(ChipsetStrategy.detect("zuma", "shiba", "gs301", "Google") == ChipsetStrategy.Family.TENSOR, "Pixel Tensor G3");
    check(ChipsetStrategy.detect("s5e9945", "s5e9945", "exynos", "Samsung") == ChipsetStrategy.Family.EXYNOS, "Exynos 2400");
    check(ChipsetStrategy.detect("ranchu", "goldfish", "emu", "unknown") == ChipsetStrategy.Family.GENERIC, "Generic fallback");

    check("qrtr-first".equals(ChipsetStrategy.Family.QUALCOMM.akaOrder), "Qualcomm qrtr-first");
    check("binder-first".equals(ChipsetStrategy.Family.MEDIATEK.akaOrder), "MediaTek binder-first");
    check("binder-first".equals(ChipsetStrategy.Family.TENSOR.akaOrder), "Tensor binder-first");
    check("binder-first".equals(ChipsetStrategy.Family.EXYNOS.akaOrder), "Exynos binder-first");
    check("auto".equals(ChipsetStrategy.recommendedSimMode(ChipsetStrategy.Family.MEDIATEK)), "auto mode");
    check(ChipsetStrategy.radioStrategyNote(ChipsetStrategy.Family.TENSOR).contains("APDU"), "Tensor APDU note");

    System.out.println("PASS " + checks + " cross-baseband chipset strategy assertions.");
  }
}
