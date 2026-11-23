# 版本记录

## v0.4.6-dev — 2026-11-23

应用与模块 versionCode=17，新增可选脱敏遥测日志上传（默认关闭）与手动导出全部日志功能，配套 Cloudflare Worker 中转服务写入私有 GitHub 仓库。

- **脱敏遥测日志（默认关闭）**：新增纯 Java 白名单脱敏器 [LogSanitizer.java](service/app/src/main/java/com/smartvowificall/ims/telemetry/LogSanitizer.java)、内存环形事件缓冲 [EventRingBuffer.java](service/app/src/main/java/com/smartvowificall/ims/telemetry/EventRingBuffer.java) 与 HTTPS 客户端 [TelemetryClient.java](service/app/src/main/java/com/smartvowificall/ims/telemetry/TelemetryClient.java)。仅上传版本、机型、连接状态与错误码，严格剔除 ICCID、IMSI、IMEI、手机号、SIP URI、订阅链接、节点名、IP 与密钥；支持上传前在 App 内完整预览脱敏 JSON。
- **Cloudflare Worker 中转服务**：新增 [telemetry-worker/](telemetry-worker/)，将 GitHub Fine-grained Token 仅保存在 Worker 服务端加密变量，收到 `smartvowificall.telemetry.v1` 后执行二次脱敏并按日期写入私有仓库 `YubaiNya/SmartVoWifiCall-Telemetry`。
- **手动导出全部日志**：在「网络 → 日志与遥测」下方新增「导出脱敏日志」与「导出原始全部日志（带隐私确认）」，通过系统文件保存器（SAF）一键导出包含诊断快照、运行事件、引擎日志与过滤后 `logcat` 的 ZIP 压缩包。

## v0.4.5-dev — 2026-11-23

应用与模块 versionCode=16，优化 Wi-Fi Calling 期间切换 eSIM 配置文件、按 SIM 自动切换内置 Clash 出口节点以及节点切换/刷新即时恢复。

- **按 SIM 记忆与自动切换 Clash 节点**：某张海外卡通过 `wfctun0` 成功注册或用户手动选择节点后，按 `simKey` 记录该卡偏好节点；后续在同一 eSIM 槽位切换不同国家/地区海外卡时自动切到该卡节点并清理旧连接后再起引擎，避免跨区 IP 触发 ePDG 9003 与退避。
- **切节点与刷新立即破除退避**：手动切换 Clash 节点或点击刷新会立即清空 `FallbackPolicy` 退避并关闭旧 UDP/IKE 连接、重建非通话中的 `wfctun0` 会话，不再卡在引擎 `BACKOFF` 或 5–30 分钟注册退避。
- **eSIM 切卡全链路清理与租约迁移**：
  - `FallbackPolicy` 新增 `resetSlot(slot)`，同一卡槽切换 eSIM 配置文件时清零旧卡计时与退避；
  - `RootIdentityMain` 仅在持有 `ImsLock` 的 `radio-hold` 中迁移槽位射频租约（保留原 `restoreOn`），`bindIms` 同步更新 `ims/slot-<slot>.json` 新 `subId`/`simKey`，`releaseRadio` 支持切换至大陆卡及停用 eSIM（`sub < 0`）场景恢复基带，并在 `snapshot` 中上报磁盘 `heldRadioSlots`；
  - 修复 `releaseStaleSlots` 6 秒防抖释放（海外卡互切不抖动基带，切回大陆卡或停用 eSIM 后自动恢复蜂窝与原厂 IMS），并按 `subId` + `simKey` 精确校验 `featureAttached` 与 `binding`。
- **界面版本与状态文案**：网络页版本徽章改为动态读取系统 `versionName`（修复此前硬编码 `v0.4.0-dev`），`BACKOFF`/`RECONNECTING` 显示为中文「重试中」/「正在重连」。
- **验证**：全量本地测试通过（含新增 `FallbackPolicy.resetSlot` 断言共 47 项）及 `verify_artifacts.py` 校验通过。Xiaomi 15 / Android 17 已安装 `0.4.5-dev (versionCode=16)`，实测卡槽 1（3HK `45403` / `subId=4`）达到 `REGISTERED / WLAN / READY`（`FrameworkProbeMain 4` 返回 `2/2/2`）、`voiceServiceOverride=true`、`radioOff=true` 且自动记录偏好节点，卡槽 0 大陆移动保持在网；真机双向通话及切卡实测留待用户操作。

## v0.4.4-dev — 2026-11-23

应用与模块 versionCode=15，包含 0.4.3 节能和简化文案改动，修复远端挂断后下一通失败导致的通话残留及麦克风采集条件。

