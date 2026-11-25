# 更新日志（UpLog）

记录 **SmartVoWifiCall** 每个版本的核心新特性、架构演进与真机修复。更细粒度的技术验证过程见 [VERSION_HISTORY.md](./VERSION_HISTORY.md)。

---

## v0.6.3-dev — 2026-11-26

- 修复内置代理改写 UDP 回包来源后被连接式 socket 丢弃的问题；保留 SPI、完整性和对端 AUTH 校验。
- 补齐 CTExcel 的 IPv6 IMS 隧道、SIP 地址处理和 `ipsec-3gpp` 安全协商。
- 用 SIP 标准短头和精简的 REGISTER 避免双层 ESP 封装后超过代理 TUN MTU；补充安全协商日志及 IPv6 脱敏。
- 小米 15 + CTExcel + 现有英国节点已实测 EAP、最终 AUTH 和 IMS REGISTER 200，应用显示已注册。未测试真实电话和短信，不能据此认定 POCO F3 / giffgaff 已修复。
- [根因、修复与验证记录](service/docs/CTEXCEL_VALIDATION.md)。应用和模块 versionCode=23。

## v0.6.2-dev — 2026-11-26

应用与模块 versionCode=22。本版修复已复现的 IKE/EAP 认证流程问题，并加入认证包诊断与遥测 v3。

- **认证流程**：声明 EAP_ONLY_AUTHENTICATION；必须验证 AKA 挑战并收到 EAP-Success 才发送最终 AUTH；验证对端最终 AUTH；设备身份应答延后到挑战认证成功；区分最终认证拒绝与身份协商错误。
- **认证日志**：记录 IKE/EAP、SIP REGISTER 的方向、阶段、Payload 类型及可公开内容、错误、引擎版本与源码摘要。身份保留协议前缀和运营商域，仅遮盖用户部分；密钥、认证证明和其他隐私值打码。
- **遥测 v3**：手动上传或启用遥测时默认附带留存的认证记录；超限明确分包，检查分包回执，日志缺口和部分上传失败明确显示。每个引擎保留当前及上一份 256 KiB 认证日志，不是无限历史归档。
- **验证**：独立本地认证验证、Go 测试及 vet、Java 遥测测试、Worker 测试与构建校验通过；260 条长记录分为 7 包并完整保留。小米 15 + 3HK 通过大陆卡数据及香港代理完成 EAP/最终 AUTH 和 IMS REGISTER 200，观察约 136 秒保持注册，测试后恢复原配置；未测试通话和短信。
- **线上中转**：Worker v3 已上线并保留原密钥绑定；180 条人工记录及 20,000 字符公开 Payload 的真实入库、局部脱敏和回执验证通过，测试记录已删除。
- **POCO F3**：原 giffgaff Notify 24 的真实原因仍待新版遥测验证，不能把小米 15 的注册成功当作 F3 已修复。升级模块并重启后再复现、手动上传，提供全部日志 ID。

## v0.6.1-dev — 2026-11-25

> **实用体验增强与欧美 ePDG 兼容版本：新增内置 Clash 摄像头/相册扫码添加订阅、应用内一键检查 GitHub Release 更新与模块直链下载（免浏览器直存 `Download/`）、升级自研 Go 引擎 3GPP IKEv2 兼容与自动 NAI 协商，并根治 `RootDaemonMain` 每 5 秒重复拉起问题。**

### 核心新特性
- **应用内检查更新 & 模块直链下载（免浏览器）**
  - 「网络」页新增 **「检查更新」** 按钮：查询 GitHub Releases（兼容正式版与 `-dev` 预发布版，过滤草稿），发现新版本时弹出详情对话框展示版本号对比、发布日期与完整更新内容。
  - 支持 **「下载模块」**（在应用内经 HTTPS 直连下载，严格校验 GitHub 可信域名与 ZIP 文件头，实时进度条与可取消，通过 `MediaStore.Downloads` 原子写入系统 `Download/` 文件夹，无需跳转浏览器）或 **「前往 GitHub」**。
- **内置 Clash 摄像头 / 相册扫码订阅**
  - 代理页（内核 1 / 内核 2 均支持）新增 **「扫码订阅」**，基于 CameraX + 纯 Java ZXing 离线识别二维码（支持手电筒与相册选图），兼容 `http(s)://`、`clash://install-config?url=...`、`clashmeta://`、`mihomo://`、`flclash://`、`sub://`（Base64）等订阅协议。

### 真机兼容与稳定性修复
- **Go 引擎 IKEv2 3GPP 兼容性增强（针对 O2 UK / giffgaff `23410` 等欧美 ePDG `Notify 24`）**
  - 3HK（`45403`）保持优先使用 `@wlan...`（实机冷启动 1.72 秒完成 `IMS REGISTER 200 OK`）；O2 UK / giffgaff 等现代 3GPP EPC 运营商优先按 3GPP TS 23.003 §19.3.2 使用 `0<IMSI>@nai.epc...`，遇 `Notify 24 (AUTHENTICATION_FAILED)` 自动回退轮换 `6<IMSI>@nai.epc...`（EAP-AKA'）→ `0<IMSI>@wlan...` → `IDr=ims.mnc...` 并缓存成功配置。
  - `IKE_AUTH #1` 补齐 `INITIAL_CONTACT (16384)` 与 `MOBIKE_SUPPORTED (16396)`；新增 **3GPP TS 24.302 §8.2.9.2 `DEVICE_IDENTITY (41101)`**（IMEI / IMEISV TBCD 编码）自动应答。
  - 移除 Go 引擎 `config-verify --strict` 中残留的 `MAINLAND_SIM_FORBIDDEN`（MCC `460`/`461`）拦截，与 Java 层全卡解锁保持一致。
