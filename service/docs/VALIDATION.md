# 开发版验证记录

> 本文保留 **0.1.0-dev 的历史实机结果**，包括当时的 4 字节日志补丁。当前 0.2.0-dev 改为独立凭据读取、网络快照发布和 16 字节路径隔离，尚未真机复测；见 [独立版验证](STANDALONE_VALIDATION.md)。

设备验证日期：2026-11-22（北京时间）。设备为 Xiaomi 15，Android 17 / API 37，arm64；安装包版本为 `0.1.0-dev`。真机验证全程没有重启手机、实际拨号或发送短信，收费业务开关始终为 false。

## 结论

CTExcel 已通过独立引擎注册。2026-11-22 04:59:22 的首次成功日志为 SIP REGISTER → 401 → 鉴权 REGISTER → **200**，有效期 3600 秒。随后修复 Android MMTEL 就绪顺序，并重新安装和注册；独立的系统 API 探针返回：

```json
{"ok":true,"subId":19,"registrationState":2,"transportType":2,"featureState":2}
```

分别对应 **REGISTERED、WLAN、READY**。这是通过 `ImsMmTelManager` 查询 Android phone 服务得到的结果，区别于应用自己的状态文本。最终关闭、恢复原厂、重新开启后再次得到相同结果。

最终 APK SHA-256：`b06064ffdb9e7f66a1fb28bad9260eb45fbb75032bc081355d10265c310ab57b`。完整交付物校验值见 `dist/SHA256.json`。

## 真机检查

| 检查 | 已验证结果 |
|---|---|
| APK 与模块安装 | APK 更新安装成功；KernelSU 模块安装成功，活动脚本和引擎已同步；无需重启 |
| Root 与 socket | 应用专属 root 授权有效；普通应用域被拒绝的 socket 通过 root 中继连接，hello/getStatus 成功 |
| 实际网络选择 | 全部内核接口列出；通过 VPN Manager、接口、包名与 UID 复核 FlClash 所有者；选中 tun0 |
| 英国出口 | 现有 qianlink 配置的英国家庭出口；HTTP 探测返回 loc=GB、colo=LHR |
| 分卡策略 | 大陆归属卡（当前 subId 16）保持原厂；CTExcel（当前 subId 19）仅 MMTEL 接入 com.newvowifi.ims |
| 系统实际绑定 | com.android.phone 实际绑定 NewVoWifiImsService，目标槽 feature 为 MMTEL |
| ePDG / IMS | 独立 IPsec 隧道 CONNECTED，获得 P-CSCF；运营商 REGISTER 返回 200；引擎 REGISTERED |
| Android 注册 | 系统 API registrationState=2、transportType=2、featureState=2 |
| 不可用接口 | 选中 DOWN 接口后保留该选择、不替换为别的接口，并恢复目标卡原厂 IMS；更新 APK 后仍保留选择 |
| 恢复有效接口 | 重新选择 tun0 后重新建立隧道并注册；无需手工改系统绑定 |
| 停用与重启服务 | 停用后两个卡槽各 feature 的 provider 与安装前一致；自有规则和 XFRM policy 清理完成；再次启用注册成功 |
| 常驻模块脚本 | 已在当前启动周期运行，观察到唯一 supervisor；本轮没有重启验证开机挂载 |
| 隔离 | 原 SoftVoWiFi 引擎文件未覆盖；旧 NewVoWiFi 目录未覆盖；新应用数据与日志池独立 |
| 业务限制 | billableAllowed=false；未向实际引擎发送拨号、短信或音频启动操作 |
| 手机状态 | boot_id 始终一致；飞行模式开启、Wi-Fi 开启；未重启 phone、system_server 或手机 |

本轮遇到的真实阻塞及处理：

