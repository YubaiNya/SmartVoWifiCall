# 更新日志（UpLog）

记录 **SmartVoWifiCall** 每个版本的核心新特性、架构演进与真机修复。更细粒度的技术验证过程见 [VERSION_HISTORY.md](./VERSION_HISTORY.md)。

---

## v0.5.0-dev — 2026-11-24

> **里程碑版本：底层协议栈 100% 纯 Go 自研，彻底摆脱 SoftVoWiFi 预编译二进制依赖，支持跨基带与 Android 14–17，实网修复系统 `tun0` 套娃与通话稳定性。**

### 核心新特性
- **纯自研 Go VoWiFi 协议栈（零第三方依赖）**
  - 彻底移除外部预编译 `SoftVoWiFi` 二进制，使用纯 Go 标准库（`CGO_ENABLED=0`）在 `engine/` 静态编译 `vowifi-engine-arm64`（约 4.2 MB，待机仅 ~1.2 MB RSS，峰值 ~4.2 MB，内存较旧引擎降 5 倍）。
  - 完整实现 RFC 7296 IKEv2（多算法动态协商、`COOKIE`/`INVALID_KE` 自动重试、EAP-AKA/AKA' 含 `AKA-Identity` 与 `AUTS` 重同步）、纯用户态 IPsec ESP（无需内核 XFRM）、3GPP TS 24.229 IMS SIP（注册 / 呼叫 / 早期媒体 / SMS）与 RTP/DTMF 媒体桥。
- **常驻 Root 守护进程（`RootDaemonMain`）**
  - 将 `RootIdentityMain` / `RootClashMain` / `RootNetworkMain` 收拢到独立目录 `/data/local/tmp/smartvowificall/root-daemon.sock`（独占 `FileLock` + `SO_PEERCRED` UID 校验），状态查询延迟从 ~400 ms 降至 0–4 ms，并避免与旧模块共享 `/data/local/tmp/vowifi` 产生权限冲突。
- **跨基带 SIM AKA（打破仅限骁龙限制）**
  - 抽象 `SimAuthProvider`：高通骁龙优先走 root netns `RootSimAuthMain` → 纯 Go `AF_QIPCRTR` QMI UIM（关闭单卡蜂窝下仍可鉴权）；联发科天玑 / Google Tensor / 三星 Exynos 自动走 `IPhoneSubInfo` / `ITelephony` / APDU（含 `61xx` 分段与 ISIM→USIM 回退）通用通道。
- **Android 14–17（API 34–37）正式适配与自检**
  - 放开 `customize.sh` 安装范围至 Android 14–17，按 API Level 自动适配 `ITelephony` Binder 签名；新增「系统接口兼容性自检」与无 Root 只读预览模式。
- **内置 + 可更新的海外运营商与 OEM 适配库**
  - 内置 25 家海外运营商（含 3HK `wlan.three.com.hk`、AT&T、Verizon、T-Mobile、Vodafone 等实网 ePDG 规则）与 5 家 OEM 适配策略，概览页自动展示匹配建议，并拒绝 ePDG DNS 解析到 `127.0.0.1` / `0.0.0.0` 的陷阱结果。
- **通话音质、快捷磁贴与紧急恢复工具**
  - `PcmAudio` 启用硬件 AEC / NS / AGC 与低延迟 `AudioTrack`；新增 Quick Settings 快捷磁贴、一键紧急恢复原厂 IMS 与蜂窝射频、`RadioInfo` 调试入口。

### 真机实网关键修复（Xiaomi 15 / Android 17 / 3HK 实测验证）
- **彻底绕开系统 `tun0` VPN 套娃**：通过 `ip -4 route get 1.1.1.1 mark 0x20000` 自动识别真实物理网卡（`wlan0` / `rmnet_data*`），在内置 `mihomo` 启动及切网时自动绑定 `interface-name` 并清理旧连接，3HK 冷启动注册耗时降至 **1.78 秒**。
- **通道断开立即下掉状态栏 VoWiFi 图标**：实现 RFC 7296 §2.3/§2.4 合规 DPD（同 `msgID` 重传、活跃 RTP/ESP 期间不误杀、15s `0xFF` NAT-T 保活）与对端 `DELETE` 检测；断链瞬间推送 `imsState` 事件，按「清空 `MmTelCapabilities` → `onDeregistered` → 关闭语音覆盖」顺序 1 秒内下掉图标。
- **解决通话中途无声与连续拨号失败**：RTP `JitterBuffer` 支持 `SSRC` 变更 / `Marker` / 大跳变即时重同步并过滤舒适噪声与 RTCP；`INVITE` 增加 RFC 3261 Timer A 重传、携带 `Service-Route`、移除伪造全零 PANI、`183`/`180` 立即起早期媒体；第一通挂断后立即补刷 `VoiceServiceOverride`，避免第二通提示“移动网络不可用”。
- **修复 `RootDaemonMain` 工作线程 `IDENTITY_UNAVAILABLE`**：在主 Looper 线程预热 `ActivityThread.systemMain()`，并增加守护进程异常自动回退保护。