- 实机 radio 日志确认：远端挂断已收到 `onCallTerminated(510)`；下一通在 `onCallInitiating` 后失败 `131`。Android 17 在 initiating 时清空 `mPendingMO`，之后发送 `callSessionInitiatedFailed` 可被忽略，导致系统永久 DIALING、引擎却 IDLE。现在 initiating/progressing 已上报的会话统一用 `callSessionTerminated` 结束，未开始的失败仍用 StartFailed。
- 原有录音会话被系统标记为 silenced。`AUDIO_HANDLER_ANDROID` 通知由无通话的 Feature 初始化阶段移到实际呼叫回调之后，让 Telecom 正确选择 VoIP 音频模式，不直接修改全局音频模式或蜂窝音频路由。
- 仅音频采集期间持有 microphone 前台服务租约，确认前台就绪再录音；结束/失败/取消释放。通话中显示无声低重要性通知，待机不常驻。不绕过 Android 麦克风限制，也不记录 PCM 内容。
- 音频故障先通知会话，再异步清理，避免关闭媒体资源阻塞终结。增加仅帧数/电平/系统静音状态的诊断。
- 新的远端挂断→再次失败呼出回归在旧实现上复现失败，修复后通过；完整 Feature 73 项、通话/音频 77 项、relay 12 项检查及 Release 构建通过。实机已清除残留并恢复 `REGISTERED / IDLE`；同签名一秒录音探针观察到非零 PCM、policy-silenced=false，未保存音频，测试APK已移除。Instrumentation 可能享有后台豁免，真实双方通话仍需与该结果分开验收。

## v0.4.3-dev — 2026-11-23（节能与文案，待真机复验）

应用与模块 versionCode=14。本版离线完成；手机已由用户拔线，不能沿用 0.4.2 的三次通话结果作为本版验收。

- 开启网络通话时自动启用代理，先准备 Clash 再继续注册；关闭时先等通话结束、清理引擎、恢复系统 IMS 和蜂窝，再停止 Clash。关闭期间保存订阅、重载配置或请求重启不会单独拉起内核。
- 稳定状态每 5 秒只进行轻量引擎检查，完整 root/SIM/接口检查改为 30 秒；网络、SIM 和通话事件仍立即触发合并刷新。彻底关闭后应用停止周期检查。
- 语音覆盖先读取 framework 当前语音状态；已正确生效时不重复启动 root 进程，挂断后被清除或读取失败仍重申覆盖。MMTEL 能力仅变化时通知，不牺牲掉注册和挂断恢复。
- 模块每 30 秒检查应用是否存活，不再每 5 秒重启前台服务；网络快照发布器只在存在引擎进程记录时运行，运行期间维持 5 秒刷新。临时网络快照去掉无必要的磁盘 fsync，保留原子替换和 15 秒过期校验。
- UI 后台停止刷新；连接页仅前台、代理运行、未暂停时每 3 秒刷新且不重叠。代理默认日志降至 warning，未修改运营商/IKE/SIP 保活参数。
- 精简首页、代理、网络与设置文案，常用状态中文化，保留收费提示及必要配置项。
- 本地策略/节能 506 项断言、Feature 生命周期 56 项、安装/模块 23 项测试通过；Release 构建通过。真实耗电改善幅度、开关顺序和连续通话仍待连接手机复验。稳定 0.4.2 包已另行备份。

## v0.4.2-dev — 2026-11-23

应用与模块 versionCode=13；已迁移安装并由用户确认连续三个电话稳定。

- 保留 0.4.1-dev（versionCode=12）的挂断后语音覆盖修复：Telecom 可以清除 `setVoiceServiceStateOverride`，因此真实注册且允许通话时每次策略同步重新应用，不能用上次成功的布尔值跳过。写入失败标记为未知并重试；掉注册、退出和旧绑定仍清除覆盖。通话回到 IDLE 时触发同步。
- 按卡槽保留 `ImsConfigImplBase` 强引用，修复实机出现的 `RemoteException: Fail to get ImsConfigImpl`，避免仅由 framework 弱引用持有的配置实现被回收。
- 增加不含号码的会话生命周期日志，以及 `dumpsys activity service .../.PolicyService` 的会话计数、待发呼叫和引擎阶段；可附加 `threads` 读取应用线程栈。
- 已保留订阅、所选节点、SIM 配置与用户开关迁移至 SmartVoWifiCall；旧安装停用保留用于回滚。用户重启后确认模块服务和网络快照自动启动。
- 本地策略回归 475 项断言、通话/音频 39 项、relay 清理 12 项、Feature 生命周期 51 项通过。真机三次模拟清除语音覆盖均自动恢复；真实呼叫由用户手动完成，代理未拨号或发短信。证据和限制见 [验证记录](service/docs/BUILTIN_CLASH_VALIDATION.md)。

## v0.4.0-dev — 2026-11-22

