# 0.3.0-dev 内置 Clash（mihomo）仅代理 Wi-Fi Calling（`wfctun0`，不开启系统 VPN）

日期：2026-11-22（北京时间），versionCode=8。

## 已确认的需求

用户要求在模块内直接内置 Clash 内核，**仅全局代理 Wi-Fi Calling 相关流量**，不代理手机其他任何流量，也不在手机开启系统 VPN：

- 专用 TUN 接口名为 **`wfctun0`**，保留接口下拉框兼容其他真实 TUN（如 FlClash `tun0`）。
- App 内支持输入订阅链接拉取、导入 YAML 文件及切换/测速出口节点，同时保留 `/data/adb/smartvowificall_ims/clash/` 目录（编辑 `profile.yaml` 后可一键重载）。
- 内置 Clash 连接上游代理服务器时直接走手机系统默认路由。
- 保留 0.2.5 的单卡蜂窝协议栈互斥（`enableModemForSlot`）与禁止普通呼叫 CSFB 回退。

## 核心实现

1. **内置官方 `MetaCubeX/mihomo` 内核**
   - 打包官方 `MetaCubeX/mihomo v1.19.34`（`mihomo-android-arm64-v8-v1.19.34.gz`）至模块 `bin/mihomo-arm64`（`0755`），来源与 SHA-256 固化于 `service/deps/mihomo.source.json`，GPL-3.0 许可证保留在 `module/licenses/MIHOMO-GPL-3.0.txt`。

2. **强制隔离配置生成（`ClashConfig` + `RootClashMain`）**
   - 用户配置写入 `/data/adb/smartvowificall_ims/clash/profile.yaml`（`0600`）；每次启动或重载由纯 Java 净化器 [ClashConfig.java](../app/src/main/java/com/smartvowificall/ims/clash/ClashConfig.java) 生成 `runtime.yaml`，不修改用户原文件。
   - 自动剥离原订阅中可能影响系统或其他应用的顶层字段（`port` / `mixed-port` / `socks-port` / `redir-port` / `tproxy-port` / `allow-lan` / `tun` / `dns` / `rules` / `proxy-groups` / `external-controller` / `interface-name` / `routing-mark` 等），强制写入：
     - `port: 0`、`mixed-port: 0`、`socks-port: 0`、`redir-port: 0`、`tproxy-port: 0`、`allow-lan: false`（不开放任何代理端口）。
     - `external-controller: 127.0.0.1:19090` + 随机 48 位十六进制 `secret`（`/data/adb/smartvowificall_ims/clash/controller.secret`，`0600`）。
     - `tun`: `device: wfctun0`、`inet4-address: [198.19.0.1/30]`、`stack: gvisor`、`auto-route: false`、`auto-redirect: false`、`auto-detect-interface: false`、`dns-hijack: [any:53]`（不创建系统 VPN、不改主路由表、不写 iptables）。
     - `proxy-groups`: 仅生成 `WFC-PROXY`（`type: select`），汇总用户所有真实节点与 `proxy-providers`，严格剔除 `DIRECT` / `REJECT` / `GLOBAL` 等保留目标，防止误走直连；若配置中无任何有效代理节点/provider，直接以 `CLASH_NO_PROXIES` 拒绝启动。
     - `rules`: 仅 `MATCH,WFC-PROXY`（进入 `wfctun0` 的全部流量全局走所选代理节点）。
     - `dns`: `enhanced-mode: redir-host`、`fake-ip-range: 198.19.0.1/16`（用于锚定 mihomo v1.19 顶层 `tun` 的 `wfctun0` 网段为 `198.19.0.1/30`，避免与外部 `198.18.0.1/30` 冲突；不启用 fake-ip 响应）、`proxy-server-nameserver: [223.5.5.5, 119.29.29.29]`（上游代理域名走系统直连解析），`nameserver: [https://1.1.1.1/dns-query#WFC-PROXY, tcp://8.8.8.8:53#WFC-PROXY]`（ePDG 域名解析走 `WFC-PROXY` 代理）。

