# SmartVoWifiCall TODO & 演进路线图

> 坚持现有技术路线：**独立外挂 `ImsService` + 隔离网络命名空间内置 `mihomo` + 免 LSPosed + 零基带 EFS 修改**。

---

## P0（首要任务）：移除 SoftVoWiFi 预编译二进制依赖，实现全栈源码自主可控 ✅（核心已完成，待真机实网回归）

已彻底移除 `service/deps/vowifi-engine-arm64*`、`service/deps/engine.source.json` 与 `.rodata` 二进制补丁，完成纯自研、零外部 Go 依赖、静态编译 `arm64` 的 `engine/` 协议栈并接入一键构建与校验流程。

- [x] **锁定与梳理引擎契约接口（不破坏现有 Android 上层）**
  - [x] 完整实现并对齐控制面协议 v1（多并发 Unix Socket 换行 UTF-8 JSON：`hello`、`ping`、`getClientCapabilities`、`getConfig`、`getStatus`、`getCallSnapshot`、`dial`、`answer`、`reject`、`hangup`、`cancelCall`、`sendDtmf`、`audioStart`、`audioStop`、`sendSms`、`stop` 与异步 `{event,data}` 推送）
  - [x] 完整实现音频面协议（`*.audio.sock` 无握手原始帧 `BE32(644) + 640B 16kHz Mono PCM16`，与 `PcmFrameCodec` 完全对齐）
  - [x] 完整实现 `config-verify --strict` CLI 与原生 `/data/adb/smartvowificall_ims` 隔离路径，彻底告别 `.rodata` 二进制补丁
- [x] **实现自主可控的 VoWiFi 协议引擎（纯 Go 零外部依赖，静态编译 `arm64-v8a`）**
  - [x] **隔离出口与解析模块（`engine/internal/config`）**：支持 `iface:wfctun0` 校验、3GPP ePDG FQDN 推导、自定义直连 UDP DNS 解析与 `[Hidden]` 脱敏诊断日志
  - [x] **跨基带 SIM/ISIM 鉴权抽象层（`engine/internal/simauth` + `RootSimAuthMain`）**：同时实现通用 Android Root Binder 通道（`ITelephony.getIccAuthentication` + APDU 逻辑通道兜底，支持骁龙/天玑/Tensor/Exynos）与纯 Go 高通 `AF_QIPCRTR` QMI UIM 后端，支持 `AUTS` 重同步与 `auto` 自动探测回退
  - [x] **IKEv2 + EAP-AKA / EAP-AKA' + 用户态 IPsec ESP 隧道（`engine/internal/ikev2`、`engine/internal/ipsec`）**：支持 MODP-2048 / ECP-256、AES-GCM / AES-CBC+HMAC-SHA1/SHA256、抗重放窗口、NAT-T（UDP 4500）、Configuration Payload（内网 IP + P-CSCF）、DPD 心跳与纯用户态虚拟 IPv4/UDP/TCP
  - [x] **IMS SIP 协议栈（`engine/internal/sip`）**：支持 `REGISTER`（AKAv1/AKAv2 Digest + `AUTS`）、脱敏 `P-Access-Network-Info`、呼叫状态机（`INVITE/180/183/PRACK/200/ACK/CANCEL/BYE`）与 3GPP SMS-over-IP（`RP-DATA/RP-ACK` + GSM 7-bit / UCS-2）
  - [x] **媒体面 RTP/RTCP 与语音桥接（`engine/internal/media`）**：实现 G.711（PCMU/PCMA）16kHz↔8kHz 重采样、自适应抖动缓冲、RFC 4733 DTMF 与 `AudioServer` 桥接，并预留可插拔 `ExternalAMRFactory` 接口
  - [ ] *可选增强*：接入 AMR-WB / AMR-NB 编解码后端（NDK 静态编译 `opencore-amr` / `vo-amrwbenc` 或 Android `MediaCodec` 桥接），适配仅允许 AMR 编码（拒收 G.711 返回 SIP 488）的运营商
- [x] **离线全栈回归、源码一键构建与旧二进制清理**
  - [x] Go 全模块单元测试 + Mock ePDG/IMS/RTP 端到端全链路回环测试 100% 通过
  - [x] 全部现有 `service/tests/`（846 项策略、Clash、SIM、遥测、契约、通话与生命周期测试）100% 通过
  - [x] 从 `service/deps/`、`service/tools/build.py`、`service/tools/verify_artifacts.py` 彻底移除旧预编译 SoftVoWiFi 二进制及补丁流程，改为 Go 源码自动交叉编译与 ELF/DEX 校验
  - [ ] 在 Xiaomi 15（Android 17）及非高通机型上完成海外 eSIM 真实运营商网络注册、双向通话、短信收发与切卡实测调优

---

## P1：常驻 Root Daemon 替代频繁 `su -c app_process`（降功耗 + 提速） ✅

