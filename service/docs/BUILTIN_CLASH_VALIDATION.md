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
     - `dns`: `enhanced-mode: redir-host`、`proxy-server-nameserver: [223.5.5.5, 119.29.29.29]`（上游代理域名走系统直连解析），`nameserver: [https://1.1.1.1/dns-query#WFC-PROXY, tcp://8.8.8.8:53#WFC-PROXY]`（ePDG 域名解析走 `WFC-PROXY` 代理）。

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

## 待真机验证的边界

- 本轮手机未通过 ADB 连接，**0.3.0-dev 尚未在真机上安装实测**。
- 连接手机并刷入 `NewVoWiFi-module.zip` 后，需实测验证：
  1. `mihomo-arm64` 在 Android 17 上正常启动并创建 `wfctun0`（`198.19.0.1/30`），状态栏不出现系统 VPN 钥匙图标；
  2. `ip rule show` 仅增加 `1050` / `1051` 两条 `wfctun0` 规则，手机其他应用出口 IP 与直连访问不受影响；
  3. 填入订阅或导入 YAML 并选定海外节点后，`vowifi-engine-arm64` 通过 `iface:wfctun0` 完成 `config-verify --strict`、ePDG DNS 代理、IKEv2 与 SIP REGISTER（`REGISTERED / WLAN / READY`）；
  4. 停用内置 Clash 或卸载模块时，`mihomo-arm64` 退出且 `1050` / `1051` / `table 209` 完整清除。
