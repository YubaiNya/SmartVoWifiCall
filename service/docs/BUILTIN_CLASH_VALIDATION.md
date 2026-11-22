# 0.3.0-dev 内置 Clash（mihomo）仅代理 Wi-Fi Calling（`wfctun0`，不开启系统 VPN）

日期：2026-11-22（北京时间），versionCode=8。

## 已确认的需求

用户要求在模块内直接内置 Clash 内核，**仅全局代理 Wi-Fi Calling 相关流量**，不代理手机其他任何流量，也不在手机开启系统 VPN：

- 专用 TUN 接口名为 **`wfctun0`**，保留接口下拉框兼容其他真实 TUN（如 FlClash `tun0`）。
- App 内支持输入订阅链接拉取、导入 YAML 文件及切换/测速出口节点，同时保留 `/data/adb/newvowifi_ims/clash/` 目录（编辑 `profile.yaml` 后可一键重载）。
- 内置 Clash 连接上游代理服务器时直接走手机系统默认路由。
- 保留 0.2.5 的单卡蜂窝协议栈互斥（`enableModemForSlot`）与禁止普通呼叫 CSFB 回退。

## 核心实现

1. **内置官方 `MetaCubeX/mihomo` 内核**
   - 打包官方 `MetaCubeX/mihomo v1.19.34`（`mihomo-android-arm64-v8-v1.19.34.gz`）至模块 `bin/mihomo-arm64`（`0755`），来源与 SHA-256 固化于 `service/deps/mihomo.source.json`，GPL-3.0 许可证保留在 `module/licenses/MIHOMO-GPL-3.0.txt`。

2. **强制隔离配置生成（`ClashConfig` + `RootClashMain`）**
   - 用户配置写入 `/data/adb/newvowifi_ims/clash/profile.yaml`（`0600`）；每次启动或重载由纯 Java 净化器 [ClashConfig.java](../app/src/main/java/com/newvowifi/ims/clash/ClashConfig.java) 生成 `runtime.yaml`，不修改用户原文件。
   - 自动剥离原订阅中可能影响系统或其他应用的顶层字段（`port` / `mixed-port` / `socks-port` / `redir-port` / `tproxy-port` / `allow-lan` / `tun` / `dns` / `rules` / `proxy-groups` / `external-controller` / `interface-name` / `routing-mark` 等），强制写入：
     - `port: 0`、`mixed-port: 0`、`socks-port: 0`、`redir-port: 0`、`tproxy-port: 0`、`allow-lan: false`（不开放任何代理端口）。
     - `external-controller: 127.0.0.1:19090` + 随机 48 位十六进制 `secret`（`/data/adb/newvowifi_ims/clash/controller.secret`，`0600`）。
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

- `ksud module install` 刷入最终 `0.3.0-dev` 模块包，自动完成随包 APK 覆盖安装（`versionCode=8`、`versionName=0.3.0-dev`），输出 `- NewVoWiFi APK installed/verified; existing configuration retained.`。
- 实机运行 `/data/adb/modules/newvowifi/bin/mihomo-arm64 -v` 返回 `Mihomo Meta v1.19.34 android arm64 ... Use tags: with_gvisor`。
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
- **`wfctun0` 迁入独立网络命名空间（`clash-control.sh`）**：启动后创建一个零 CPU 占用的命名空间锚点进程（`setsid unshare -n -- sleep 2147483647`，以 `<pid> <starttime> <netnsId>` 原子记录于 `/data/adb/newvowifi_ims/clash/netns.owner`，通过 `/proc/<pid>/ns/net` 在 Android/KernelSU 全部挂载命名空间统一可见，避免 per-app mount namespace 下 bind-mount 不可见问题），将 `wfctun0` 移入该命名空间并在其中配置 `lo`、`198.19.0.1/30` 与唯一默认路由 `default dev wfctun0`。
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
   - 真机 Xiaomi 15（`c4e7fca`）实测渲染「概览」「代理」「连接」「网络」四页面，移植 Kyant / SukiSU-Ultra `LiquidBottomTabs` + `DampedDragAnimation` + `InteractiveHighlight` 三层底栏（按压滑块放大 `78/56`、隐藏着色层放大 `1.2x`、实时 `lens` 折射与色散、高光及速度拉伸形变）。
2. **External Controller 与实时连接观测**：
   - `ClashConfigTest` 扩展至 43 项断言，验证 `externalControllerLan=true/false` 下 `0.0.0.0:<port>` + CORS、`127.0.0.1:<port>`、强制 `secret`、`dns.ipv6: false` 与零开放代理端口。
   - 真机 `RootClashMain connections` 与 App「连接」页实时观测到 `wfctun0`（`198.19.0.1`）至 `wlan.three.com.hk:500/4500` 的 UDP IKEv2/IPsec 会话及 `WFC-PROXY -> 香港WiFicall（家庭） X1.5` 链路与上下行字节统计。
3. **接口切换/失效时的蜂窝回退与状态栏 VoWiFi 图标清理（`FallbackPolicy`）**：
   - 新增 `FallbackPolicyTest`（44 项断言）通过。
   - 真机验证：当卡槽 1 出口切换为非 TUN 接口 `dummy0` 时，`PolicyService` 立即先清空 `MmTelCapabilities` 并发送 `onDeregistered`，再解绑独立 IMS 并释放蜂窝射频（`status="VoWiFi 暂不可用（所选接口不是 TUN）；已回退蜂窝"`、`radioOff=false`、`FrameworkProbeMain 4` 返回 `registrationState=2, transportType=1, featureState=2` 回退至蜂窝）；切回 `wfctun0` 后自动重新关闭单卡蜂窝并恢复 `REGISTERED / WLAN / READY`（`registrationState=2, transportType=2, featureState=2`、`voiceServiceOverride=true`）。
4. **移除常驻通知**：
   - `PolicyService` 启动后立即执行 `stopForeground(STOP_FOREGROUND_REMOVE)` 并取消通知；真机 `dumpsys notification | grep -c 'pkg=com.newvowifi.ims'` 返回 `0`。

## 仍存在的验证边界

- 尚未对 `0.4.0-dev` 执行整机冷重启与真实卸载闭环；未由代理执行真实拨号或发短信。

## 历史记录：0.3.0-dev 首次验证边界（已由 0.3.1-dev 解决）

- 0.3.0-dev 曾使用根网络命名空间 `table 209` + `pref 1050/1051` 规则，已在 0.3.1-dev 替换为独立网络命名空间方案并在升级时自动清理旧规则。