---

## v0.4.7-dev — 2026-11-24

- **遥测上传默认走 Wi-Fi Calling 隔离代理链路**：通过 `nsenter` 复用 `wfctun0`（UDP DNS + 完整 TLS 校验）上传，无需手机主网络开系统代理，同时支持一键切换为「系统路由」。
- **遥测 v2 完整诊断结构与 `[Hidden]` 双重脱敏**：补充非隐私设备/SoC/基带/内核/SIM 国家与运营商信息，所有敏感字段统一显示为 `[Hidden]`，生成可复制的唯一日志 ID（`SVW-YYYYMMDD-XXXXXX`）。

---

## v0.4.6-dev — 2026-11-23

- **新增可选脱敏遥测上传（默认关闭）**：纯 Java 白名单脱敏器 + Cloudflare Worker 中转服务，支持上传前预览完整脱敏报文。
- **新增手动导出全部日志**：支持通过系统文件选择器导出「脱敏日志包（ZIP）」与「原始全部日志包（ZIP）」。

---

## v0.4.5-dev — 2026-11-23

- **按 SIM 记忆与自动切换代理节点**：同一 eSIM 槽位在不同国家/地区海外卡间切换时自动匹配并切到该卡上次成功的代理节点。
- **切节点与刷新立即破除退避**：手动切换节点或点击刷新立即清退避并重建隧道。
- **eSIM 切卡全链路清理与租约迁移**：平滑迁移射频租约，切回大陆卡或停用 eSIM 后自动恢复原厂蜂窝与 IMS。

---

## v0.4.4-dev — 2026-11-23

- **修复远端挂断后下一通卡死 DIALING**：已上报 `initiating`/`progressing` 的会话统一以 `callSessionTerminated` 结束。
- **修复 Android 17 录音静音（silenced）**：延迟至呼叫回调后通知 `AUDIO_HANDLER_ANDROID`，仅通话录音期间持有无声 `microphone` 前台服务租约，挂断立即释放。

---

## v0.4.3-dev — 2026-11-23

- **联动启停与深度节能**：开网络通话自动拉起代理，关闭待通话结束后自动停内核复原蜂窝；稳定态降频轮询，UI 后台停止刷新，代理日志降至 `warning`。

---

## v0.4.2-dev — 2026-11-23

- **修复挂断后语音覆盖被 Telecom 清除**：注册且允许计费时按框架实际语音状态重新应用 `setVoiceServiceStateOverride`。
- **强引用持有 `ImsConfigImplBase`**：修复系统弱引用回收导致的 `RemoteException: Fail to get ImsConfigImpl`。

---

## v0.4.0-dev — 2026-11-22

- **全新 Apple Design + Miuix + 液态玻璃四页面 UI**：重构「概览 / 代理 / 连接 / 网络」四页与三层折射浮动底栏。
- **内置 Clash 外部控制与实时连接管理**：支持本机/局域网 External Controller、密钥轮换与实时查看/断开 `wfctun0` 连接。
- **新增 `FallbackPolicy` 自动回退**：接口失效或注册超时自动释放蜂窝并恢复原厂 IMS；移除待机常驻通知。

---

## v0.3.2-dev — 2026-11-22

- **解决关闭海外卡蜂窝后的拨号拦截**：注册成功且允许计费时调用 `ITelephony.setVoiceServiceStateOverride` 消除“移动网络不可用”拒呼。
- **修复小米升级/强停后 `ImsService` 不重连**：检测到已绑定但未挂载 Feature 时自动先 restore 再 bind。

---

## v0.3.1-dev — 2026-11-22

- **内置 Clash 升级为独立 Linux Network Namespace 隔离**：零 Android VPN、零根命名空间路由改动、零手机其他应用流量代理，仅 `wfctun0` 迁入独立 netns。

---

## v0.3.0-dev — 2026-11-22

- **内置官方 `mihomo` 内核与独立 `wfctun0` 接口**：自动剥离危险配置、强制 `MATCH,WFC-PROXY`，新增订阅拉取、YAML 导入、节点测速与选择。

---

## v0.2.5-dev — 2026-11-22

- **单卡蜂窝互斥（`enableModemForSlot`）**：仅关闭海外卡蜂窝协议栈，保持大陆卡正常在网并彻底防止普通呼叫误回退漫游扣费。

---

## v0.1.0-dev – v0.2.4-dev — 2026-11-22

- **奠基版本系列**：建立独立 Android `ImsService` 框架、内置 SIM 凭据读取与网络快照、单 ZIP 自动覆盖安装 APK、真实 `IFF_TUN` 校验与按需注册接管机制。