3. **精准定向策略路由（`clash-control.sh`）**
   - 仅在 `wfctun0` 就绪后写入独立路由表 `209` 与两条精确 `ip rule`：
     - `ip route replace default dev wfctun0 table 209`
     - `ip rule add oif wfctun0 lookup 209 priority 1050`
     - `ip rule add from 198.19.0.1/32 lookup 209 priority 1051`
   - 仅当 `vowifi-engine-arm64` 通过 `SO_BINDTODEVICE("wfctun0")` 或源地址 `198.19.0.1` 发包时命中表 `209`；手机其他任何应用既不会绑定 `wfctun0` 也不会使用 `198.19.0.1`，完全不受影响；mihomo 自身出站连接走系统路由。
   - 停止内置 Clash、禁用模块（`service.sh`）或卸载模块（`uninstall.sh`）时，自动终止自有 `mihomo-arm64` 进程并清理优先级 `1050` / `1051` 规则与表 `209`。

4. **引擎与界面整合（`InterfaceSnapshot` + `RootIdentityMain` + `RuntimeController` + `MainActivity`）**
   - `InterfaceSnapshot` 识别 `wfctun0`（真实 `IFF_TUN` + `198.19.0.1`），标注为 `内置 Clash`，并在可用时优先作为默认出口（同时保留全部接口下拉选择，兼容 FlClash `tun0`）。
   - `RootIdentityMain.prepare` 在选择 `wfctun0` 时将引擎配置 `dns` 设为 `1.1.1.1`（经 `wfctun0` 发送并由 mihomo `dns-hijack: any:53` 经 `WFC-PROXY` 代理），确保非 Android VPN TUN 下 `directTUNBinding` 始终具备可靠 DNS 目标。
   - `MainActivity` 新增内置 Clash 控制区：总开关、状态显示、订阅链接拉取、YAML 文件导入、本地 `profile.yaml` 重载、`WFC-PROXY` 节点切换、组测速与一键切换海外卡出口至 `wfctun0`。

## 本地验证

- `tests/policy/run_tests.py`：策略/网络 **92**（含 `wfctun0` 优先、标签与非 TUN 伪造拒绝）、注册路由 **27**、单卡互斥 **28**、内置 Clash 配置净化 **33**（含剥离 `auto-route`/开放端口/用户规则、块级/行内/引号/Emoji 节点名提取、`proxy-providers` 支持、`DIRECT`/`REJECT` 剔除与空配置拒绝）、SIM 凭据 **181**、网络快照 **27**、身份/路径 **23** 项全部通过。
- `tests/policy/test_clash_control.py`：真实 `clash-control.sh` 路由函数与启动守卫验证通过（确认仅写入 `table 209` 与 `pref 1050/1051`，零 `main` 表与零 `iptables` 操作，停止时完整清理）。
- `tests/policy/test_module_control.py`（4 组）、`tests/policy/test_standalone_module.py`（21 场景）、`tests/engine/run_tests.py`（93）、`run_call_tests.py`（51）、`run_feature_tests.py`（51）、`test_native_selectors.py`（290）、`test_isolated_log_pool.py`（5）、短信测试（187）及 `tools/verify_artifacts.py` 全部通过。

## 真机验证结果（Xiaomi 15 / Android 17，无拨号、无短信）

