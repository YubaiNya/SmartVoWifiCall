# SmartVoWifiCall TODO & 演进路线图

> 坚持现有技术路线：**独立外挂 `ImsService` + 隔离网络命名空间内置 `mihomo` + 免 LSPosed + 零基带 EFS 修改**。

---

## P0（首要任务）：移除 SoftVoWiFi 预编译二进制依赖，实现全栈源码自主可控

当前唯一非自研源码组件是 `service/deps/vowifi-engine-arm64-isolated`（基于预编译二进制做 16 字节路径补丁）。目标是用仓库内完全自主可控的开源协议引擎替代它，摆脱黑盒二进制与路径补丁依赖，并为后续全芯片适配与 IKEv2/SIP 深度调优铺平道路。

- [ ] **锁定与梳理引擎契约接口（不破坏现有 Android 上层）**
  - [ ] 完整文档化控制面协议 v1（Unix Socket 换行 UTF-8 JSON：`hello`、`getStatus`、`getCallSnapshot`、`dial`、`answer`、`hangup`、`hold`、`resume`、`sendDtmf`、`sendSms` 及异步事件帧）
  - [ ] 完整文档化音频面协议（`*.audio.sock` 16kHz Mono PCM 帧封装格式与 `PcmFrameCodec` 对齐）
  - [ ] 完整文档化配置校验 CLI（`config-verify --strict`）与隔离目录结构（原生使用 `/data/adb/smartvowificall_ims`，彻底告别 `.rodata` 二进制补丁）
- [ ] **实现自主可控的 VoWiFi 协议引擎（Go / Rust，静态编译 `arm64-v8a`）**
  - [ ] **隔离出口与解析模块**：原生支持绑定 `wfctun0` / 指定网卡与隔离 netns，内置直连 UDP DNS 解析与 ePDG FQDN 推导（`epdg.epc.mncXXX.mccYYY.pub.3gppnetwork.org`）
  - [ ] **SIM/ISIM 鉴权抽象层（`AKAProvider`）**：先打通高通 QRTR / QMI UIM（与现网 Xiaomi 15 100% 对等验证），并预留通用外部 AKA 后端接口给 P3
  - [ ] **IKEv2 + EAP-AKA / EAP-AKA' + 用户态 IPsec ESP 隧道**：支持 NAT-T（UDP 4500）、DPD 心跳、IKE/Child SA Rekey、双栈内网地址分配与 P-CSCF 地址下发
  - [ ] **IMS SIP 协议栈**：完成 `REGISTER`（含 IPSec/AKA 鉴权）、`INVITE` / `PRACK` / `UPDATE` / `BYE` / `CANCEL`、`MESSAGE`（3GPP SMS-over-IP RP-DATA/RP-ACK）及会话保活
  - [ ] **媒体面 RTP/RTCP 与语音编解码**：支持 AMR-WB / AMR-NB / G.711（PCMU/PCMA）、DTMF（RFC 4733）与 `PcmFrameCodec` 双向桥接
- [ ] **回归测试与真机替换验证**
  - [ ] 跑通全部现有 `service/tests/engine/` 契约、通话与生命周期测试
  - [ ] 在 Xiaomi 15（Android 17）上完成 3HK / 海外 eSIM 真实注册、双向通话、短信收发与切卡验证
  - [ ] 从 `service/deps/` 与 `service/tools/build.py` 移除旧预编译二进制及二进制补丁流程，改为源码一键构建

---

## P1：常驻 Root Daemon 替代频繁 `su -c app_process`（降功耗 + 提速）

当前每次 `snapshot()`、`clashStatus()`、`vpnOwners()` 都会重新 `fork` 一次 `su` 和 ART 虚拟机，存在几百毫秒延迟与额外唤醒。

