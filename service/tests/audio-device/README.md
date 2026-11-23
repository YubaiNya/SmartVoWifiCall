# 同签名设备音频探针

仅测试已运行产品进程中的实际 `PolicyService.acquireAudio(Context, Consumer)` / `AudioLease.awaitReady()` / `close()`，不修改产品 Manifest。测试 APK 不声明权限、不调用引擎 RPC、不拨号、不启动 Activity、不采用 shell 权限，也不改变 appops、音频模式或路由。

## 构建

工作目录 `D:\NewVowifi`：

```powershell
python service\tests\audio-device\build_probe.py
```

使用现有 SDK build-tools 37.0.0、`service\deps\android-system.jar`、JDK 和 `app\build.gradle.kts` 指定的既有签名。工具或签名文件缺失即失败，不下载、不生成替代密钥。构建会校验签名及 zip 对齐；若已有产品 `dist\SmartVoWifiCall.apk`，还会比较签名证书。所有产物位于已忽略、源码打包工具也会排除的隐藏目录 `.build\`。资源链接使用现有 `platforms\android-37.0\android.jar`。

## 由设备管理者运行

确认没有真实通话，且产品进程、PolicyService 已运行。**必须使用 `--no-restart`**，避免 instrumentation 默认重启产品进程并干扰 IMS 注册。

```powershell
adb install -r -t D:\NewVowifi\service\tests\audio-device\.build\SmartVoWifiCall-audio-probe.apk
adb shell am instrument --user 0 --no-restart -w -r com.smartvowificall.ims.audiotest/com.smartvowificall.ims.audiotest.AudioProbeInstrumentation
```

未执行设备安装或运行。测试不会自行启动缺失的 PolicyService；存在真实通话、非 `MODE_NORMAL`、其他音频租约或缺少现有麦克风授权时失败退出。采集过程中也检查电话状态和模式，变化即停止。

探针约采集 1 秒 `VOICE_COMMUNICATION`、16 kHz、16-bit、单声道数据。读取使用 `READ_NON_BLOCKING`，独立采集任务的硬等待上限为 3 秒；超时会取消任务并由独立线程停止/释放 AudioRecord。清理线程最多等待 1 秒，超时明确报告失败，不继续重试。FGS 就绪使用产品现有 5 秒上限，属于采集之前的等待。

只输出 UID/进程重要性、模式、录音状态、配置中的 policy silence、样本数、非零样本数、峰值、耗时和资源释放结果；PCM 只在内存缓冲区中短暂存在，不写文件或输出内容。零输入或未知录音配置标记为 `INCONCLUSIVE`，不误称成功；系统拒绝标明失败阶段和异常类型。

重要结果字段：

- `foreground_ready`, `recording_state`
- `policy_state_known`, `policy_silenced_observed`
- `samples`, `nonzero_samples`, `peak`, `capture_elapsed_ms`
- `capture_verdict`, `failure_stage`, `failure_type`, `root_cause_type`
- `record_released`, `lease_released`, `pcm_stored=false`

## 测量边界

**同 UID 不等于普通后台资格。** Android 的 instrumentation 启动会记录调用者的后台 Activity/FGS 启动能力，可能赋予被测进程后台豁免；`--no-restart` 不会消除这个效应。因此本探针可以验证实际产品租约、录音和释放，但不能单独证明非 instrumentation 的后台通话路径具有同样资格。结果固定包含：

```text
instrumentation_affects_background_eligibility=true
background_policy_verdict=INCONCLUSIVE_INSTRUMENTATION_EXEMPTIONS
```

`PASS_CAPTURE_NONZERO` 仅表示本次 instrumentation 期间采集到非零样本，不能替代普通通话路径的实际验证。未使用任何系统权限绕过机制来试图消除这一边界。