- `ksud module install` 刷入最终 `0.3.0-dev` 模块包，自动完成随包 APK 覆盖安装（`versionCode=8`、`versionName=0.3.0-dev`），输出 `- SmartVoWifiCall APK installed/verified; existing configuration retained.`。
- 实机运行 `/data/adb/modules/smartvowificall/bin/mihomo-arm64 -v` 返回 `Mihomo Meta v1.19.34 android arm64 ... Use tags: with_gvisor`。
- 真机实测发现 mihomo v1.19 顶层 `tun:` 从 `dns.fake-ip-range` 推导 TUN 网段；补充 `fake-ip-range: 198.19.0.1/16` 后重新打包实测通过：
  - `RootClashMain start` 返回 `{"ok":true,"code":"CLASH_READY","device":"wfctun0"}`。
  - `/sys/class/net/wfctun0/tun_flags` 为 `0x1001`（真实 `IFF_TUN`），地址为 `198.19.0.1/30`（`UP`）。
  - `ip route show table 209` 仅含 `default dev wfctun0 scope link`；`ip rule show` 仅新增 `1050: from all oif wfctun0 lookup 209` 与 `1051: from 198.19.0.1 lookup 209`，`main` 路由表无默认路由变更。
  - `RootNetworkMain snapshot` 确认全程**零 Android VPN**（`vpn: false`）。
  - `ss -lntup | grep mihomo` 确认仅监听回环 `127.0.0.1:1053`（DNS）与 `127.0.0.1:19090`（带密钥控制器），零外部代理端口开放。
  - `RootClashMain status` 与 `select-node` 实测正常读取 `WFC-PROXY` 节点列表并切换持久化至 `Test-Node-B`。
  - `vowifi-engine-arm64 config-verify --strict` 对 `transport: "iface:wfctun0"` 与 `dns: "1.1.1.1"` 返回 `{"ok":true,"code":"IDENTITY_MATCH"}`。
  - `RootClashMain stop` 完整停止进程并清除 `1050` / `1051` 规则、路由表 `209` 与 `wfctun0` 设备；临时合成测试配置已删除。
  - `MainActivity` 实机 UI 检查确认内置 Clash 开关、订阅输入框、拉取/导入/重载按钮、节点下拉框、测速与一键切到 `wfctun0` 按钮及原有接口下拉框均正常渲染。

## 0.3.1-dev 根因修复与真实订阅端到端注册实测（2026-11-22，versionCode=9）

### 根因定位

用户在 `0.3.0-dev` 上导入真实海外订阅并成功测速选中节点后，引擎日志出现：

- `[tunnel] 连接失败: failed to bind socket: lookup wlan.three.com.hk on [::1]:53: selected network unavailable`（`tunnel.source: address_unavailable`）。
- 反汇编 `vowifi-engine-arm64` 的 `underlay.Binding.SourceIP`（`0x748c20`）与 `requireInterfaceRoute`（`0x745fb0`）确认：非 Android VPN 的 `directTUNBinding` 会调用不带 `oif`/`src`/`fwmark` 的 `netlink.RouteGet(dst)`（如 `1.1.1.1` 与 ePDG IP），并要求返回路由的 `LinkIndex == wfctun0`。`0.3.0-dev` 在主网络命名空间仅添加了 `oif wfctun0` / `from 198.19.0.1` 规则，因此普通 `RouteGet` 命中系统 `wlan0` 路由而被引擎拒绝。

### 修复方案：独立 Linux Network Namespace 硬件级隔离（无 bind-mount、无根命名空间路由改动）

- **`mihomo-arm64` 保持在根网络命名空间**：其自身出站代理连接直接走手机正常系统路由（Wi-Fi / 蜂窝），零系统 VPN，根网络命名空间零 `ip rule`、零路由表改动、零 iptables 改动。
- **`wfctun0` 迁入独立网络命名空间（`clash-control.sh`）**：启动后创建一个零 CPU 占用的命名空间锚点进程（`setsid unshare -n -- sleep 2147483647`，以 `<pid> <starttime> <netnsId>` 原子记录于 `/data/adb/smartvowificall_ims/clash/netns.owner`，通过 `/proc/<pid>/ns/net` 在 Android/KernelSU 全部挂载命名空间统一可见，避免 per-app mount namespace 下 bind-mount 不可见问题），将 `wfctun0` 移入该命名空间并在其中配置 `lo`、`198.19.0.1/30` 与唯一默认路由 `default dev wfctun0`。
- **仅 `iface:wfctun0` 引擎进程进入该命名空间（`control.sh`）**：以 `nsenter --net=/proc/<nspid>/ns/net -- unshare -m` 私有重挂 `/sys`（`mount -o private none / && mount -o private none /sys && mount -t sysfs sysfs /sys`，不污染根 `/sys`）后 `exec vowifi-engine-arm64`。实测确认 `AF_QIPCRTR`（QRTR QMI SIM AKA）与跨命名空间文件系统 Unix Socket（`/data/local/tmp/vowifi/*.sock`）均原生工作；`control.sh` 改为按 `/proc/$pid/net/unix` 校验引擎 socket 所有权。外部 TUN（如 FlClash `tun0`）保持原根命名空间路径不变。
- **`InterfaceParser`、`RootIdentityMain` 与 `RuntimeController` 联动**：列表与 `requireTun("wfctun0")` 在命名空间内复核 `tun_flags`、`198.19.0.1` 与 `default dev wfctun0`；`RuntimeController` 跟踪 `netnsId`，当内置 Clash 停止或重启导致命名空间切换时，在通话空闲下自动重启引擎接入新命名空间。