- [ ] 设计并实现单实例常驻 Root Daemon（合并 `RootIdentityMain`、`RootClashMain`、`RootNetworkMain`、`RootTelemetryMain`）
- [ ] 通过 `0600` 权限 + `SO_PEERCRED` 严格校验调用方 UID 的 Unix Domain Socket 提供本地 RPC
- [ ] 保留 `ImsLock` 事务安全与断线自动拉起机制，把单次状态查询延迟压到 `< 5 ms`
- [ ] 优化 `service/module/service.sh`：加入顶层 `flock` 单实例锁，无活跃引擎时拉长待机休眠间隔以降低唤醒功耗

---

## P2：正式放开并适配 Android 14–17（扩大可用设备面）

当前 APK `minSdk=33`，但 `service/module/customize.sh` 硬卡 `API >= 37`。

- [ ] 梳理并适配 Android 14 / 15 / 16 / 17（API 34–37）隐藏 Binder 反射签名差异（`ISub`、`ITelephony`、`IPhoneSubInfo`、`setBoundImsServiceOverride`、`enableModemForSlot`）
- [ ] 在 App 内新增「系统接口兼容性自检」面板（检测 Root 授权、模块同步、高通 QRTR/基带能力、单卡射频与 IMS 覆盖接口可用性）
- [ ] 实现未授 Root 时的只读降级模式与清晰授权引导
- [ ] 将 `customize.sh` 正式门槛放宽至 Android 14–17（对未充分验证机型/版本给出温和提示而非直接阻断）

---

## P3：通用 SIM AKA 鉴权通道（打破“仅支持高通骁龙”限制）

基于 P0 自研引擎的 `AKAProvider` 抽象层，扩展非高通基带支持。

- [ ] 新增通过 Android Root 通道（`ITelephony.getIccAuthentication` / OMAPI）透传 EAP-AKA 与 IMS AKA 的通用后端
- [ ] 实现自动回退策略：高通骁龙优先走 QRTR / QMI UIM，天玑（MediaTek）/ Google Tensor / 三星 Exynos 自动切换通用后端
- [ ] 验证各平台在单卡关闭蜂窝后是否保持 UICC AKA 可用，并补充按芯片平台的单卡互斥策略

---

## P4：拉平与原生基带的体感差距（通话音频、切网快恢与交互打磨）

- [ ] **通话音频质量优化（`PcmAudio.java`）**
  - [ ] 挂载硬件回声消除（`AcousticEchoCanceler`）、降噪（`NoiseSuppressor`）与自动增益（`AutomaticGainControl`）
  - [ ] 优化蓝牙 SCO / LE Audio / 听筒 / 扬声器切换时的音频路由与抖动缓冲
  - [ ] 强化来电唤醒与弱网丢包恢复可靠性
- [ ] **切网与切节点秒级重连**
  - [ ] 基于 P0 自研引擎调优 IKEv2 DPD 与 SIP 心跳策略
  - [ ] 监听主网 Wi-Fi ↔ 5G / 2.4G ↔ 5G 切换，主动清理失效 UDP 隧道并秒级重注册
- [ ] **借鉴 VoWiFiX 的高实用性系统交互**
  - [ ] 新增系统控制中心快捷磁贴（`TileService`：实时展示注册状态，单击启停/重试，长按进 App）
  - [ ] 在 App 内新增「一键彻底恢复原厂蜂窝与 IMS」（带通话保护与二次确认）与系统 `RadioInfo` 直达入口
  - [ ] 开启 R8 代码/资源裁剪压缩 APK 与模块体积，并配合快捷磁贴提供可选「隐藏桌面图标」

---

## P5：基于遥测 v2 沉淀「运营商 & 机型适配库」

利用已上线的脱敏遥测 v2（含 `countryIso`、`carrierName`、`homePlmn`、`socModel`、`osVersion`、`basebandVersion`、网络包体与原始日志）：

- [ ] 统计并整理海外主流运营商的最佳参数（自定义 ePDG 域名、DNS 策略、归属国 IP 严格度、SIP 位置头/鉴权特性）
- [ ] 整理不同 OEM / ROM 的单卡射频与 IMS 覆盖特例
- [ ] 沉淀为轻量内置/可更新的 JSON 运营商与机型规则表，实现“插卡选节点即通”