应用与模块 versionCode 升为 11。采用 **Apple Design** 视觉令牌 + **Miuix (`top.yukonga.miuix.kmp:miuix-*:0.9.4`)** + **Kyant AndroidLiquidGlass (`io.github.kyant0:backdrop-android:2.0.1`)** 重新设计整个多页面 UI，移植 SukiSU-Ultra / Kyant `LiquidBottomTabs` 三层液态玻璃浮动底栏与 `DampedDragAnimation` 物理弹簧阻尼滑动（按压放大 `78/56`、实时 `lens` 折射与色差、交互高光与速度形变）；内置 Clash 新增 **外部控制器（External Controller）** 局域网/本机切换与 **实时连接监控/管理（Live Connections）**；新增 **`FallbackPolicy`** 自动蜂窝回退与有序 IMS 注销机制，并移除状态栏常驻通知。

- **多页面 Apple Design + Miuix + 液态玻璃底栏（[AppleGlassTheme.kt](service/app/src/main/java/com/smartvowificall/ims/AppleGlassTheme.kt)、[LiquidGlassBottomBar.kt](service/app/src/main/java/com/smartvowificall/ims/LiquidGlassBottomBar.kt)、[AppScreens.kt](service/app/src/main/java/com/smartvowificall/ims/AppScreens.kt)、[MainActivity.kt](service/app/src/main/java/com/smartvowificall/ims/MainActivity.kt)）**：
  - 拆分为 **「概览」**（IMS/ePDG 状态看板、总开关、计费开关、双卡槽与出口选择器）、**「代理」**（内置 Mihomo 内核开关、订阅拉取、YAML 导入、本地重载、42 节点延迟测速与卡片切换）、**「连接」**（1.5 秒实时观测 `wfctun0` 活跃连接、累计上下行流量、UDP/TCP 与关键字过滤、单条/全部连接断开、External Controller 本机/局域网模式切换、密钥重置与 MetaCubeXD/Yacd/Zashboard 一键调试地址复制）、**「网络」**（内核全部网络接口与 `IFF_TUN` 资格审查）四个页面。
  - 底栏严格对齐 Kyant / SukiSU-Ultra 的 `LiquidBottomTabs` + `DampedDragAnimation` + `InteractiveHighlight` 三层结构：可见底栏折射页面背景、隐藏着色捕获层（按压放大 `1.2x`）提供蓝色折射源、滑块胶囊按压放大 `78/56` 并动态淡入 `lens` 折射、色散、高光、内外阴影及速度拉伸形变。
- **内置 Clash 外部控制与实时连接调试（[ClashConfig.java](service/app/src/main/java/com/smartvowificall/ims/clash/ClashConfig.java)、[RootClashMain.java](service/app/src/main/java/com/smartvowificall/ims/root/RootClashMain.java)）**：
  - 支持 `externalControllerLan`（`127.0.0.1:<port>` 与 `0.0.0.0:<port>` + `external-controller-cors`）动态切换、端口配置与密钥轮换，持久化于 `/data/adb/smartvowificall_ims/clash/controller.json`，同时保持所有代理端口为 `0`、`allow-lan: false`、`auto-route: false` 以及强制 `secret`；`dns` 新增 `ipv6: false` 避免冷启动向代理上游重复发起 `AAAA` 查询。
  - 新增 `connections`、`close-connection`、`close-all-connections` 命令，直接对接 Mihomo `/connections` REST API。
- **接口失效/非 TUN 自动蜂窝回退与状态栏 VoWiFi 图标即时清理（[FallbackPolicy.java](service/app/src/main/java/com/smartvowificall/ims/policy/FallbackPolicy.java)、[RuntimeController.java](service/app/src/main/java/com/smartvowificall/ims/RuntimeController.java)、[SmartVoWifiImsService.java](service/app/src/main/java/com/smartvowificall/ims/SmartVoWifiImsService.java)）**：
  - 新增纯策略 `FallbackPolicy`（44 项单测断言）：当所选接口不是 TUN（如切换到 `dummy0`）、未选接口、所选 TUN 超过宽限期不可用或 IMS 注册超过 120 秒未就绪时，自动释放目标卡蜂窝射频（`releaseRadios`）并恢复系统 IMS 服务（指数退避 5→10→20→30 分钟自动重试，用户切换接口或开关时立即重置）。
  - 统一每卡槽唯一的 `ImsRegistrationImplBase` 实例，并在解绑系统 IMS 服务前严格先执行 `setVoiceServiceOverride(false)`、`MmTelFeature.registrationChanged(false)`（清空 `VOICE/SMS` 能力）与 `onDeregistered`，彻底消除切换到非 TUN 接口后状态栏仍残留 VoWiFi 图标、电话无法回退蜂窝呼出的问题。