### 真机端到端实测结果（Xiaomi 15 / Android 17，真实海外订阅，无拨号、无短信）

- `ksud module install` 刷入 `0.3.1-dev`（`versionCode=9`）并自动完成随包 APK 覆盖更新。
- 实测内置 Clash 启动于独立命名空间 `net:[4026535807]`，节点为 `香港WiFicall（家庭） X1.5`（共 42 项）：
  - `PolicyService` 状态：`Wi-Fi Calling 模式；蜂窝已关闭`；卡槽 1（`45403`）`selectedInterface=wfctun0`、`radioOff=true`、`featureAttached=true`、`tunnel=CONNECTED`、`ims=REGISTERED`、`registered=true`、`independentProvider=true`。
  - 引擎日志完整走通 ePDG DNS（经 `WFC-PROXY`）→ IKEv2 UDP 500/4500（经 `WFC-PROXY`）→ `sv...@wfctun0` XFRM → `SIP REGISTER 200`（`expires=7200s`）→ `SUBSCRIBE 200`（`state=active`）。
  - Android 框架实测：`FrameworkProbeMain 4` 返回 `{"ok":true,"subId":4,"registrationState":2,"transportType":2,"featureState":2}`（`REGISTERED / WLAN / READY`）。
  - 卡槽 0（大陆中国移动 `46007`）：`IN_SERVICE` LTE 全程未受影响。
  - **零 Android VPN**：`RootNetworkMain snapshot` 仅见 `wlan0` / `rmnet_data3` / `rmnet_data4`，全部 `vpn: false`。
  - **手机其他流量零代理**：根网络命名空间 `ip link show wfctun0` 不存在、零 Clash `ip rule`、`ip route get 1.1.1.1` 直走 `wlan0`。
  - **跨挂载命名空间一致性与自愈**：从 `adb shell su`（init mount ns）执行 `clash-control.sh status` 准确返回 `running:true, tunUp:true, netnsId:"net:[4026535807]"`；执行 `clash-control.sh stop` 干净清除进程与 `netns.owner` 后，`PolicyService` 在约 16 秒内自动拉起新 Clash 命名空间并重启引擎恢复 `REGISTERED / WLAN / READY`。

## 0.3.2-dev 修复单卡关蜂窝下拨号被 `STATE_OUT_OF_SERVICE` 拦截与小米重连问题（2026-11-22，versionCode=10）

