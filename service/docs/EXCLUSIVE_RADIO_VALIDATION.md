# 0.2.5-dev 单卡蜂窝互斥与禁止普通呼叫蜂窝回退

日期：2026-11-22（北京时间），versionCode=7。

## 已确认的需求

用户明确要求：海外卡的 Wi-Fi Calling 与蜂窝不可同时启用；开启 Wi-Fi Calling 时禁止该卡获取蜂窝信号，出现 Wi-Fi Calling 图标或处于 Wi-Fi Calling 模式时，普通呼叫不可用必须直接报错失败，绝不自动回退到蜂窝。大陆卡保持原厂蜂窝与 IMS 不受影响；保留所选真实 TUN 出口限制、配置保留与单 ZIP 自动升级，验证过程不拨号、不发送短信。

## 关键实现与真机发现

1. **单卡蜂窝协议栈互斥（`ExclusiveRadio` + `RootIdentityMain`）**
   - 原计划使用 `ITelephony.setRadioForSubscriber(subId, false)`；在 Xiaomi 15 / Android 17（Qualcomm DSDS）实测发现，尽管 Java 仅向目标 `Phone` 发送 `RIL_REQUEST_RADIO_POWER(false)`，基带仍将整机切入共享 LPM，导致卡槽 0（中国移动）与卡槽 1 同时触发 `UNSOL_RADIO_STATE_CHANGED RADIO_OFF`。
   - 改用单卡协议栈开关 `ITelephony.enableModemForSlot(phoneId, false/true)` 与状态读回 `isModemEnabledForSlot(phoneId, "root", null)`：在真机上发送 `RIL_REQUEST_ENABLE_MODEM` 仅作用于目标卡槽，约 16 ms 返回；目标卡进入 `NOT_REG_MT_NOT_SEARCHING_OP`（不驻网、不搜网），大陆卡保持 `REG_HOME` LTE 不中断，目标卡 SIM 状态与 QRTR UIM 鉴权通道保持可用。
   - 关闭蜂窝前先落盘持久化租约 `/data/adb/newvowifi_ims/radio/<slot>.json`（记录原始状态与 SIM 身份），确认 `isModemEnabledForSlot == false` 后才允许绑定独立 IMS、写入配置、启动引擎、上报 `REGISTERED` 或放行普通呼叫。
   - 取消普通业务（注册丢失或 TUN 断开）时的系统 IMS 回退；仅在用户关闭 Wi-Fi Calling 开关、禁用模块或卸载模块时，按“恢复系统 IMS → 停止引擎 → 恢复单卡协议栈”的顺序释放租约。若期间开启了飞行模式，保留租约待飞行模式关闭后恢复。
   - `app_process` 非 AMS 注册应用进程，调用 `Settings.Global.getInt(ContentResolver)` 会抛出 `SecurityException`；飞行模式状态改由 `/system/bin/settings get global airplane_mode_on` 读取并 fail-closed 校验。紧急回拨模式检查改用 `ITelephony.getEmergencyCallbackMode(subId)` 直接 Binder 调用。

2. **禁止普通呼叫 CSFB 回退（`NewMmTelFeature` + `NewCallSession` + `RegistrationRouting`）**
   - `NewMmTelFeature.shouldProcessCall` 仅在明确识别为紧急号码时返回 `PROCESS_CALL_CSFB`；普通号码及紧急号码识别异常均返回 `PROCESS_CALL_IMS`，不可用时在 `NewCallSession` 内直接以失败结束，不转交蜂窝。
   - `NewCallSession.start` 拨号前再次实时调用 `verifyRadioOff` 确认目标卡蜂窝协议栈关闭且处于 `REGISTERED` 状态，否则拒绝下发引擎 `dial`。
   - `RegistrationRouting.synchronize` 在失联/掉注册时保持独立 IMS provider，不再调度系统 IMS 回退。

3. **实机排查并顺带修复的四处运行缺陷**
   - **Android 17 自动安装参数**：`app-install.sh` 原 `pm install -r --user 0 -S "$nv_size" -` 在 Android 17 上报错 `IllegalArgumentException: Unknown option -`；移除多余的 `-` 后直接从 stdin 流式安装返回 `Success`。
   - **冷启/升级后 `ImsResolver` 空特性缓存**：实测 `pm install -r` 覆盖升级后，`ImsResolver` 首次动态查询发生在本进程首次 `root.snapshot()` 完成前，返回空特性 `[]` 后解绑并回落到原厂 `org.codeaurora.ims`。现于 `RuntimeController.tick()` 在 `off == true` 且目标槽尚未挂载 `NewMmTelFeature` 时触发一次带 15 秒冷却的 `root.bind(sim)`，促使 `ImsResolver` 重新查询并挂载 `NewMmTelFeature`。
   - **`control.sh` toybox `tr` 死循环**：在 0.2.4 真机现场观察到 `tr '\000' ' ' < /proc/$pid/cmdline` 在目标进程中途退出后陷入 98% CPU 死循环并永久占用 `.lock`（导致 `ENGINE_BUSY`）；改为 `dd if="/proc/$pid/cmdline" bs=4096 count=1 2>/dev/null | tr '\000' ' '` 避免读错误空转。
   - **`uninstall.sh` 释放重试**：卸载脚本为 `radio-release-all` 增加重试循环，避免异步/短暂 `RADIO_RESTORE_PENDING` 导致首次退出。