- **移除状态栏常驻通知（[PolicyService.java](service/app/src/main/java/com/smartvowificall/ims/PolicyService.java)）**：
  - `PolicyService` 启动满足前台服务契约后立即调用 `stopForeground(STOP_FOREGROUND_REMOVE)` 并取消通知（`IMPORTANCE_MIN` 无角标通道），状态栏与通知栏零常驻通知（`dumpsys notification` 验证为 `0`）。

0.3.2-dev 交付物已备份至 `C:\ProjBackup\SmartVoWifiCall\20261122-223544-before-liquid-glass-ui-0.4.0\`。

## v0.3.2-dev — 2026-11-22

应用与模块 versionCode 升为 10。修复海外卡关闭蜂窝协议栈（`enableModemForSlot(slot, false)`）且 VoWiFi 已注册（`REGISTERED / WLAN`）时，拨号仍被 Android `TelephonyConnectionService` 拦截并提示“移动网络不可用。需连接至无线网络才能拨打电话”的问题，同时修复小米 `ImsServiceController` 在应用强停/升级后不重连 `NewMmTelFeature` 的问题。

- **拨号拦截根因**：反汇编真机 `TeleService.apk` 与 `telephony-common.jar` 确认，`GsmCdmaPhone.mergeVoiceServiceStates` 在蜂窝 `mSST.mSS` 处于 `OUT_OF_SERVICE` 且 `dataRegState` 亦为 `OUT_OF_SERVICE`（关闭单卡蜂窝且未走原厂 `vendor.qti.iwlan` PS PDN）时，即使 `ImsPhone` 已在 `IWLAN` 上注册且 `isWifiCallingEnabled=true`，仍返回 `STATE_OUT_OF_SERVICE`，导致 `TelephonyConnectionService.getTelephonyConnection` 在进入 `GsmCdmaPhone.dial()` 之前直接以 `DisconnectCause.OUT_OF_SERVICE` 拒呼。
- **修复 1（`ITelephony.setVoiceServiceStateOverride`）**：在 [RootIdentityMain.java](service/app/src/main/java/com/smartvowificall/ims/root/RootIdentityMain.java)、[RootSupervisor.java](service/app/src/main/java/com/smartvowificall/ims/RootSupervisor.java) 与 [RuntimeController.java](service/app/src/main/java/com/smartvowificall/ims/RuntimeController.java) 中，仅当该卡满足单卡蜂窝已关闭、独立 IMS 已绑定、`ims == REGISTERED` 且允许计费动作时，针对该 `subId` 调用 `ITelephony.setVoiceServiceStateOverride(subId, true, "root")`；一旦掉注册、清理会话或恢复/禁用/卸载模块时立即清除为 `false`。
- **修复 2（小米 `ImsServiceController` 重连）**：在 [RuntimeController.java](service/app/src/main/java/com/smartvowificall/ims/RuntimeController.java) 检测到 `alreadyBound && !features.containsKey(slot)`（强停/升级后小米 `xiaomi_issue` 补丁跳过相同 feature 列表）时，先执行 `restore` 再 `bind`，触发 `ImsResolver` 立即重新挂载 `NewMmTelFeature`。
- **真机实测（Xiaomi 15 / Android 17，无拨号、无短信）**：`ksud module install` 升级并 `am force-stop` 重启 `PolicyService` 后，`ImsResolver` 在 12 秒内重新挂载 `NewMmTelFeature`（`featureAttached=true`、`FrameworkProbeMain 4` 返回 `registrationState=2, transportType=2, featureState=2`）；`PhoneInterfaceManager` 应用 `setVoiceServiceStateOverride(subId=4, hasService=true)`，`dumpsys telephony.registry` 确认卡槽 1 `mVoiceRegState=0(IN_SERVICE)`、`mDataRegState=1(OUT_OF_SERVICE)`（蜂窝仍关闭）、`Phone-1: isImsRegistered=true / isWifiCallingEnabled=true`，卡槽 0 中国移动 LTE 全程在网未受影响。实际呼出通话留待用户手动测试。

0.3.1-dev 交付物已备份至 `C:\ProjBackup\SmartVoWifiCall\20261122-220556-before-voice-override-0.3.2\`。

## v0.3.1-dev — 2026-11-22

应用与模块 versionCode 升为 9。修复 `0.3.0-dev` 中真实订阅导入后内置 Clash `wfctun0` 上 `vowifi-engine-arm64` 报 `selected network unavailable` 无法拉起隧道的问题，将内置 Clash 隔离机制升级为**独立 Linux Network Namespace**，并在 Xiaomi 15 / Android 17 上通过真实海外订阅完成端到端 `REGISTERED / WLAN / READY` 验证（零 Android VPN、零根命名空间路由改动、零手机其他流量代理）。

- **根因**：反汇编确认引擎 `directTUNBinding` 的 `requireInterfaceRoute` 会执行不带 `oif`/`src`/`fwmark` 的 `netlink.RouteGet(dst)` 并要求命中 `wfctun0`，而根命名空间的 `oif`/`from` 策略路由无法匹配普通 `RouteGet`。
- **修复**：`mihomo-arm64` 留在根网络命名空间（出站走系统默认路由），`clash-control.sh` 通过零 CPU 锚点进程（`unshare -n -- sleep 2147483647`，记录于 `netns.owner`，借 `/proc/<pid>/ns/net` 在所有 Android/KernelSU 挂载命名空间统一可见）创建独立网络命名空间，仅将 `wfctun0` 迁入并设置 `default dev wfctun0`；`control.sh` 仅在配置为 `iface:wfctun0` 时以 `nsenter + unshare -m` 私有重挂 `/sys` 启动引擎，并改用 `/proc/$pid/net/unix` 校验 socket 所有权；`InterfaceParser`、`RootIdentityMain` 与 `RuntimeController` 同步支持命名空间内 `wfctun0` 校验与 `netnsId` 变更自动重建。
- **验证**：本地全量回归与产物校验全部通过（含新增锚点 PID netns 隔离单测）。Xiaomi 15 实机真实海外订阅实测通过：`tunnel=CONNECTED`、`ims=REGISTERED`（SIP 200 + SUBSCRIBE 200 active）、`FrameworkProbeMain 4` 返回 `registrationState=2, transportType=2, featureState=2`；`RootNetworkMain snapshot` 零 VPN；根命名空间无 `wfctun0`、零 Clash `ip rule`、`ip route get 1.1.1.1` 直走 `wlan0`；卡槽 0 大陆中国移动 LTE 全程在网未受影响；实测 `clash-control.sh stop` 清理后 `PolicyService` 自动自愈重建命名空间并恢复注册。未拨号或发短信。详见 [内置 Clash 验证记录](service/docs/BUILTIN_CLASH_VALIDATION.md)。

0.3.0-dev 交付物已备份至 `C:\ProjBackup\SmartVoWifiCall\20261122-213337-before-clash-netns-0.3.1\`。

## v0.3.0-dev — 2026-11-22

应用与模块 versionCode 升为 8。模块内置官方 `MetaCubeX/mihomo v1.19.34`（arm64）内核，通过独立 Linux TUN 接口 **`wfctun0`** 仅全局代理 Wi-Fi Calling 流量，不代理手机其他任何流量，也不开启 Android VPN。

- 内置 `bin/mihomo-arm64`（来源与 SHA-256 见 `service/deps/mihomo.source.json`，GPL-3.0 许可证位于 `module/licenses/MIHOMO-GPL-3.0.txt`）。
- `ClashConfig` 自动剥离用户订阅中的 `auto-route`、`auto-redirect`、本地代理端口、原 `dns`/`rules`/`proxy-groups`，强制生成 `device: wfctun0`（`198.19.0.1/30`、`auto-route: false`）、仅包含真实代理节点的 `WFC-PROXY` 与单一规则 `MATCH,WFC-PROXY`（永不误走 `DIRECT`），ePDG DNS（`any:53` 劫持）经 `WFC-PROXY` 代理，mihomo 上游出站走系统默认路由。
- `clash-control.sh` 仅写入针对 `oif wfctun0`（优先级 1050）与 `from 198.19.0.1/32`（优先级 1051）的独立路由表 `209`，不改主路由表、不写 iptables；停止/禁用/卸载模块时完整清理进程与两条规则。
- `MainActivity` 新增内置 Clash 管理：启用开关、订阅链接拉取、YAML 文件导入、本地 `/data/adb/smartvowificall_ims/clash/profile.yaml` 重载、`WFC-PROXY` 节点选择与测速、一键将海外卡切到 `wfctun0`，并保留原有接口下拉框兼容外部 TUN（如 FlClash `tun0`）。
- 完整保留 0.2.5 的单卡蜂窝协议栈互斥（`enableModemForSlot`）与禁止普通呼叫 CSFB 回退。

本地全量回归与产物校验全部通过（策略/网络 92、注册路由 27、单卡互斥 28、Clash 配置净化 34、SIM 凭据 181、网络快照 27、身份/路径 23、引擎/通话/Feature 195、模块脚本与单包安装 26、原生选择器 290、日志池 5、短信 187）。Xiaomi 15 / Android 17 真机已完成 `ksud` 刷入自动覆盖升级、`mihomo-arm64 v1.19.34` 启动、`wfctun0`（`0x1001` / `198.19.0.1/30`）就绪、仅新增 `1050`/`1051` 两条策略路由（表 `209`）、零 Android VPN、仅回环端口监听（`127.0.0.1:1053` 与 `19090`）、`WFC-PROXY` 节点切换、`vowifi-engine-arm64 config-verify --strict` 校验 `iface:wfctun0` + `dns: 1.1.1.1` 通过、停用完整清理与 App 新 UI 渲染验证（实测期间补充 `dns.fake-ip-range: 198.19.0.1/16` 锚定 `wfctun0` 网段）。尚待填入真实海外订阅完成 `wfctun0` 端到端 SIP 注册与冷重启验证；未拨号或发短信。详见 [内置 Clash 验证记录](service/docs/BUILTIN_CLASH_VALIDATION.md)。

0.2.5 交付物已备份至 `C:\ProjBackup\SmartVoWifiCall\20261122-204200-before-builtin-clash-0.3.0\` 并核验 SHA-256。

## v0.2.5-dev — 2026-11-22

应用与模块 versionCode 升为 7。按用户要求实现海外卡 Wi-Fi Calling 与蜂窝二选一互斥，并禁止普通呼叫在 Wi-Fi Calling 不可用时自动回退蜂窝。

- 真机实测确认 Qualcomm DSDS 上 `setRadioForSubscriber(subId, false)` 会连带关闭大陆卡，因此改用单卡协议栈接口 `ITelephony.enableModemForSlot(phoneId, false/true)` 与 `isModemEnabledForSlot`：仅关闭海外卡蜂窝协议栈（`NOT_REG_MT_NOT_SEARCHING_OP`），保留大陆卡 LTE 在网以及海外卡 UICC/QRTR EAP-AKA 鉴权。
- 持久化单卡租约 `/data/adb/smartvowificall_ims/radio/<slot>.json`；必须读回目标卡协议栈已关闭后，才绑定独立 IMS、准备配置、启动引擎、上报 `REGISTERED` 或放行普通呼叫。
- `NewMmTelFeature.shouldProcessCall` 对普通号码及异常分类始终返回 `PROCESS_CALL_IMS`（仅明确紧急号码允许 `PROCESS_CALL_CSFB`）；`NewCallSession.start` 拨号前实时复核目标卡蜂窝已关闭且已注册，否则直接报错失败。
- `RegistrationRouting` 掉注册/断 TUN 时保持独立 IMS 接管不回退蜂窝；仅在关闭总开关、禁用或卸载模块时按序恢复系统 IMS、停引擎并释放单卡蜂窝租约（飞行模式期间保留租约待退出后恢复）。
- 顺带修复真机发现的四处问题：`app-install.sh` 去除 Android 17 `pm install -S` 不识别的多余 `-`；`RuntimeController` 在升级/冷启后若 `ImsResolver` 尚未挂载 `NewMmTelFeature` 自动重新触发绑定（15 秒冷却）；`control.sh` 用 `dd bs=4096 count=1 | tr` 避免 toybox `tr` 在进程退出时 98% CPU 死循环卡死锁；`uninstall.sh` 为 `radio-release-all` 增加重试循环；飞行模式查询改用 `/system/bin/settings` 避开 `app_process` ContentResolver 拒绝。

本地全量回归通过：策略/网络 89、注册/provider 27、单卡互斥 28、SIM 凭据 181、网络快照 27、身份/路径 23、引擎 93、通话/中继 51、Feature 生命周期 51、模块控制 4 组、单包安装/回滚 21 个场景、原生选择器 290、日志池 5、短信 PDU/适配 187 项及产物校验全部通过。Xiaomi 15 / Android 17 真机已完成全链路实测：`ksud module install` 自动覆盖升级 APK 并保留配置；App 界面总开关关闭/开启完整验证恢复蜂窝与重新接管；卡槽 1 在 `modemEnabled=false` 下冷启引擎成功完成 QRTR EAP-AKA 与 SIP REGISTER，`FrameworkProbeMain 4` 返回 `registrationState=2, transportType=2, featureState=2`（`REGISTERED / WLAN / READY`），卡槽 0 中国移动 LTE 全程在网未受影响；整机冷重启后 KernelSU 顺利切换至 `0.2.5-dev`、`app-install.status=APP_CURRENT`，重启无 `tun0` 时自动保持卡槽 1 `modemEnabled=false` 与独立 IMS 接管不回退蜂窝（`registrationState=0, featureState=2`），卡槽 0 正常驻网 LTE。未实际拨号或发短信。详见 [单卡互斥验证](service/docs/EXCLUSIVE_RADIO_VALIDATION.md)。

0.2.4 交付物已备份至 `C:\ProjBackup\SmartVoWifiCall\20261122-195200-before-exclusive-radio-0.2.5\` 并核验 SHA-256。

## v0.2.4-dev — 2026-11-22

应用与模块 versionCode 升为 6。用户明确允许普通蜂窝通话回退，独立 VoWiFi 仍只使用所选 TUN。

- 根据内核 `tun_flags` 判断真实 IFF_TUN，拒绝物理蜂窝/Wi-Fi、TAP 和仅名称像 TUN 的接口；不要求物理 Wi-Fi 在线。
- 全部接口仍列出，非 TUN 标注为不用于 VoWiFi；保留已有选择，不静默改到其他网卡。首次默认 FlClash 接口也必须同时确认 TUN 类型。
- 配置准备、写入和引擎启动前重新检查；引擎只接收显式 `iface:<所选 TUN>`，不接受自动/蜂窝/系统默认路由模式。
- 保留 0.2.3 的真实注册后接管、空闲后恢复原厂 IMS、注册恢复后重新接管，以及单 ZIP 自动 APK 更新。

本地策略/网络 89、provider 回退 27、凭据 181、网络快照 27、身份/路径 23 项检查通过；模块真实启动分支的 TUN 拒绝场景、安装流程 20 个场景和原生选择器 290 项重放通过。构建和交付物校验见 [TUN 策略验证](service/docs/TUN_POLICY_VALIDATION.md)。手机离线，未安装或实测新包；不将这些检查当作 TUN 断开时无流量外泄、原厂 WFC 或 FlClash 物理上游限制的证明。未实际拨号或发送短信。

0.2.3 交付物已备份至 `C:\ProjBackup\SmartVoWifiCall\20261122-151303-before-tun-egress-0.2.4\` 并逐项核验 SHA-256。

## v0.2.3-dev — 2026-11-22

应用与模块 versionCode 升为 5。针对断开 Wi-Fi 后仍提示需要 Wi-Fi 的反馈，确认并修正“接口 UP 即接管、注册失败仍占用 MMTEL”的源码缺口；未获得该手机当时日志，不能确定提示只由此引起。

- 启动独立引擎作为候选，注册成功并确认无框架通话后才切换系统 provider。
- 独立注册丢失或控制连接断开时，空闲后恢复原厂 provider；VPN 接口仍 UP 不再阻止回退。
- 引擎可继续重试，恢复注册后再接管；保留接口选择与生命周期记录。
- 活跃通话期间延后切换；候选引擎的来电不阻止向空闲 Android 框架接入自身。
- 界面区分当前使用系统 IMS 还是独立 IMS，并暴露 provider/延后状态诊断。

新增 27 项 provider 切换检查通过；策略、凭据、网络、通话、协议和单包安装相关回归通过。手机离线，未实测蜂窝回退或实际拨号/短信。蜂窝通话仍要求关闭飞行模式及具备该卡的蜂窝服务。详见 [回退验证](service/docs/IMS_FALLBACK_VALIDATION.md)。

原 0.2.2 包已备份至 `C:\ProjBackup\SmartVoWifiCall\20261122-143603-before-ims-fallback-0.2.3\` 并核验 SHA-256。

## v0.2.2-dev — 2026-11-22

应用与模块 versionCode 升为 4。仅需刷模块 ZIP 并重启，APK 首次安装和覆盖升级由模块自动处理。

- 安装时使用 PackageManager 流式覆盖安装；开机核对安装 APK 与模块内 APK 的 SHA-256，相同则跳过，避免反复重装。
- 同包名、同签名更新，不卸载、不清数据、不重置开关、接口选择或 SIM 配置；实测文件校验确认签名与 0.1.0 相同。
- 活跃/未知通话或临时安装服务故障自动延期，开机重试；签名不符/降级被拒绝时保留旧应用并报告失败。
- 模块的网络发布和前台服务启动在 APK 校验成功后进行；状态写入 `/data/adb/smartvowificall_ims/app-install.status`。
- 新增安装决策模式仅返回通话是否空闲，不将 SIM 身份写入安装输出。

20 个真实 shell 安装/开机流程的替身测试通过，覆盖首次、0.1 式旧版覆盖、同包跳过、配置保留、失败读回、通话延期、Recovery、签名失败及开机重试；另有 288 项策略/凭据/路径检查和模块既有场景通过。本轮设备离线，未执行真实安装或重启；没有实际拨号或发送短信。详见 [自动安装验证](service/docs/AUTO_APK_VALIDATION.md)。

此前 0.2.1 交付物已备份并核验至 `C:\ProjBackup\SmartVoWifiCall\20261122-140856-before-auto-apk-0.2.2\`。

## v0.2.1-dev — 2026-11-22

应用与模块 versionCode 升为 3。用户反馈短信正常，但接听并结束一次电话后两张卡均无法呼出，来电显示未知号码；这是用户实测报告，本轮没有取得对应手机日志。

- 精确消费有版本的 `incomingCall.state=ENDED` 和 `callEnded`，后续快照报错也能通知 Android 结束匹配会话；拒绝旧 epoch、旧 revision 与其他 callId 的事件。
- 将音频资源关闭移出共享通话线程；audioStop 不再排在可能阻塞的本地关闭之后，仍保持 audioStart 先于 audioStop。
- root 中继先取消子进程再关闭缓冲管道，stdin EOF 时关闭整个 socket，避免半关闭残留。
- 新权威快照可清理已不属于当前通话的旧会话。
- 公开号码设置 OI 和明确的 OIR；缺失、匿名号码维持未知。使用 Android 17 返回 listener 的来电通知接口，显式接回框架回调。

新增 38 项真实 feature/session/audio 代码集成替身检查和 12 项 root 中继关闭检查通过。旧标签 `v0.2.0-dev` 在号码展示、终止事件、阻塞音频关闭、旧会话清理四组场景中均复现失败，修正后通过；本轮共执行 522 项 Java/协议模拟检查。手机 ADB 及无线 ADB 均不可用，新包未安装、未实际拨号或发送短信，不能声称手机症状已消除。详见 [修复验证](service/docs/CALL_FIX_VALIDATION.md)。

此前 0.2 安装包已备份并核验至 `C:\ProjBackup\SmartVoWifiCall\20261122-134529-before-call-cleanup-0.2.1\`。

## v0.2.0-dev — 2026-11-22

独立模块版本，应用与模块 versionCode 升为 2。

- 内置 SIM 凭据读取，直接调用 Android 电话服务，去掉旧 APK 查找和 provisioning helper 调用。
- 内置实时网络快照发布，使用真实网络 handle、IP、DNS、开机标识和单调时钟，模块每 5 秒刷新。
- 引擎四个默认文件路径迁往 `/data/adb/nvwims`，相对原件精确修改 16 个数据字节；不修改协议或鉴权代码。
- 安装器不要求存在 VoWiFi Engine 包；新 APK 不再声明或加载旧包，模块自带所需引擎。
- 本地增加独立凭据、网络快照和无旧应用安装场景测试，交付物检查包含旧依赖引用排除。

本轮手机离线，0.2 新安装包尚未进行独立注册或重启恢复实测；不能沿用 0.1 的 SIP 成功结果作为新版本验收。通话、短信发送仍默认关闭，未实际拨号或发送短信。详情见 [独立版验证](service/docs/STANDALONE_VALIDATION.md)。

0.1 已验证安装包在重新构建前保留至 `C:\ProjBackup\SmartVoWifiCall\20261122-083948-before-standalone-0.2\` 并逐一核验 SHA-256。当前交付物哈希见 `service/dist/SHA256.json`。

## v0.1.0-dev — 2026-11-22

首次 Git 基线，应用与模块版本均为 `0.1.0-dev`，versionCode 为 `1`。

### 实现

- 独立 Android ImsService，海外归属卡自动接管，大陆卡保持原厂 IMS。
- 按 SIM 保存接口选择，核实 FlClash VPN 所有者，接口失效时恢复目标卡原厂服务。
- 独立引擎生命周期、配置、日志与 root socket 中继。
- MMTEL 就绪通知、注册回调、普通语音/PCM 与文字短信适配；收费业务默认关闭。
- 复用 VoWiFi Engine 0.3.48 arm64 引擎，仅在独立副本中修改 4 个日志池路径数据字节。

### 验证基线

Xiaomi 15 / Android 17 实测 CTExcel SIP REGISTER 返回 200，系统 API 确认 REGISTERED / WLAN / READY。接口失效回退、关闭服务恢复原厂、重新连接注册已通过；大陆卡绑定未改变。

399 项 Java/协议检查通过；另有 290 项原始指令重放、4 组日志池补丁检查、6 例 PANI 分支重放和 2 组模块场景。详细边界见 [验证记录](service/docs/VALIDATION.md)。

本版本未实际拨号或发送短信。基线形成时尚未执行手机重启，开机恢复不属于此标签已通过的测试。用户后续已批准重启测试，应将实测结果与必要修复记入后续提交。

PANI 分析指向独立路径省略该头；没有证明最终 SIP 显式写入 GB。完整 IKE/IPsec/SIP 引擎源码并未提供。

### 已实测交付物

以下为 Git 初始化前已构建、交付并核对的文件；本次仅增加版本管理信息，没有重新构建或覆盖这些安装包。

| 文件 | SHA-256 |
|---|---|
| SmartVoWifiCall.apk | `b06064ffdb9e7f66a1fb28bad9260eb45fbb75032bc081355d10265c310ab57b` |
| SmartVoWifiCall-module.zip | `45cf11c11cb3e7f038a9dbfc888c748c059310571eb539dbf282e1380bd22092` |
| SmartVoWifiCall-source.zip | `b5ec6c14bd0bf5772d80a8c2262261da7a16e25487a57e52e15f3681595cfc24` |

已有源码 ZIP 不包含本次新增的 Git 元数据和根目录说明；当前完整受版本控制的内容以 Git 提交为准。
