# 通话生命周期与音频修复记录

## 0.4.4-dev：远端挂断后的失败呼出、麦克风上行（2026-11-23）

### 真实故障链

用户报告对方挂断后无法再呼出，以及下行声音和 DTMF 正常、对方听不到本机。设备实际运行 0.4.2，尚未安装此前的 0.4.3 节能包。本次保存了私有日志、音频策略、录音权限和线程栈，未主动拨号或发短信。

- `02:25:48.910`：上一通收到 `onCallTerminated reasonCode=510`，Telecom 记录 REMOTE 断开。这证明该通远端挂断事件并非完全漏报。
- `02:25:53.763`：下一通收到 `onCallInitiating`；`02:25:54.030` 收到 `onCallStartFailed reasonCode=131`。之后系统保留 DIALING，挂断请求无效；独立引擎探针则报告 `callStage=IDLE`。
- 实机 `telephony-common.jar` 方法 `ImsPhoneCallTracker$8.onCallInitiating` 明确清空 `mPendingMO`；`onCallStartFailed` 在此后缺少 pending 呼叫且不是特定重拨原因时不走正常断开分支。原适配器按“是否已接通”选择 StartFailed，遗漏了“已 initiating、尚未接通”的阶段。
- 修正依据的是已经向 framework 发送的回调：initiating/progressing/initiated 后均用 Terminated；尚未通知开始的失败才用 StartFailed。保持真实原因码和不回退蜂窝的边界，未伪造接通、删除引擎锁或修改系统通话框架。
- 同时将拨号前 `radio-check` 从写事务锁中分离：此路径仅读取当前 SIM、租约和 modem 状态，并在读取前后复核身份及射频，避免与后台 `radio-hold` 的非阻塞写锁争用产生无谓失败。写操作仍加锁且要求通话空闲。该并发风险由代码确认，但现存日志不足以证明它就是该次 131 的具体来源；12 次只读实机核验均为 CELLULAR_RADIO_OFF。

### 上行音频证据与实现

- 录音权限已授予，但 AppOps UID 模式为 foreground。音频策略与 AudioFlinger 记录了该应用的采集会话被系统静音；DTMF 属于独立信令，不能证明麦克风 PCM 正常。
- 原先只在 `onFeatureReady()` 调用 `setCallAudioHandler`，radio 日志显示无有效通话时该通知被丢弃。实机 SDK 确认 `AUDIO_HANDLER_ANDROID=0`，对应 framework VoIP 音频路径。现在在 initiating/progressing/initiated 回调及来电创建后重新通知，避免停留在基带通话音频模式；不自行设置全局 `AudioManager` 模式或路由。
- 录音前取得 `PolicyService` 的 microphone 前台租约，等待类型真正生效再建立 PCM 采集；只在实际音频期间显示低重要性无声通知，最后租约释放后移除。取消/超时/权限拒绝均有界失败并释放资源，不绕过系统麦克风限制。
- 音频失败先通知通话，再异步关闭可能阻塞的传输/设备；诊断只记录帧数、电平和 policy-silenced 状态，不保留原始音频或号码。

### 验证与边界