## 本地验证

- `tests/policy/run_tests.py`：策略/网络 **89**、注册/provider **27**、单卡蜂窝互斥 **28**、SIM 凭据 **181**、网络快照 **27**、身份/路径 **23** 项全部通过。
- `tests/engine/run_tests.py`：**93** 项通过。
- `tests/engine/run_call_tests.py`：通话/音频 **39** + root 中继 **12** 项通过。
- `tests/engine/run_feature_tests.py`：**51** 项通过（含新增的普通呼叫禁 CSFB、紧急号码保留 CSFB、未注册/蜂窝未关闭/实时核验失败阻断拨号检查）。
- `tests/policy/test_module_control.py`：4 组模块控制与脚本语法检查通过。
- `tests/policy/test_standalone_module.py`：**21** 个真实 shell 场景通过（含回滚时 `radio-release-all` 重试与 Android 17 无 `-` 的 `pm install` 参数校验）。
- `tests/engine/test_native_selectors.py`：**290** 项原生指令重放通过；`tests/engine/test_isolated_log_pool.py`：**5** 项通过。
- `tests/sms/run.ps1` + `run-adapter.ps1`：**135 + 52** 项通过。
- `tools/verify_artifacts.py`：APK 签名、DEX、ZIP、脚本 LF 与引擎哈希校验通过。

## 真机验证结果（Xiaomi 15 / Android 17，无拨号、无短信）

- 验证前已将 0.2.4 交付物备份至 `C:\ProjBackup\NewVoWiFi\20261122-195200-before-exclusive-radio-0.2.5\` 并逐项核验 SHA-256。
- 通过 `/data/adb/ksu/bin/ksud module install` 刷入最终 `NewVoWiFi-module.zip`：自动完成随包 APK 覆盖安装并输出 `- NewVoWiFi APK installed/verified; existing configuration retained.`，原有配置保留。
- 启动 `PolicyService` 后实测 Wi-Fi Calling 模式：
  - 诊断状态为 `Wi-Fi Calling 模式；蜂窝已关闭`；卡槽 1（45403 海外卡）`radioOff=true`、`featureAttached=true`、`ims=REGISTERED`、`tunnel=CONNECTED`、`independentProvider=true`。
  - 系统侧独立探测 `FrameworkProbeMain 4` 返回 `{"ok":true,"subId":4,"registrationState":2,"transportType":2,"featureState":2}`（Android 系统确认 `REGISTERED / WLAN / READY`）。
  - 基带状态确认：卡槽 1 `modemEnabled=false`、`NOT_REG_MT_NOT_SEARCHING_OP`，租约位于 `/data/adb/newvowifi_ims/radio/1.json`；卡槽 0（46007 中国移动）全程保持 `modemEnabled=true`、`mVoiceRegState=0(IN_SERVICE)`、LTE 在网，IMS 覆盖为空。
  - 在卡槽 1 `modemEnabled=false` 下冷重启 `vowifi-engine-arm64 --sim-mode=qrtr`，完整通过 QRTR EAP-AKA 鉴权、IKEv2 与 SIP REGISTER。
- 实测退出与重新接管闭环：
  - 按顺序执行 `ims-restore-all` → `control.sh stop` → `radio-release-all`，分别返回 `IMS_RESTORED`、引擎停止、`RADIO_RELEASED`；卡槽 0/1 IMS 覆盖均为空，租约目录清零，卡槽 1 恢复 `modemEnabled=true`、卡槽 0 全程 `modemEnabled=true`。
  - 重新启动 `PolicyService` 后自动重新关闭卡槽 1 蜂窝协议栈、绑定 `com.newvowifi.ims`、挂载 `NewMmTelFeature` 并完成注册；`FrameworkProbeMain 4` 再次返回 `registrationState=2, transportType=2, featureState=2`，卡槽 1 `modemEnabled=false`、卡槽 0 `modemEnabled=true`。

## 尚未实测的边界

- 本轮采用免重启热同步验证模块脚本，尚未执行整机冷重启验证开机自启闭环及真实卸载流程；模块更新已通过 `ksud module install` 写入 `/data/adb/modules_update/newvowifi`，重启后将由 KernelSU 正常切换。
- 尚未实测开启 Wi-Fi Calling 状态下主动断开 `tun0` 观察界面与系统图标同步变化。
- 按要求未进行真实拨号或发送短信。紧急号码仍允许系统按紧急呼叫规则走 `PROCESS_CALL_CSFB`。
