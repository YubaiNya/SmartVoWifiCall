# 0.2.3-dev 注册状态与系统 IMS 回退

日期：2026-11-22（北京时间），versionCode=5。

## 用户报告及边界

用户报告不连接 Wi-Fi 拨号会提示移动网络不可用、需要连接 Wi-Fi。本轮 ADB 设备列表为空，无法核实当时的飞行模式、蜂窝注册、所选 VPN 状态、系统 provider 或已安装版本。因此只能修复已确认的源码缺口，不能判定手机提示的唯一原因。

若飞行模式仍开启，断开 Wi-Fi 后没有正常蜂窝呼叫通路；恢复原厂 IMS provider 也不会自动开启蜂窝无线或保证运营商服务。本修复没有自动改变飞行模式、用户通话偏好或 SIM 归属策略。

## 缺口与改动

旧版的接口可用判断为 UP、有地址且非回环。VPN 可以在 Wi-Fi 断开后保留接口/IP；旧 Runtime 在引擎启动和 socket 连接后立刻 root.bind，没有等待 IMS REGISTERED。之后 native IMS 未注册时也不恢复 provider，除非接口变为不可用或引擎进程死亡。

0.2.3 将引擎生命周期与系统 provider 选择分开：

1. 符合海外卡策略且所选接口可用时，启动独立引擎作为候选，先不抢占系统 MMTEL。
2. 只有真实 getStatus 为 REGISTERED 且控制连接仍有效，才在框架无通话时选择独立 provider。root backend 仍执行当前身份、通话和原有覆盖记录核对。
3. 未注册或连接丢失时，仅恢复本项目持有的 override；有框架/引擎通话则延后。候选引擎可继续重试，接口选择不清空。
4. 注册恢复后再选择独立 provider；已稳定选中的 provider 不每轮重复绑定。
5. 候选引擎已有来电、Android 框架仍空闲时，可以接入该 feature 并向系统呈现来电；不会因候选自己的 RINGING 状态而永久阻止接入。
6. 界面显示实际 provider 选择；诊断新增 independentProvider、providerSwitchDeferred。显示系统 IMS 不代表已验证蜂窝注册成功。

大陆卡仍由原有策略排除，其他应用持有的 carrier override 不被覆盖。所选接口仍可为 VPN/移动网络等；没有强制添加“只有物理 Wi-Fi 才允许接管”的新限制。

## 验证

- 新 RegistrationRoutingTest：**27 项通过**，覆盖首次未注册、注册成功、稳定状态幂等、VPN 仍在线但注册丢失、恢复后重新接管、框架/引擎通话延后、候选来电、进程恢复时旧 override、第三方覆盖、未知状态及后端失败。
- 既有策略 57、凭据 181、网络快照 27、身份/路径 23 项通过。
- 通话 feature 38、通话/音频 39、root relay 12、协议 93 项通过。
- 单 ZIP 安装/升级 **20 个场景通过**。
- 全量源码构建、APK 签名及模块嵌入 APK、引擎哈希、ZIP 内容校验通过。

所有场景均为本地 Java/shell/transport 替身。没有真实拨号、短信发送，也没有更改手机网络设置。需在手机恢复连接后读取真实系统 provider 与注册状态，才能验证用户报告的无 Wi-Fi 呼叫问题是否消除。

## 交付

- 模块 SHA-256：`617364d5e9fd5afeec95cd02160b3fcba45a5ba600a2a1bc9f2ef77f96d1bd1f`
- APK SHA-256：`24564e26cb9acd0d9b89bffc84366bd471ff785d260c079f6666f32fd2a889c1`
- 使用单个 `SmartVoWifiCall-module.zip` 覆盖刷入并重启，APK 自动升级并保留配置。
- 旧包备份：`C:\ProjBackup\SmartVoWifiCall\20261122-143603-before-ims-fallback-0.2.3\`，哈希已核验。