- **用户实测现象**：卡槽 1 在 `ims=REGISTERED` 且 `isWifiCallingEnabled=true` 状态下尝试拨号时，`Telecom` 立即断开（`Reason: ServiceState.STATE_OUT_OF_SERVICE`），提示“移动网络不可用。需连接至无线网络才能拨打电话。”（此外 `21:47:52` 所选代理节点曾短暂超时掉线，约 15 分钟后恢复）。
- **根因（实机字节码核实）**：`GsmCdmaPhone.mergeVoiceServiceStates` 在蜂窝 voice/data 均为 `OUT_OF_SERVICE` 时，必须依赖 `mTelecomVoiceServiceStateOverride == STATE_IN_SERVICE` 才会向 `TelephonyConnectionService` 汇报 `STATE_IN_SERVICE`；否则即使 `ImsPhone` 已在 `IWLAN` 注册，也在到达 `GsmCdmaPhone.dial()` 之前被拦截。另发现 `am force-stop` 后小米 `ImsServiceController` 的 `xiaomi_issue` 检查会跳过相同 `mImsFeatures` 的重绑请求。
- **修复与真机验证**：
  - 仅在目标卡 `radioOff=true && ims==REGISTERED && billableAllowed==true` 时调用 `ITelephony.setVoiceServiceStateOverride(subId, true, "root")`，掉注册/恢复/卸载时立即清零为 `false`。
  - `alreadyBound && needsFeature` 时先 `restore` 再 `bind`，解决强停/升级后 `NewMmTelFeature` 不挂载问题。
  - Xiaomi 15 实测：`featureAttached=true`、`FrameworkProbeMain 4` 返回 `registrationState=2, transportType=2, featureState=2`、`voiceServiceOverride=true`、`dumpsys telephony.registry` 显示卡槽 1 `mVoiceRegState=0(IN_SERVICE), mDataRegState=1(OUT_OF_SERVICE)`、`Phone-1: isImsRegistered=true, isWifiCallingEnabled=true`，卡槽 0 中国移动 LTE 不受影响。未主动拨号或发短信，留待用户实测。

## 0.4.0-dev 液态玻璃四页面 UI、外部控制/实时连接、自动蜂窝回退与移除常驻通知验证（2026-11-22）

1. **多页面 Apple Design + Miuix + SukiSU-Ultra 同源液态玻璃底栏**：
   - 升级构建链路至 Gradle `9.4.1` + AGP `9.1.0` + Kotlin Compose `2.4.20`（`compileSdk=37`），集成 `top.yukonga.miuix.kmp:miuix-{ui,preference,icons,blur}-android:0.9.4` 与 `io.github.kyant0:backdrop-android:2.0.1`。
   - 真机 Xiaomi 15 实测渲染「概览」「代理」「连接」「网络」四页面，移植 Kyant / SukiSU-Ultra `LiquidBottomTabs` + `DampedDragAnimation` + `InteractiveHighlight` 三层底栏（按压滑块放大 `78/56`、隐藏着色层放大 `1.2x`、实时 `lens` 折射与色散、高光及速度拉伸形变）。
2. **External Controller 与实时连接观测**：
   - `ClashConfigTest` 扩展至 43 项断言，验证 `externalControllerLan=true/false` 下 `0.0.0.0:<port>` + CORS、`127.0.0.1:<port>`、强制 `secret`、`dns.ipv6: false` 与零开放代理端口。
   - 真机 `RootClashMain connections` 与 App「连接」页实时观测到 `wfctun0`（`198.19.0.1`）至 `wlan.three.com.hk:500/4500` 的 UDP IKEv2/IPsec 会话及 `WFC-PROXY -> 香港WiFicall（家庭） X1.5` 链路与上下行字节统计。
3. **接口切换/失效时的蜂窝回退与状态栏 VoWiFi 图标清理（`FallbackPolicy`）**：
   - 新增 `FallbackPolicyTest`（44 项断言）通过。
   - 真机验证：当卡槽 1 出口切换为非 TUN 接口 `dummy0` 时，`PolicyService` 立即先清空 `MmTelCapabilities` 并发送 `onDeregistered`，再解绑独立 IMS 并释放蜂窝射频（`status="VoWiFi 暂不可用（所选接口不是 TUN）；已回退蜂窝"`、`radioOff=false`、`FrameworkProbeMain 4` 返回 `registrationState=2, transportType=1, featureState=2` 回退至蜂窝）；切回 `wfctun0` 后自动重新关闭单卡蜂窝并恢复 `REGISTERED / WLAN / READY`（`registrationState=2, transportType=2, featureState=2`、`voiceServiceOverride=true`）。
4. **移除常驻通知**：
   - `PolicyService` 启动后立即执行 `stopForeground(STOP_FOREGROUND_REMOVE)` 并取消通知；真机 `dumpsys notification | grep -c 'pkg=com.smartvowificall.ims'` 返回 `0`。

