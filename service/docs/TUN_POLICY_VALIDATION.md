# 0.2.4-dev TUN 出口与普通蜂窝回退

日期：2026-11-22（北京时间），versionCode=6。

## 已确认的需求

用户要求独立 VoWiFi 流量使用所选 TUN，不能因为接口失败而直接改走本卡蜂窝接口；随后明确允许 TUN 不可用或独立 IMS 未注册时，普通电话恢复原厂蜂窝通话流程。两者分别由出口选择和系统 provider 策略实现。保留配置、单包 APK 自动覆盖升级，不进行真实拨号或短信发送测试。

## 本版实现

1. `TunEgress` 读取 `/sys/class/net/<name>/tun_flags`，按 IFF_TUN 判断真实设备类型；名称前缀、Android VPN 标志和所有者信息不能代替该检查。TAP、物理网卡及无法读取类型的接口不能用于独立 VoWiFi。
2. 清单仍列出所有接口；root 只读清单补充普通应用无法读取的 TUN 类型。FlClash 首次默认值要求真实 TUN、Android VPN 与核实的包所有者同时满足。已有选择不被重写。
3. 策略要求所选 TUN 已 UP 且有地址；没有物理 Wi-Fi 也可准备注册。选到物理网卡时显示不可用于 VoWiFi，在空闲时清理本项目的接管记录并恢复原厂服务。
4. root 配置准备与原子提交前重新验证 TUN；模块 `control.sh start` 再读取配置并检查实际设备，失败时在访问引擎/共享 socket 前返回 `TUN_EGRESS_UNAVAILABLE`。旧配置中的 `wifi`、`sim1`、`sim2` 或自动默认模式不能绕过显式接口限制。
5. 0.2.3 的注册/provider 分离逻辑保留：只有独立引擎真实注册且控制连接有效才接管，注册丢失时空闲后恢复原厂 provider，候选注册恢复后可重新接管。普通蜂窝服务是否可用仍取决于实际无线状态及运营商，不由 provider 恢复保证。

本次没有修改原生引擎。精确接口选择、绑定错误传递与 DNS/IKE/用户态 ESP 路径的二进制证据见 [TUN 出口分析](TUN_EGRESS_FINDINGS.md)。该文记录静态证据及未证实部分，不能替代抓包。

## 本地验证

- `tests/policy/run_tests.py`：策略/网络 **89**、注册/provider **27**、SIM 凭据 **181**、网络快照 **27**、身份/路径 **23** 项通过。新增覆盖非 TUN 拒绝、不依赖物理 Wi-Fi、错误选择保留与恢复服务、root TUN 类型解析、显式 selector 和 FlClash 默认选择约束。
- `tests/policy/test_module_control.py`：4 组通过，包括实际 helper 调用参数、成功结果、TUN 不可用、空输出、helper 失败/缺失，以及实际 start 分支在失败后不触达引擎/socket；既有所有权和只读 status 行为通过。
- `tests/policy/test_standalone_module.py`：20 个真实 shell 流程替身场景通过，覆盖单包安装/覆盖升级、保留配置、启动重试和失败状态。
- `tests/engine/test_native_selectors.py`：290 项原始 AArch64 指令重放通过；外部系统调用为替身，不联网。
- APK 完整构建、签名、模块与独立 APK 字节一致、TUN 校验代码入包、固定引擎哈希、ZIP 与脚本检查通过；当前交付物哈希见 `dist/SHA256.json`。

所有上述测试均在主机执行，不连接运营商、不拨号、不发送短信。0.2.3 安装包与源码包已复制至 `C:\ProjBackup\SmartVoWifiCall\20261122-151303-before-tun-egress-0.2.4\` 并核验 SHA-256。

## 待真机验证的边界

- 本轮 ADB 未列出手机，**0.2.4 尚未安装**。注册、升级保留配置、无 Wi-Fi 的 TUN 注册以及重启恢复均不能标为本版实测通过。
- 配置/启动校验及 native 轮询不能证明 TUN 删除、同名重建瞬间没有残留包。需要核对内核 XFRM/策略路由并观察 DNS、IKE/NAT-T 和加密数据包的实际输出。
- 外层 FlClash TUN 与引擎内层 IMS TUN 是不同设备。用户选择的外层 TUN 不证明 FlClash 使用何种代理规则或物理上游；此版不设置 FlClash 的 DIRECT 规则或蜂窝上游限制。
- 恢复原厂 provider 允许普通蜂窝回退，但本模块没有修改原厂 WFC 偏好，因此此版限制针对独立引擎，不能宣称全机原厂 VoWiFi 也受同一限制。

交付为开发版单 ZIP，包含 APK 自动覆盖更新并保留原设置；本地代码/包验证与上述设备验收分别记录。
