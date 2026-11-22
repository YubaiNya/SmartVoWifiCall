# 版本记录

## v0.2.0-dev — 2026-11-22

独立模块版本，应用与模块 versionCode 升为 2。

- 内置 SIM 凭据读取，直接调用 Android 电话服务，去掉旧 APK 查找和 provisioning helper 调用。
- 内置实时网络快照发布，使用真实网络 handle、IP、DNS、开机标识和单调时钟，模块每 5 秒刷新。
- 引擎四个默认文件路径迁往 `/data/adb/nvwims`，相对原件精确修改 16 个数据字节；不修改协议或鉴权代码。
- 安装器不要求存在 SoftVoWiFi 包；新 APK 不再声明或加载旧包，模块自带所需引擎。
- 本地增加独立凭据、网络快照和无旧应用安装场景测试，交付物检查包含旧依赖引用排除。

本轮手机离线，0.2 新安装包尚未进行独立注册或重启恢复实测；不能沿用 0.1 的 SIP 成功结果作为新版本验收。通话、短信发送仍默认关闭，未实际拨号或发送短信。详情见 [独立版验证](service/docs/STANDALONE_VALIDATION.md)。

0.1 已验证安装包在重新构建前保留至 `C:\ProjBackup\NewVoWiFi\20261122-083948-before-standalone-0.2\` 并逐一核验 SHA-256。当前交付物哈希见 `service/dist/SHA256.json`。

## v0.1.0-dev — 2026-11-22

首次 Git 基线，应用与模块版本均为 `0.1.0-dev`，versionCode 为 `1`。

### 实现

- 独立 Android ImsService，海外归属卡自动接管，大陆卡保持原厂 IMS。
- 按 SIM 保存接口选择，核实 FlClash VPN 所有者，接口失效时恢复目标卡原厂服务。
- 独立引擎生命周期、配置、日志与 root socket 中继。
- MMTEL 就绪通知、注册回调、普通语音/PCM 与文字短信适配；收费业务默认关闭。
- 复用 SoftVoWiFi 0.3.48 arm64 引擎，仅在独立副本中修改 4 个日志池路径数据字节。

### 验证基线

Xiaomi 15 / Android 17 实测 CTExcel SIP REGISTER 返回 200，系统 API 确认 REGISTERED / WLAN / READY。接口失效回退、关闭服务恢复原厂、重新连接注册已通过；大陆卡绑定未改变。

399 项 Java/协议检查通过；另有 290 项原始指令重放、4 组日志池补丁检查、6 例 PANI 分支重放和 2 组模块场景。详细边界见 [验证记录](service/docs/VALIDATION.md)。

本版本未实际拨号或发送短信。基线形成时尚未执行手机重启，开机恢复不属于此标签已通过的测试。用户后续已批准重启测试，应将实测结果与必要修复记入后续提交。

PANI 分析指向独立路径省略该头；没有证明最终 SIP 显式写入 GB。完整 IKE/IPsec/SIP 引擎源码并未提供。

### 已实测交付物

以下为 Git 初始化前已构建、交付并核对的文件；本次仅增加版本管理信息，没有重新构建或覆盖这些安装包。

| 文件 | SHA-256 |
|---|---|
| NewVoWiFi.apk | `b06064ffdb9e7f66a1fb28bad9260eb45fbb75032bc081355d10265c310ab57b` |
| NewVoWiFi-module.zip | `45cf11c11cb3e7f038a9dbfc888c748c059310571eb539dbf282e1380bd22092` |
| NewVoWiFi-source.zip | `b5ec6c14bd0bf5772d80a8c2262261da7a16e25487a57e52e15f3681595cfc24` |

已有源码 ZIP 不包含本次新增的 Git 元数据和根目录说明；当前完整受版本控制的内容以 Git 提交为准。
