# 0.2.2-dev 单包安装与覆盖升级

日期：2026-11-22（北京时间）。应用与模块 versionCode=4。

## 用户操作

只需在模块管理器覆盖刷入 **NewVoWiFi-module.zip** 并重启。模块自带 APK，会自动处理首次安装与旧版覆盖，不再要求单独安装 APK；现有配置保留。从 0.1.0 可以直接升级，不需要逐个安装中间版本。

已核对实际 0.1.0 与 0.2.2 APK：包名均为 `com.newvowifi.ims`，签名证书 SHA-256 相同，versionCode 从 1 升至 4，模块 ID 一直是 `newvowifi`。

## 实现

- `app-install.sh` 是刷入脚本和开机脚本共同使用的安装流程。
- `customize.sh` 在正常系统中尝试安装；Recovery 或暂时无法完成时，明确提示开机自动处理。
- 使用 `pm install -r --user 0 -S <size> -`，由 root 将随包 APK 从 stdin 传入，系统服务无需打开私有 `/data/adb` 路径，不产生临时安装 APK 副本。
- 使用相同包名/签名的覆盖安装语义。没有卸载、清数据、强制降级、拷贝偏好覆盖或删除运行目录；应用开关、各 SIM 接口选择、计费开关和 SIM 配置均沿用原值。
- 已安装的 APK 与随包 APK 哈希相同则跳过。安装返回成功之后，再查 PackageManager 当前路径并比对完整 APK 哈希；仅看到 Success 字样不算验收。
- 替换现有 APK 前，通过本包的只读 `install-check` 查询是否空闲。活跃或未知通话时不终止应用，留待开机/空闲时自动补装。该入口只输出判断和错误码，不输出 SIM 身份。
- 开机脚本对临时故障每 10 秒重试，通过安装和哈希核对后才继续它的网络发布及前台服务启动。同次启动成功后不再循环安装。
- 签名冲突和系统拒绝的降级不使用“卸载重装”补救；刷入时明确失败，开机遇到则记录状态并停止该启动流程，旧应用数据保留。
- 最后一次开机安装结果保存在 `/data/adb/newvowifi_ims/app-install.status`，仅含结果码，不保存原始 PackageManager 错误正文。

命令与生命周期依据：[AOSP PackageManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) 的 stdin/size/user/替换参数，以及 [Magisk 模块指南](https://github.com/topjohnwu/Magisk/blob/master/docs/guides.md) 的 customize 与 late-start service 流程。目标是当前 user 0 的 Android 17 设备，不扩展到多用户安装管理。

## 本地验证

`python service/tests/policy/test_standalone_module.py`：**20 个测试通过**。执行真实 `app-install.sh`、`customize.sh` 和开机循环，替换 PackageManager、Android 命令和私有路径；不连接手机。

覆盖首次安装、旧 APK 替换、相同 APK 跳过、签名冲突、降级拒绝、Failure 但返回码为 0、Success 但实际 APK 未变、APK 缺失、活跃/未知通话、正常刷入、Recovery 延期、平台限制、开机失败后自动重试、空闲后安装、安装失败不继续启动模块服务，以及 5 个脚本的语法检查。

每个场景都保存模拟的应用偏好与 SIM 配置哨兵，并核对未改变；流式安装还核对收到的字节数与完整 APK 内容。这证明脚本的流程和“不重置配置”行为，不替代真实 PackageManager 更新测试。

相关回归：57 项策略、181 项凭据、27 项网络快照、23 项身份/路径，以及既有 2 组模块场景通过。APK 构建、签名、模块内嵌 APK、独立引擎哈希和 ZIP 内容检查通过。

## 交付与边界

- APK SHA-256：`2651eb7733a7a7f6a560478f7005482d7a4eef437ef1a24b2c99510ded40bcac`
- 模块 SHA-256：`7c94ef0869cfaf9cec62c598dbdf72fdb3a5086a0c4235b2066782290f257d02`
- 完整校验值见 `dist/SHA256.json`。0.2.1 包已备份至 `C:\ProjBackup\NewVoWiFi\20261122-140856-before-auto-apk-0.2.2\` 并验证哈希。
- 包含 0.2.1 的通话结束与来电显示修复，不改其协议逻辑；安装器保留已有计费开关，不为了升级而改变用户设置。
- **本轮手机仍离线，未真实刷入、重启或验证旧配置在该手机上保留。** 之前的注册记录不能当作本版升级成功证明。没有实际拨号或发送短信。
- 首次使用仍需 root 授权和可用的用户所选网络；已有安装继续使用原设置。本次解决的是单包 APK 安装/升级，不把未完成的真机验收标成通过。