- [x] 设计并实现单实例常驻 Root Daemon（`RootDaemonMain` + `RootDaemonProtocol`，合并 `RootIdentityMain`、`RootClashMain`、`RootNetworkMain` 的进程内 `execute()` 调用）
- [x] 通过 `0660` 权限 + `SO_PEERCRED` 严格校验调用方 UID（仅允许 `uid == 0` 或已验证的 `com.smartvowificall.ims` UID）的 Unix Domain Socket 提供本地并发 RPC，并在 `RootSupervisor` 中优先走常驻通道、异常自动回退与拉起
- [x] 保留 `ImsLock` 事务安全与缓存 `ActivityThread.systemMain()` 上下文，消除频繁 `fork su` / ART 启动开销
- [x] 优化 `service/module/service.sh`：加入顶层单实例互斥锁（`$BASE/run/service.lock`），托管 `RootDaemonMain`，无活跃引擎时将巡检休眠拉长至 `25s` 以显著降低待机唤醒功耗

---

## P2：正式放开并适配 Android 14–17（扩大可用设备面） ✅

- [x] 梳理并适配 Android 14 / 15 / 16 / 17（API 34–37）隐藏 Binder 反射签名差异（`ISub.getActiveSubscriptionInfoList`、`ITelephony.setBoundImsServiceOverride` 5/4 参数、`getBoundImsServicePackage`、`isModemEnabledForSlot` 3/2/1 参数、`setVoiceServiceStateOverride` 3/2 参数、`getCallStateForSubscription`、`IPhoneSubInfo`、`IConnectivityManager.getNetworkCapabilities`、`IPackageManager.getPackageUid`）
- [x] 在 App「网络」页新增「系统接口兼容性自检（Android 14–17）」卡片与 `CompatReport` 评估引擎（检测 API 版本、Root 与常驻守护进程、模块与自研 Go 引擎、IMS 覆盖接口、单卡蜂窝开关、语音状态覆盖与跨基带 SIM AKA 通道）
- [x] 实现未授 Root 时的「只读降级模式」提示卡片与清晰授权引导
- [x] 将 `service/module/customize.sh` 正式门槛放宽至 Android 14–17（API 34–37），对 API 34–36 给出温和兼容提示而非直接阻断

---

## P3：通用 SIM AKA 鉴权通道（打破“仅支持高通骁龙”限制） ✅（全平台策略与测试已完成，待非高通真机验证）

- [x] 新增通过 Android Root 通道（`RootSimAuthMain`：`ITelephony.getIccAuthentication` + `iccTransmitApduLogicalChannel` APDU 兜底 + `61xx GET RESPONSE` 续读 + `ISIM → USIM` 自动回退 + `probe` 能力探测）透传 EAP-AKA / IMS AKA 的通用后端，完整适配 API 34–37 反射签名
- [x] 实现芯片平台感知的 `ChipsetStrategy` 与 Go 引擎 `NewChipsetAutoProvider` 自动回退策略：高通骁龙（检测到 QRTR）优先 QRTR → Binder，天玑（MediaTek）/ Google Tensor / 三星 Exynos 优先 Binder → APDU → QRTR
- [x] 补全 Java 与 Go 跨基带芯片策略与 Binder `probe` 离线单元测试
- [ ] *真机验证*：在天玑 / Tensor / Exynos 实体机上实测单卡关闭蜂窝后的 UICC AKA 可用性

---

## P4：拉平与原生基带的体感差距（通话音频、切网快恢与交互打磨） ✅

- [x] **通话音频质量优化（`PcmAudio.java`）**
  - [x] 在 `AudioRecord` 会话上挂载硬件回声消除（`AcousticEchoCanceler`）、降噪（`NoiseSuppressor`）与自动增益（`AutomaticGainControl`），并在 `close()` 中安全释放
  - [x] 开启 `AudioTrack.PERFORMANCE_MODE_LOW_LATENCY` 低延迟语音播放，记录当前蓝牙 SCO / LE Audio / 听筒 / 扬声器通信设备路由状态
- [x] **切网与切节点秒级重连**
  - [x] 在自研 Go 引擎控制面新增 `reconnect` RPC（`Engine.Reconnect()` + `EngineSession.reconnect()`），立即清理失效 UDP 隧道并零退避重连重注册（已通过 Go 端到端重连回环测试）
  - [x] 在 App「网络 → 系统工具与快捷恢复」提供一键秒级重连入口
- [x] **借鉴 VoWiFiX 的高实用性系统交互**
  - [x] 新增系统控制中心快捷磁贴（`VoWifiTileService`：实时展示「已注册 / 连接中 / 蜂窝模式」，单击启停，长按直达 App）
  - [x] 新增带通话保护与二次确认的「一键恢复原厂蜂窝与 IMS」及系统 `RadioInfo`（`*#*#4636#*#*`）直达入口
  - [ ] *可选后续*：在完成真机全页面 Compose/反射 Keep 规则验证后开启 R8 裁剪与可选「隐藏桌面图标」

---

## P5：基于遥测 v2 沉淀「运营商 & 机型适配库」

利用已上线的脱敏遥测 v2（含 `countryIso`、`carrierName`、`homePlmn`、`socModel`、`osVersion`、`basebandVersion`、网络包体与原始日志）：

- [ ] 统计并整理海外主流运营商的最佳参数（自定义 ePDG 域名、DNS 策略、归属国 IP 严格度、SIP 位置头/鉴权特性）
- [ ] 整理不同 OEM / ROM 的单卡射频与 IMS 覆盖特例
- [ ] 沉淀为轻量内置/可更新的 JSON 运营商与机型规则表，实现“插卡选节点即通”