- **彻底修复 `RootDaemonMain` 每 5 秒重复拉起与耗电问题**
  - `RootDaemonMain` 获取独占文件锁后原子写入权威 `root-daemon.pid` / `root-daemon.owner`；`service.sh` 增补 `/proc/*/cmdline` 存活检测与异常启动退避；`RootSupervisor` 改为全局冷却并在守护进程已存活时仅修复 socket 属主，同时将 `root-daemon.log`、`sim-auth.log` 与完整进程快照纳入遥测诊断。

---

## v0.6.0-dev — 2026-11-25

> **双卡并发与双内置代理版本：解除国内外 SIM 卡限制、重构总开关省电停机与卡槽 1/2 独立控制、新增双内置 Clash 内核（`wfctun0` / `wfctun1`），并基于真机遥测修复单卡/老基带关蜂窝失败、SELinux 拦截与遥测上传稳定性。**

### 核心新特性
- **解除国内外 SIM 卡限制，支持双卡同时 VoWiFi**
  - 移除对大陆 MCC（`460` / `461`）的硬性拦截，所有就绪 SIM 卡只要在对应卡槽开启开关即可拉起 VoWiFi（首次升级默认不自动接管大陆卡，避免误关蜂窝）。
  - 双卡槽并发支持：卡槽 1 与卡槽 2 可同时运行独立的自研 Go 引擎、独立 `MmTelFeature`、独立通话/短信计费开关与独立 TUN 出口。
- **总开关省电停机 + 停止前状态记忆恢复**
  - 原通话开关升级为「网络通话总开关」：关闭后立即停止全部 VoWiFi 引擎、恢复所有卡槽原厂蜂窝/IMS，并自动记录停止前两个 Clash 内核的启停状态后全部停机省电；重新打开总开关时自动恢复停止前的内核开关与上次选中节点。
  - 概览页新增卡槽 1 / 卡槽 2 独立卡片：各自展示地区标签、实时隧道 / IMS / 蜂窝 / 当前代理节点，支持一键绑定 `wfctun0`、`wfctun1` 或外部 TUN。
- **双内置 Clash（`mihomo`）独立内核（`wfctun0` / `wfctun1`）**
  - 新增内核 2（`clash1`，`wfctun1`，`198.19.1.1/30`，DNS `:1054`，控制端口 `:19091`），与内核 1（`wfctun0`，`198.19.0.1/30`，DNS `:1053`，控制端口 `:19090`）各自运行在独立 Linux 网络命名空间、各自绕过系统 `tun0` VPN、均由手动开关独立控制。
  - 代理页与连接页顶部新增 **`内核 1 · wfctun0` | `内核 2 · wfctun1`** 切换栏，支持内核 2 一键从内核 1 复制订阅与配置、独立选节点/测速、独立查看连接与 External Controller 管理。

### 真机兼容与遥测修复
- **修复老基带与单卡设备 `RADIO_COMMAND_REJECTED`（实测反馈来自 POCO F3 SM8250 / Android 17）**
  - 优先使用 `enableModemForSlot` 实现 DSDS 严格单卡隔离；当老 RIL 或单卡设备拒绝 `enableModemForSlot` 且不会影响其他未接管激活卡槽时，自动安全回退到 `setRadioForSubscriber` / `setRadioPowerForReason` / `setRadio`，并在射频租约中持久化恢复方式。
- **补全 Android 17 与 `untrusted_app` SELinux 策略与 `priv-app` 自检**
  - `sepolicy.rule` 扩展放行 `priv_app` / `priv_app_36` / `priv_app_37` / `untrusted_app` / `untrusted_app_all` 访问 `shell_data_file` 套接字及 `ksu` / `su` / `magisk` 守护连接；兼容性自检新增 `priv-app` 挂载检测提示。
- **彻底修复遥测日志上传报错与节点名脱敏**
  - `RootSupervisor` 改为异步写入 `stdin` 并安全忽略 `EPIPE`，根治 `IOException (Broken pipe)` 掩盖真实错误码的问题。
  - 新增 `440 KB` UTF-8 报文预算自动尾部裁剪，彻底杜绝日志累积触发 `TELEMETRY_PAYLOAD_TOO_LARGE`。
  - Wi-Fi Calling 链路上传新增 UDP DNS 自动重试（2 次）、60 秒超时、双内核存活 netns 逐项检测与内置代理未运行时的系统路由自动回退。
  - 日常 `200 OK` 的 Clash `/configs`、`/proxies`、`/connections` 状态轮询不再写入 `http-trace.jsonl`，`LogSanitizer` 兜底拦截截断后的 `/proxies` JSON 杜绝代理节点名泄露。

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
