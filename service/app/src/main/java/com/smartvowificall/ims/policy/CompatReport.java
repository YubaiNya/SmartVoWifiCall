package com.smartvowificall.ims.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java Android 14-17 (API 34-37) compatibility evaluator and read-only degraded mode classifier.
 */
public final class CompatReport {
  public static final int MIN_SUPPORTED_API = 34; // Android 14
  public static final int MAX_VERIFIED_API = 37;  // Android 17

  public static final String STATUS_OK = "OK";
  public static final String STATUS_WARN = "WARN";
  public static final String STATUS_FAIL = "FAIL";

  public static final String MODE_READY = "READY";
  public static final String MODE_DEGRADED_COMPAT = "DEGRADED_COMPAT";
  public static final String MODE_READ_ONLY_NO_ROOT = "READ_ONLY_NO_ROOT";
  public static final String MODE_UNSUPPORTED = "UNSUPPORTED";

  public static final class Input {
    public int apiLevel;
    public boolean rootAvailable;
    public boolean daemonActive;
    public boolean privilegedApp = true;
    public boolean moduleInstalled;
    public boolean engineBinaryReady;
    public String imsOverrideVariant = "";   // "api35_5arg" | "api34_4arg" | ""
    public String modemSlotVariant = "";     // "api35_3arg" | "api34_2arg" | "api34_1arg" | ""
    public String voiceOverrideVariant = ""; // "api35_3arg" | "api34_2arg" | ""
    public boolean iccAuthAvailable;
    public boolean qrtrAvailable;
    public String chipsetFamily = "generic";
  }

  public static final class CheckItem {
    public final String key;
    public final String label;
    public final String status;
    public final String detail;

    public CheckItem(String key, String label, String status, String detail) {
      this.key = key;
      this.label = label;
      this.status = status;
      this.detail = detail;
    }
  }

  public final String mode;
  public final String summary;
  public final List<CheckItem> items;

  private CompatReport(String mode, String summary, List<CheckItem> items) {
    this.mode = mode;
    this.summary = summary;
    this.items = Collections.unmodifiableList(items);
  }

  public static CompatReport evaluate(Input in) {
    List<CheckItem> items = new ArrayList<>();

    boolean apiSupported = in.apiLevel >= MIN_SUPPORTED_API;
    String apiStatus;
    String apiDetail;
    if (in.apiLevel < MIN_SUPPORTED_API) {
      apiStatus = STATUS_FAIL;
      apiDetail = "API " + in.apiLevel + " 低于最低要求 Android 14 (API 34)";
    } else if (in.apiLevel == MAX_VERIFIED_API) {
      apiStatus = STATUS_OK;
      apiDetail = "Android 17 (API 37) 主线完整验证版本";
    } else if (in.apiLevel <= MAX_VERIFIED_API) {
      apiStatus = STATUS_OK;
      apiDetail = "API " + in.apiLevel + " (Android 14–16 兼容模式已启用)";
    } else {
      apiStatus = STATUS_WARN;
      apiDetail = "API " + in.apiLevel + " 高于已验证版本，尝试兼容模式";
    }
    items.add(new CheckItem("android_api", "Android 系统版本", apiStatus, apiDetail));

    if (!in.rootAvailable) {
      items.add(new CheckItem("root_access", "Root 权限", STATUS_FAIL,
          "未检测到 Root 授权，当前处于只读降级模式。请在 KernelSU / Magisk 中授权 SmartVoWifiCall"));
      return new CompatReport(MODE_READ_ONLY_NO_ROOT,
          "未授予 Root 权限（只读降级模式）", items);
    }

    items.add(new CheckItem("root_access", "Root 与常驻守护进程",
        in.daemonActive ? STATUS_OK : STATUS_WARN,
        in.daemonActive ? "Root 已授权，RootDaemonMain 常驻低延迟通道在线"
                        : "Root 已授权（当前通过 su 回退，常驻进程将自动拉起）"));

    items.add(new CheckItem("priv_app", "系统特权安装 (priv-app)",
        in.privilegedApp ? STATUS_OK : STATUS_WARN,
        in.privilegedApp
            ? "已作为系统特权应用挂载运行"
            : "当前以普通应用运行（untrusted_app）；KernelSU 1.x+ 请确认已安装 meta-overlayfs / Magic Mount 元模块并重启"));

    boolean modOk = in.moduleInstalled && in.engineBinaryReady;
    items.add(new CheckItem("module_engine", "模块与自研 Go 引擎",
        modOk ? STATUS_OK : STATUS_FAIL,
        modOk ? "模块已激活且自研 arm64 Go 引擎就绪"
              : (!in.moduleInstalled ? "未检测到已激活的 KernelSU/Magisk 模块"
                                     : "缺少 bin/vowifi-engine-arm64 可执行引擎")));

    boolean imsOk = in.imsOverrideVariant != null && !in.imsOverrideVariant.isEmpty();
    items.add(new CheckItem("ims_override", "IMS 服务覆盖接口",
        imsOk ? STATUS_OK : STATUS_FAIL,
        imsOk ? "已适配 " + in.imsOverrideVariant
              : "未找到 ITelephony.setBoundImsServiceOverride 签名"));

    boolean modemOk = in.modemSlotVariant != null && !in.modemSlotVariant.isEmpty();
    items.add(new CheckItem("single_sim_radio", "单卡蜂窝开关接口",
        modemOk ? STATUS_OK : STATUS_WARN,
        modemOk ? "enableModemForSlot 可用 (" + in.modemSlotVariant + ")"
                : "当前 ROM 缺少单卡 enableModemForSlot，可能无法独立关闭单卡蜂窝"));

    boolean voiceOk = in.voiceOverrideVariant != null && !in.voiceOverrideVariant.isEmpty();
    items.add(new CheckItem("voice_override", "语音状态覆盖接口",
        voiceOk ? STATUS_OK : STATUS_WARN,
        voiceOk ? "setVoiceServiceStateOverride 可用 (" + in.voiceOverrideVariant + ")"
                : "当前 API 版本无语音状态覆盖签名（将自动降级跳过）"));

    boolean akaOk = in.iccAuthAvailable || in.qrtrAvailable;
    String akaDetail;
    if (in.iccAuthAvailable && in.qrtrAvailable) {
      akaDetail = "双通道可用：Binder AKA + 高通 QRTR (" + in.chipsetFamily + ")";
    } else if (in.iccAuthAvailable) {
      akaDetail = "通用 Binder AKA 可用 (" + in.chipsetFamily + ")";
    } else if (in.qrtrAvailable) {
      akaDetail = "高通 QRTR UIM 通道可用 (" + in.chipsetFamily + ")";
    } else {
      akaDetail = "未检测到可用的 SIM AKA 鉴权接口";
    }
    items.add(new CheckItem("sim_aka", "跨基带 SIM AKA 通道",
        akaOk ? STATUS_OK : STATUS_FAIL, akaDetail));

    if (!apiSupported || !modOk || !imsOk || !akaOk) {
      return new CompatReport(MODE_UNSUPPORTED,
          "检测到关键接口缺失或环境不完整，请查看下方红标项", items);
    }
    if (!modemOk || !voiceOk || !in.daemonActive || !in.privilegedApp || in.apiLevel > MAX_VERIFIED_API) {
      return new CompatReport(MODE_DEGRADED_COMPAT,
          "核心通话能力可用（部分增强接口处于兼容降级模式）", items);
    }
    return new CompatReport(MODE_READY, "全部系统接口兼容，可完整启用网络通话", items);
  }
}