- `python service\tests\engine\run_feature_tests.py --baseline 4ff9210 --case remote-redial` 按预期失败于 initiating 后无法正确终结；修复后的同一场景通过。完整 Feature 73 项、通话/音频 77 项、relay 12 项检查通过，包含无应答远端取消、远端挂断后再次失败、再次拨号可用、前台租约并发和取消清理。
- Release 编译通过并已安装 0.4.4，保留原有配置及 0.4.3 的节能/UI 修改。升级前再次确认引擎 IDLE，才清理残留 app/Telecom 会话；升级后两卡 `mCallState=0`，独立引擎 `REGISTERED / IDLE`，无旧本地会话。
- 最终包经过一次无通话重启验证：`service.sh` 由系统开机流程启动（PPID=1），网络快照发布器为其子进程；安装状态 `APP_CURRENT`，独立注册自动恢复。当前安装 APK 与开机模块 APK 的 SHA-256 均为 `8cabe9d3edc4f1c81264999448a9bede7ef14d79a9a5bb5f3ede2017bd6c5c8a`，模块包 SHA-256 为 `6ccd028985b0a011b1c4ec9594f458963c98025ed105f47f84e6f9f1d68ae72f`。待机应用通知数为 0，临时探针 APK 与上传文件已清理。
- 证据与备份分别位于 `C:\ProjBackup\SmartVoWifiCall\20261123-0233-call-audio` 和 `C:\ProjBackup\SmartVoWifiCall\20261123-before-call-audio-044`，不进入 Git/源码包。
- 同签名设备 instrumentation 使用实际应用 UID `10705`，仅在电话空闲、音频 MODE_NORMAL 时申请真实麦克风前台租约并非阻塞采集约 1 秒；未拨号、未访问引擎、未保存 PCM、未改全局音频模式或 AppOps。结果：`foreground_ready=true`、`recording_state=3`、14080 个样本中 1146 个非零、峰值 23、`policy_silenced_observed=false`，录音与租约均释放，前后 mode=0。测试 APK 已卸载。该结果证明真实采集链可产生非零 PCM，但 instrumentation 可能带来后台豁免，因此明确标记 `INCONCLUSIVE_INSTRUMENTATION_EXEMPTIONS`，不将其当作普通后台通话许可证明。
- 探针源代码和构建入口位于 `tests/audio-device`，输出在隐藏 `.build` 目录，不进入源码包；运行前必须确认没有真实电话或正在使用的音频设备。
- 修复版稳定空闲 12 秒采样的 `rootCommandCount` 保持 90，IMS 仍 REGISTERED；这是短时调度验证，不是电耗百分比测量。
- 对方真实听到语音、真实远端挂断后连续呼出仍须实际双方通话验收；静态证据、内存测试和设备电平探针不能替代该结论。

## 历史：0.2.1-dev 通话生命周期修复

日期：2026-11-22（北京时间）。应用/模块 versionCode=3。

## 报告与证据边界

用户报告：短信已确认正常；接听并结束一次电话后，两张卡都不能呼出；来电显示未知号码。

本轮 USB ADB 列表为空，无线 ADB 也未发现设备。因此无法读取发生问题时的 Telecom、音频或引擎日志，不能确认手机实际卡在哪个分支。本次针对源码和协议中可复现的缺陷修复，不能把本地通过等同于手机症状已消除。全程没有实际拨号、发送短信、接听或挂断手机上的电话。

## 已确认并修复

