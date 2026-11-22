# 0.2.1-dev 通话生命周期修复

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
- 旧交付物保留于 `C:\ProjBackup\NewVoWiFi\20261122-134529-before-call-cleanup-0.2.1\`，SHA-256 已核验。
- 新包尚未安装到手机。手机恢复连接后，应先采集当前服务版本、活动会话计数、Telecom/音频状态与相关日志，再在原有不实际拨号、不发送短信限制内进行部署和状态检查。短信正常目前记为用户反馈；实际通话后双卡可呼出和号码显示仍须实机证据确认。