## 0.4.1 / 0.4.2 挂断后再次呼出与实际数据出口（2026-11-23）

### 已确认的语音覆盖失效

- 初始手机仍运行更名前的 0.4.0 包。关闭物理 Wi-Fi 后，引擎保持 `REGISTERED / CONNECTED`，海外卡蜂窝协议栈经 `radio-check` 确认为关闭；用户首次呼出成功。
- 用户挂断后再次呼出失败。此时应用缓存 `voiceServiceOverride=true`，但 `telephony.registry` 的目标卡语音状态已变为 `OUT_OF_SERVICE`，IMS 仍注册。仅重新执行该卡的 `voice-service-override true` 即恢复 `IN_SERVICE`，未切换网络或重新注册。
- 修复不再把上次成功写入当作系统当前状态：只在当前有效绑定、真实注册、允许通话且射频关闭时重新应用 `true`；失败置未知、下次重试，禁用/掉注册仍清除。IDLE 电话状态广播触发策略同步，周期轮询兜底。
- 新版三次无拨号测试，主动清除覆盖后观察到 `OUT_OF_SERVICE → IN_SERVICE`，恢复耗时分别为 1.39、3.37、0.74 秒。这是状态恢复测试，不是实际拨号时间承诺。

### 挂断异常调查与用户验收

- 0.4.1 曾观察到系统 `DIALING`、引擎独立探针返回 `callStage=IDLE` 的残留通话；用户重启后清除。日志还出现 `RemoteException: Fail to get ImsConfigImpl`。
- 0.4.2 按卡槽保持 `ImsConfigImplBase` 强引用，避免 framework Binder 的弱引用失效；补充不含号码/凭据的会话回调日志和只读线程诊断。上述配置异常已修复，但不能仅凭它断言所有先前通话残留都由同一原因引起。
- 实机日志观察到 `start → onCallStarted → terminate → terminalCallbackComplete`。其中一次用户呼叫从应用 start 到建立约 7.1 秒，挂断请求到 framework `onCallTerminated(reasonCode=501)` 约 0.68 秒。
- 用户最终明确反馈：“这个版本OK啊，开了3个电话都很稳”。记录为用户实际通话验收，代理未主动拨号或发送短信。
- 策略回归 475 项、通话/音频 39 项、relay 12 项和 Feature 生命周期 51 项断言通过；Release 编译和 APK 签名验证通过。

### 4636 的数据状态解释

- 独立模式验证时，海外卡 `radioOff=true`、`mDataRegState=OUT_OF_SERVICE`、`mDataConnectionState=0`；系统默认互联网网络为大陆卡的 `cmnet`，不是海外卡移动数据。该结论来自当时的 Binder/系统网络状态，不是按 UI 文案推断。
- 用户重启且总开关关闭时，海外卡已按设计恢复蜂窝 LTE，观察到 `CONNECTED ROAMING extra: ims`、能力 `IMS/MMTEL` 的原厂专用承载；默认互联网出口仍为大陆卡。这能与 4636 显示数据已连接并存，不能把专用 IMS 承载当作默认上网连接，也不能把这种状态说成海外卡射频仍关闭。
- 应用 `tunnel=CONNECTED` 指独立 ePDG 隧道，并不等同于 `ims=REGISTERED`。总开关关闭、接口失效或超时自动回退后，海外卡可能恢复蜂窝；本版不承诺这些状态下永久禁用海外卡数据。

### 部署与回滚

- 订阅、节点、配置和用户偏好已迁移；旧模块与旧应用停用但保留，未删除个人配置。配置复制逐项比对一致。
- 迁移前备份位于 `C:\ProjBackup\SmartVoWifiCall\20261123-0140-migration\original.tgz`，主机与手机 SHA-256 一致。该私有备份不进入 Git 或源码包。
- 用户重启后确认模块 `service.sh` 和 `RootNetworkMain watch` 由开机流程启动，新应用具备系统特权标志；之后覆盖部署 0.4.2，不改用户订阅或默认数据卡。

## 0.4.3 节能与代理生命周期（2026-11-23，离线验证）

