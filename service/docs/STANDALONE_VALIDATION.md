# 0.2.0-dev 独立版验证

日期：2026-11-22（北京时间）。应用及模块版本 `0.2.0-dev`，versionCode 2。

## 当前结论

已完成独立版源码、APK 和模块打包：不再要求安装 SoftVoWiFi 应用或模块。模块自带协议引擎，NewVoWiFi 自己提供当前 SIM 凭据与实时网络快照。

**本轮 ADB 设备列表为空，未安装新版本到手机，未做无旧组件条件下的注册测试或重启恢复测试。** 0.1 的成功注册属于历史基线，不能当作 0.2 的真机通过证明。全程没有实际拨号或发送短信。

## 已移除的外部依赖

| 原依赖 | 当前实现与验证 |
|---|---|
| 安装器要求 `com.vowifi.sw` | 删除该检查；模拟不存在旧应用的安装环境通过，Android API 限制保留 |
| 旧 APK 的 ProvisioningIdentityMain | 内置 SimCredentials，通过 IPhoneSubInfo 读取当前 IMSI、IMEI、ISIM；新 DEX 无旧包引用 |
| 原组件生成 `/data/adb/vowifi/run/networks.json` | RootNetworkMain 根据 Android Binder 实时生成自己的 `/data/adb/nvwims/run/networks.json` |
| 原项目的网络快照、通话锁和日志路径 | 内置引擎四个默认路径迁至 `/data/adb/nvwims`；原件相对差异精确为 16 数据字节，代码和 ELF 布局不变 |
| 原 Java SIM 鉴权桥 | 显式使用引擎内置 `--sim-mode=qrtr`；静态确认直接访问 Qualcomm QRTR/UIM，strict config verify 也不依赖旧 APK |

凭据读取使用 Android `IPhoneSubInfo` 的当前订阅/phone ID 接口；字段、调用参数与异常状态参考 [AOSP 接口](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/com/android/internal/telephony/IPhoneSubInfo.aidl) 和现有打包 helper 的格式契约。必需凭据读不到则停止；可选 ISIM 字段区分 empty、available、denied、unsupported、invalid；不拼接旧卡信息、不伪造身份。

网络快照使用完整 64 位 Network handle、真实 IP/DNS、boot ID 和 elapsedRealtime。模块维护独立发布进程，每轮间隔 5 秒，避免 SIM 鉴权或生命周期锁阻塞发布；策略执行前也立即发布。原生引擎会拒绝超过 15 秒的快照。快照原子写入，文件 0600、目录 0700；不复制旧 registry，不填造地址或 DNS。

发布已读取到的真实飞行模式。可选 accessGeneration 未实现；实际网络属性变化及快照失效仍会被原引擎监测，但可能漏掉采样间发生、结束后网络属性不变的纯接入事件。详见 [原生依赖证据](STANDALONE_ENGINE_DEPENDENCIES.md)。

## 本地验证结果

| 检查 | 结果 |
|---|---|
| 归属卡与网络策略 | 57 项通过 |
| 新 SIM 凭据读取 | 181 项通过；使用虚构 Binder 响应，包括 subId/phone ID 区分、前后 IMSI 变化、缺失身份、可选字段失败与 SIP 字段校验 |
| 新网络快照 | 27 项通过；真实格式的固定地址、64 位 handle、JSON、地址/DNS/接口/slot 数量与有效性 |
| 原身份及路径校验 | 23 项通过 |
| 引擎协议与业务开关 | 93 项通过 |
| 通话生命周期及音频替身 | 39 项通过 |
| 短信 PDU / 适配替身 | 135 + 52 项通过 |
| 模块生命周期 | 2 组现有场景通过；4 个 shell 脚本语法通过 |
| 无旧应用的安装器 | 允许 API 37、拒绝 API 36，不调用旧应用查询 |
| 原始引擎选择器指令重放 | 290 项通过；外部执行边界为替身 |
| 16 字节路径隔离 | 5 组通过：字节差异、ELF 不变、重现、源哈希限制、元数据一致性 |
| 交付物 | APK 签名、ZIP 完整性、模块嵌入 APK 一致、内置引擎哈希、旧依赖引用排除通过 |

Java/协议检查合计 **607 项**。这些本地检查不替代 Android 权限、真实 SIM、运营商注册或重启测试。未进行真实通话、短信及音频测试。

## 交付物

| 文件 | SHA-256 |
|---|---|
| NewVoWiFi.apk | `d424490d94fe1f41baf5be26af6066d5d25296dd61557cc89e22e1db45cb280e` |
| NewVoWiFi-module.zip | `f8ba14db4b08682d1f6b13bdb27ee74eb66b11ec21734c5bcb2f49fca4a60b05` |
| 模块内引擎 | `f53df163c7731ebeba45ebf913e85cace0af2884647359155ed515e137b479b3` |

完整交付哈希（含源码 ZIP）见 `dist/SHA256.json`。源码包排除签名文件、手机身份、原始日志及一次性实机修复工具。此前 0.1 安装包已备份并核验至 `C:\ProjBackup\NewVoWiFi\20261122-083948-before-standalone-0.2\`。

## 使用前提与待验收项

- 需要已 root 的 Android 17 / arm64 设备及 Qualcomm QRTR SIM 通道。首次仍需授予 NewVoWiFi root、开启接管并选择网络。
- 需要用户选择的网络已可访问运营商 ePDG；使用 FlClash 时仍由其提供 VPN/出口。本模块无需 SoftVoWiFi，但并不内置 VPN 订阅。
- 协议引擎为随模块打包的派生二进制；完整引擎源码未提供，不能称为全部协议栈重新实现。
- 手机重连后需验证旧应用/模块停用条件下的配置生成、网络快照持续更新、实际注册、系统 MMTEL 回调、重启自动恢复，以及大陆卡仍保留原厂流程。
- 普通语音与短信发送默认关闭。后续验收继续遵守不实际拨号、不发送短信的限制。
