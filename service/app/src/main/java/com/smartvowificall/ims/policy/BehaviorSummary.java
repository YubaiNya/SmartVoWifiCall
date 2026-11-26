package com.smartvowificall.ims.policy;

import java.util.Map;

/** Display only known diagnostic categories, never raw protocol/error payloads. */
public final class BehaviorSummary {
  private BehaviorSummary() { }

  public static String describe(Map<String, Object> state, boolean connected) {
    if (!connected || state == null || state.isEmpty()) return "底层状态未知";
    String current = String.valueOf(state.get("state"));
    if ("RETRY_BACKOFF".equals(current)) {
      if (Boolean.TRUE.equals(state.get("retryDisabled")))
        return "运营商要求停止重试，请检查服务授权";
      if ("IKE_FINAL_AUTH_REJECTED".equals(state.get("cause")))
        return "EAP 已返回成功，但隧道最终认证被拒绝 · 原因未确定，暂停自动重试";
      String failure = label(String.valueOf(state.get("failureState")));
      if ("EPDG_REJECTED_WITH_BACKOFF".equals(state.get("cause"))) failure = "运营商网关拒绝连接";
      return failure + (Boolean.TRUE.equals(state.get("manualOnly"))
          ? " · 请检查 SIM 或运营商服务后手动重试"
          : Boolean.TRUE.equals(state.get("circuitOpen")) ? " · 多次失败，已延长冷却" : " · 等待重试");
    }
    return label(current);
  }

  private static String label(String value) {
    switch (value) {
      case "EPDG_DISCOVERY": return "正在查找运营商网关";
      case "EPDG_DNS_FAILURE": return "运营商网关域名解析失败";
      case "IKE_CONNECTING": return "正在建立安全连接";
      case "IKE_TIMEOUT": return "安全连接响应超时";
      case "IKE_AUTH_FAILURE": return "安全连接认证失败";
      case "IKE_CHALLENGE_FAILED": return "安全连接挑战未完成";
      case "IPSEC_ESTABLISHED": return "安全隧道已建立";
      case "IMS_REGISTERING": return "正在注册 IMS";
      case "IMS_REGISTRATION_FAILED": return "IMS 注册未完成";
      case "IMS_REGISTERED_IWLAN": return "IMS 已通过安全隧道注册";
      case "VOWIFI_ACTIVE_CALL": return "网络通话中";
      case "RECOVERY_IN_PROGRESS": return "正在恢复，等待注册确认";
      case "DISABLED": return "已停用";
      default: return "底层状态未知";
    }
  }
}