1. **来电号码缺少展示状态。** 旧代码设置了 `EXTRA_OI`，但没有 `EXTRA_OIR`。Android 会将默认 OIR 解释为未知。当前对引擎给出的有效公开号码设置 `OIR_PRESENTATION_NOT_RESTRICTED`；缺失或匿名值仍保持未知，不从其他字段推测号码。依据：[AOSP ImsPhoneConnection](https://android.googlesource.com/platform/frameworks/opt/telephony/+/refs/heads/main/src/java/com/android/internal/telephony/imsphone/ImsPhoneConnection.java)、[ImsCallProfile](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/telephony/java/android/telephony/ims/ImsCallProfile.java)。
2. **有效终结事件被忽略。** 原生来电用 `incomingCall` 携带 `state=ENDED`，去电 `callEnded` 没有 state；旧适配器仅触发后续 getCallSnapshot。如果快照暂时返回 2006/3002，结束回调就可能遗漏。当前核对 binding、callId、epoch、revision 后立即结束匹配会话，继续使用快照校正。静态与指令重放证据见 [协议报告](CALL_PROTOCOL_FINDINGS.md)。
3. **共享状态线程同步等待媒体关闭。** 旧 endWith 在发送终结回调前调用 PcmAudio.close。缓慢或阻塞的 pipe/AudioRecord 关闭会占住共享 worker，使 Android 无法及时收到结束通知。现在先释放本地会话所有权并异步处理音频关闭，终结回调不等待媒体 I/O；audioStop 的调度也提前到本地关闭之前，保留启动/停止先后约束。
4. **缓冲中继管道的取消顺序。** 原代码先关闭子进程输出管道再 destroy；缓冲输出关闭可能等待写线程。现在先取消所属 relay 进程，再关闭管道，重复关闭只执行一次。root relay 收到 stdin EOF 后关闭完整 socket。依据：[Android UNIXProcess](https://android.googlesource.com/platform/libcore/+/refs/heads/android17-release/ojluni/src/main/java/java/lang/UNIXProcess.java)；本地测试验证取消先于 pipe close，不能据此宣称实际手机已经发生该死锁。
5. **丢失终结事件后的旧会话。** 新快照已指向另一通话时，旧代码仍保留原 callId。当前单通话权威快照会结束已过期的本地会话；IDLE 清理全部已绑定会话。

来电通知改用 Android 17 的三参数 notifyIncomingCall，并接入返回的 listener。旧兼容接口仍可能经框架 setListener 工作，因此不把“使用旧接口”单独列为已证实根因。依据：[AOSP MmTelFeature](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/telephony/java/android/telephony/ims/feature/MmTelFeature.java)。

两卡同时受影响与共享 worker/Android Telecom 残留活动会话相符，但没有手机日志，仍属于合理解释。原生 call.lock 有正常释放路径，锁文件存在不等于锁未释放，而且它不会直接锁住原厂 IMS；本次没有删除锁文件或清空系统电话状态。

## 本地复现与回归

新增 runner 编译真实 NewMmTelFeature、NewCallSession、PcmAudio，替换 Android、媒体设备与引擎 transport。旧版来源使用 `git show v0.2.0-dev:...`，不切换或覆盖工作区。已分别运行：

```powershell
python service/tests/engine/run_feature_tests.py --baseline v0.2.0-dev --case caller
python service/tests/engine/run_feature_tests.py --baseline v0.2.0-dev --case terminal
python service/tests/engine/run_feature_tests.py --baseline v0.2.0-dev --case blocked
python service/tests/engine/run_feature_tests.py --baseline v0.2.0-dev --case reconcile
python service/tests/engine/run_feature_tests.py
```

前四条按预期失败，分别复现展示状态错误、快照失败时缺终结回调、worker 被关闭音频阻塞，以及新快照未清旧会话。修正版 38 项检查通过，覆盖：接听后结束、终止事件无 state、快照 2006、重复终止、旧版本/错误 callId 排除、后续模拟呼出、阻塞媒体关闭期间 worker 和回调仍可运行、隐私号码不公开。

其他本轮通过检查：39 项通话/音频、12 项 relay 关闭、93 项协议、52 项短信适配、57 项策略、181 项 SIM 凭据、27 项网络快照、23 项路径/身份，总计 **522 项**。模拟呼出、应答与音频操作仅作用于内存替身。无真实 SIM 通话或短信操作。

## 交付与剩余验证

- APK SHA-256：`49c33519f1a164df70f8b597164ff154207a9a809a0282ea88b306a7fe249f63`
- 模块 SHA-256：`004b2bb399671fb3e625786841590a5b47c684b505ca92802d5ac27b7a464f96`
- 协议引擎未修改，仍为 0.2 独立路径版本；没有修改短信适配实现。
- 旧交付物保留于 `C:\ProjBackup\SmartVoWifiCall\20261122-134529-before-call-cleanup-0.2.1\`，SHA-256 已核验。
- 新包尚未安装到手机。手机恢复连接后，应先采集当前服务版本、活动会话计数、Telecom/音频状态与相关日志，再在原有不实际拨号、不发送短信限制内进行部署和状态检查。短信正常目前记为用户反馈；实际通话后双卡可呼出和号码显示仍须实机证据确认。
