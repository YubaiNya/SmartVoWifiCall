package com.smartvowificall.ims.policy;

import java.util.Locale;

/**
 * Per-chipset SIM AKA backend ordering and single-SIM radio strategy across
 * Qualcomm Snapdragon, MediaTek Dimensity, Google Tensor, Samsung Exynos, and generic SoCs.
 */
public final class ChipsetStrategy {
  public enum Family {
    QUALCOMM("qualcomm", "高通骁龙", "qrtr-first", true),
    MEDIATEK("mediatek", "联发科天玑", "binder-first", false),
    TENSOR("tensor", "Google Tensor", "binder-first", false),
    EXYNOS("exynos", "三星 Exynos", "binder-first", false),
    GENERIC("generic", "通用平台", "binder-first", false);

    public final String id;
    public final String displayName;
    public final String akaOrder;
    public final boolean qrtrSupported;

    Family(String id, String displayName, String akaOrder, boolean qrtrSupported) {
      this.id = id;
      this.displayName = displayName;
      this.akaOrder = akaOrder;
      this.qrtrSupported = qrtrSupported;
    }
  }

  private ChipsetStrategy() {}

  public static Family detect(String hardware, String board, String platform, String manufacturer) {
    String combined = (safe(hardware) + " " + safe(board) + " "
        + safe(platform) + " " + safe(manufacturer)).toLowerCase(Locale.ROOT);
    String[] tokens = combined.split("[^a-z0-9]+");
    if (containsAny(combined, "qualcomm", "snapdragon")
        || hasTokenOrPrefix(tokens, "qcom", "sm8", "sm7", "sm6", "sdm", "kona",
            "lahaina", "taro", "kalama", "pineapple", "sun", "canoe")) {
      return Family.QUALCOMM;
    }
    if (containsAny(combined, "mediatek", "dimensity", "helio")
        || hasTokenOrPrefix(tokens, "mt6", "mt8")) {
      return Family.MEDIATEK;
    }
    if (containsAny(combined, "tensor", "google")
        || hasTokenOrPrefix(tokens, "gs101", "gs201", "gs301", "zuma", "zumapro", "laguna")) {
      return Family.TENSOR;
    }
    if (containsAny(combined, "exynos", "samsung")
        || hasTokenOrPrefix(tokens, "s5e", "universal", "erd")) {
      return Family.EXYNOS;
    }
    return Family.GENERIC;
  }

  /**
   * Returns the recommended engine --sim-mode flag for the detected chipset.
   * All platforms default to "auto" so the engine can automatically probe and fall back
   * between Root Binder AKA and pure-Go QRTR UIM without manual configuration.
   */
  public static String recommendedSimMode(Family family) {
    return "auto";
  }

  /**
   * On Qualcomm DSDS, QRTR communicates directly with the UIM service even after
   * enableModemForSlot(slot, false) disables the cellular stack. On non-Qualcomm SoCs,
   * Android RIL/UICC availability while a slot's modem is powered off varies by OEM, so
   * the Binder AKA path uses getIccAuthentication with APDU logical-channel fallback.
   */
  public static String radioStrategyNote(Family family) {
    if (family == Family.QUALCOMM) {
      return "高通平台：单卡关闭蜂窝后优先走 QRTR UIM，辅以 Root Binder AKA 自动回退";
    }
    return family.displayName + "平台：优先走 Root Binder ITelephony.getIccAuthentication + APDU 逻辑通道（含 61xx GET RESPONSE 续读）";
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static boolean containsAny(String haystack, String... needles) {
    for (String n : needles) {
      if (haystack.contains(n)) return true;
    }
    return false;
  }

  private static boolean hasTokenOrPrefix(String[] tokens, String... prefixes) {
    for (String tok : tokens) {
      if (tok.isEmpty()) continue;
      for (String p : prefixes) {
        if (tok.equals(p) || (p.length() >= 3 && (p.endsWith("6") || p.endsWith("7") || p.endsWith("8") || p.equals("s5e") || p.equals("sdm") || p.equals("universal")) && tok.startsWith(p))) {
          return true;
        }
      }
    }
    return false;
  }
}