1. Root app_process 的部分 TelephonyManager 包装器不能提供有效身份。改用当前 Binder 身份、SIM 状态与归属 PLMN 交叉校验，海外卡必须取得当前 ICCID。
2. 创建配置的简化校验器只接受 Wi-Fi 模板。实际完整 `config-verify --strict` 支持 `iface:tun0`，已改用该校验器并再次核对当前 SIM。
3. 原日志池已满；独立副本仅替换日志池路径的 4 个数据字节，日志正常建立。
4. 原有代理实际为美国出口，ePDG 返回 9003。切换现有 qianlink 英国出口后进入成功的 ePDG 认证流程。
5. 同一 SIM 的旧会话留下 6 条 XFRM policy 和 3 条 IPv6 路由规则。完整原生属性、原始恢复负载及 SHA-256 已保存在本机回滚目录；只删除准确匹配的旧记录，其他路由逐条保持一致。没有读取 SA 密钥、删除全部 SA 或全局 flush。
6. 初版 MMTEL 在 onFeatureReady 中才发布 READY，导致框架一直等待。改为对象构造完成后先发布 READY，再等框架 listener 到位；真机系统 API 随后从 INITIALIZING / 未注册变为 READY / REGISTERED。参考 [AOSP ImsFeature 生命周期定义](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/ims/feature/ImsFeature.java)。

## PANI 与国家码结论

已证明的是独立服务在英国出口上注册成功。不能把它表述成“最终 SIP 国家码由 CN 改成 GB”。引擎对 `iface:tun0` 走非 Wi-Fi 分支，原始指令与调用链显示初始和鉴权 REGISTER 的 PANI 为空，设置函数移除旧 PANI 且不追加空头。该路径也不会回退生成默认 Wi-Fi PANI。

这是二进制调用链和离线指令重放结论；本轮没有采集带完整 SIP 头的线上报文。没有向基带写国家码，没有 Hook 系统 IMS，没有伪造坐标。英国出口检测仅证明代理出口国家。详见 [PANI 证据](ENGINE_0348_REGISTER_PANI.md)。

## 本地检查

| 项目 | 结果 | 边界 |
|---|---|---|
| 引擎协议、超时、隔离与业务开关 | 93 项通过 | 内存 transport |
| 通话生命周期与音频清理 | 39 项通过 | Android/socket/media 替身 |
| 归属卡策略、接口清单和回退计划 | 57 项通过 | 纯 Java |
| 身份与路径校验 | 23 项通过 | Host 校验函数 |
| 短信 PDU 编解码 | 135 项通过 | 含独立固定样例 |
| 短信适配、ACK、拼接及回调 | 52 项通过 | Android/engine 替身 |
| 模块 socket 所有权与进程状态 | 2 组场景通过 | 本地 fixture |
| 原引擎接口选择 | 290 项通过 | 原 AArch64 指令重放，外部边界为替身 |
| 日志池补丁 | 4 组通过 | 精确字节差异、ELF 不变、复现与输入限制 |
| PANI 分支 | 6 例通过 | 原 AArch64 指令重放，无线上报文捕获 |
| 交付物 | 构建、签名、ZIP、嵌入 APK、引擎校验通过 | 本机构建与哈希核对 |

合计 399 项 Java/协议检查；原始二进制重放和补丁校验单列，不视为运营商兼容性测试。真实通话、音频和短信收发按用户要求未测试。

## 保留的限制与证据

- 只有这张 CTExcel 完成真实运营商注册；其他海外卡适用自动选择策略，但尚无运营商逐个实测。
- 本轮未重启，因此持久模块下一次开机的挂载和自动启动尚未实测。当前运行的是已安装的数据分区 APK、实时模块脚本与 root 中继。
- 复用的完整 IKE/IPsec/SIP 引擎源码不可用；交付的新服务源码不等于完整协议栈源码。
- 验证模式关闭普通语音和短信能力上报；本轮不承诺系统拨号器已能实际完成收费业务。
- 私有原始证据在本机 `service/device-private/`，不进入源码包。备份在 `C:\ProjBackup\NewVoWiFi\20261122-031905-no-reboot-install\`，含完整性校验及恢复材料；手机上没有留下部署备份档案。

主要原始证据：`final-framework-status.json`、`final-service-status.json`、`final-stop-providers.json`、`final-stop-fallback.json`、`invalid-interface-fallback.json`、`engine-after-rule-cleanup.log`、`xfrm-cleanup-results.json`、路由清理前后原生记录、最终交付核对记录。SIM 身份、鉴权材料及完整敏感日志不对外展示。