### 优化前采集

用户拔线前只读保存了 19 份资料，位于私有备份 `C:\ProjBackup\SmartVoWifiCall\20261123-power-baseline`，包含电池统计、线程栈、内存、网络、实际部署脚本及 6 次约 27.7 秒跨度的进程采样；文件已校验 SHA-256。最近保留日志中分别出现 `RootIdentityMain` 67 次、`RootClashMain` 22 次、`RootNetworkMain` 22 次启动；这些是保留日志窗口计数，不是每秒频率或电量消耗百分比。采集未重置计数器、拨号或修改设置。

### 已实现

- `PolicyLoop` 合并突发事件；稳定注册每 5 秒使用已有 socket 检查引擎，每 30 秒完整检查 SIM/接口/代理。启动、异常和关闭未完成时仍快速重试，关闭完成后不再安排应用定时任务。网络、SIM 和电话状态通知保持实时响应。
- `VoiceServiceOverride` 在本地 framework 语音状态仍为 `IN_SERVICE` 且上次应用成功时跳过重复 root 写入；若挂断导致覆盖被清除、状态未知或上次失败，则继续修复。不存在仅凭旧缓存永远不重申的问题。
- `NewMmTelFeature` 对重复能力上报去重；注册丢失、收费开关改变和新 listener 仍立即通知。
- 启用服务自动启用代理；完整检查先启动 Clash 并核实接口，再进入引擎准备。关闭事务顺序为清理会话、恢复系统 IMS、归还蜂窝、停止 Clash；任一步失败都会保留后续依赖并重试，通话中延迟关闭。关闭期间更新配置只保存，不启动代理。
- 模块不再每 5 秒调用 `start-foreground-service`；每 30 秒只检查应用是否存活，缺失时恢复。socket 修复降至 30 秒。没有引擎 PID 记录时停止网络快照发布器；有引擎时保留每 5 秒发布，未放宽原有 15 秒新鲜度要求。
- 临时网络快照仍以原子重命名发布，不再每次 `fsync`。SIM 配置、事务日志等需持久恢复的数据不变。
- UI 仅 `RESUMED` 时定时刷新；连接页须可见、代理运行且未暂停，间隔 3 秒，防止请求重叠。Clash 默认日志为 warning；运营商/IKE/SIP 保活和流量隔离规则均未修改。
- UI 去除面向开发者的副标题和冗长技术说明，保留功能开关、必要地址/接口字段与收费提示。

### 验证与未验证项

- 策略/节能测试共 506 项断言，包含停用顺序、活跃通话保护、失败重试、关闭无定时工作、事件合并、语音覆盖重置修复和避免重复写入。
- Feature 生命周期 56 项断言、模块安装/看护 23 项测试及 Release 构建通过。诊断输出新增 `shutdownComplete` 和 `rootCommandCount`，可用于重新接机后对照采样。
- 手机已拔线，因此没有部署或测量 0.4.3 的真实电耗改善，不能给出续航提升百分比，也不能把 0.4.2 的三个稳定电话归为本版验证。
- 重新接机需验证：关闭后 `shutdownComplete=true` 且 Clash/引擎/网络发布器退出；开启后 Clash 先就绪再注册；待机 root 命令计数增长降低；锁屏无连接页轮询；连续呼叫与挂断无回归。对比耗电时应使用相同亮屏、充电、网络与时长条件。
- 先前稳定交付物已备份于 `C:\ProjBackup\SmartVoWifiCall\20261123-before-power-optimization`，不会以未验收节能版替代其稳定性记录。

## 仍存在的验证边界

- 尚未执行真实卸载闭环、长通话/音质测试或运营商短信验收；代理未执行真实拨号或发短信。用户确认三次通话不等同于所有网络、运营商均验证。

## 历史记录：0.3.0-dev 首次验证边界（已由 0.3.1-dev 解决）

- 0.3.0-dev 曾使用根网络命名空间 `table 209` + `pref 1050/1051` 规则，已在 0.3.1-dev 替换为独立网络命名空间方案并在升级时自动清理旧规则。
