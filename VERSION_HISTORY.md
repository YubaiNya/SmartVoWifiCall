# 版本记录

## v0.2.2-dev — 2026-11-22

应用与模块 versionCode 升为 4。仅需刷模块 ZIP 并重启，APK 首次安装和覆盖升级由模块自动处理。

- 安装时使用 PackageManager 流式覆盖安装；开机核对安装 APK 与模块内 APK 的 SHA-256，相同则跳过，避免反复重装。
- 同包名、同签名更新，不卸载、不清数据、不重置开关、接口选择或 SIM 配置；实测文件校验确认签名与 0.1.0 相同。
- 活跃/未知通话或临时安装服务故障自动延期，开机重试；签名不符/降级被拒绝时保留旧应用并报告失败。
- 模块的网络发布和前台服务启动在 APK 校验成功后进行；状态写入 `/data/adb/newvowifi_ims/app-install.status`。
- 新增安装决策模式仅返回通话是否空闲，不将 SIM 身份写入安装输出。

20 个真实 shell 安装/开机流程的替身测试通过，覆盖首次、0.1 式旧版覆盖、同包跳过、配置保留、失败读回、通话延期、Recovery、签名失败及开机重试；另有 288 项策略/凭据/路径检查和模块既有场景通过。本轮设备离线，未执行真实安装或重启；没有实际拨号或发送短信。详见 [自动安装验证](service/docs/AUTO_APK_VALIDATION.md)。

此前 0.2.1 交付物已备份并核验至 `C:\ProjBackup\NewVoWiFi\20261122-140856-before-auto-apk-0.2.2\`。

## v0.2.1-dev — 2026-11-22

应用与模块 versionCode 升为 3。用户反馈短信正常，但接听并结束一次电话后两张卡均无法呼出，来电显示未知号码；这是用户实测报告，本轮没有取得对应手机日志。

- 精确消费有版本的 `incomingCall.state=ENDED` 和 `callEnded`，后续快照报错也能通知 Android 结束匹配会话；拒绝旧 epoch、旧 revision 与其他 callId 的事件。
- 将音频资源关闭移出共享通话线程；audioStop 不再排在可能阻塞的本地关闭之后，仍保持 audioStart 先于 audioStop。
- root 中继先取消子进程再关闭缓冲管道，stdin EOF 时关闭整个 socket，避免半关闭残留。
- 新权威快照可清理已不属于当前通话的旧会话。
- 公开号码设置 OI 和明确的 OIR；缺失、匿名号码维持未知。使用 Android 17 返回 listener 的来电通知接口，显式接回框架回调。

新增 38 项真实 feature/session/audio 代码集成替身检查和 12 项 root 中继关闭检查通过。旧标签 `v0.2.0-dev` 在号码展示、终止事件、阻塞音频关闭、旧会话清理四组场景中均复现失败，修正后通过；本轮共执行 522 项 Java/协议模拟检查。手机 ADB 及无线 ADB 均不可用，新包未安装、未实际拨号或发送短信，不能声称手机症状已消除。详见 [修复验证](service/docs/CALL_FIX_VALIDATION.md)。

此前 0.2 安装包已备份并核验至 `C:\ProjBackup\NewVoWiFi\20261122-134529-before-call-cleanup-0.2.1\`。

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
