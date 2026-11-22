# NewVoWiFi

独立 Android `ImsService` 开发版，参考 SoftVoWiFi 的引擎协议。源码位于 `app/src/main/java/com/newvowifi/ims/`。2026-11-22 已在 Xiaomi 15 / Android 17 上完成无需重启的安装，CTExcel 实际收到 SIP 200，Android 系统 API 确认 `REGISTERED / WLAN / READY`。详情见 [验证记录](docs/VALIDATION.md)。

## 当前实现

- 按当前 SIM 的归属 PLMN 判断：460、461 保留系统 IMS；其余有效归属 PLMN（包括港澳台）由用户开启自动接管。身份未就绪时不接管。
- 按 SIM 身份保存接口选择，列出全部内核网络接口；消失的选择保留为失效状态。能核实 VPN 所有者时，首次默认选择 FlClash 的接口。
- 每卡独立引擎会话、配置和系统 MMTEL 绑定。接口失效、换卡、关闭服务时，在无通话状态恢复系统服务。
- 系统注册回调来自独立引擎的真实 `getStatus.ims == REGISTERED`，不会引用系统原有 IMS 注册结果。
- 接入 Android MmTel、普通语音会话、PCM 音频及文字短信适配。通话和发送短信开关默认关闭，RPC 层再次检查；设备验收不得开启或触发收费动作。

新写的是 Android 系统服务、分卡策略、接口选择、生命周期和协议适配；底层复用用户手机现有 **SoftVoWiFi 0.3.48 arm64 引擎二进制**，其完整源码不在这个项目中。独立副本只改动日志池路径的 4 个数据字节，网络、鉴权和国家码逻辑没有改动；原始引擎保留。见 [日志池补丁](docs/ENGINE_ISOLATED_LOG_POOL.md)。

SIM 配置生成依赖已安装的 `com.vowifi.sw.ProvisioningIdentityMain`，每次重新读取并核对当前 SIM 身份。应用数据独立保存到 `/data/adb/newvowifi_ims`，日志池元数据保存到 `/data/adb/nvwims`；旧 `/data/adb/newvowifi` 和原 SoftVoWiFi 数据继续保留。

本机选择经所有者核实的 FlClash `tun0`，并使用现有 qianlink 配置的英国出口。出口探测返回 GB；这不代表 SIP 中写入了 `country=GB`。二进制分析表明 `iface:tun0` 的非 Wi-Fi 分支省略 PANI，实际注册成功已经绕过原厂 PANI 选择路径。没有伪造 GPS 坐标。

## 构建

在当前 Windows 开发机运行：

```powershell
python D:\NewVowifi\service\tools\build.py
```

需要本机 JDK 21、Android build-tools 37.0.0、Android 37 资源 SDK、`deps/android-system.jar` 和 `deps/vowifi-engine-arm64-isolated`。源包保留原始引擎，可运行 `tools/isolate_engine_log_pool.py` 复现独立副本。本版以当前手机 Android 17 / API 37 为目标，未声明兼容旧版系统。依赖路径、版本及来源记录在 `deps/`；脚本通过 `javac → d8 → aapt2 → zipalign → apksigner` 生成 APK 与 KernelSU/Magisk 模块。签名文件仅供本机开发，不放入源码包。

输出：`dist/NewVoWiFi.apk`、`dist/NewVoWiFi-module.zip`、`dist/SHA256.json`。再运行 `tools/package_source.py` 生成源码与固定依赖包。模块使用独立 ID `newvowifi`，不覆盖原 `vowifi_sw`。

本次实机采用数据分区 APK、该应用的 root 授权和模块脚本即时运行，已证实不必等待系统应用扫描即可绑定 IMS。普通应用域无法直连引擎 socket 时，使用获授权的 root 子进程中继。没有放宽全部普通应用的 SELinux 权限。KernelSU 的持久模块已安装并暂存；本轮没有重启，因此下次启动后的系统挂载路径尚未实测。

## 本地验证

```powershell
python D:\NewVowifi\service\tests\engine\run_tests.py
python D:\NewVowifi\service\tests\policy\run_tests.py
& D:\NewVowifi\service\tests\sms\run.ps1
& D:\NewVowifi\service\tests\sms\run-adapter.ps1
python D:\NewVowifi\service\tests\engine\run_call_tests.py
python D:\NewVowifi\service\tests\policy\test_module_control.py
python D:\NewVowifi\service\tests\engine\test_native_selectors.py
python D:\NewVowifi\service\tests\engine\test_isolated_log_pool.py
python D:\NewVowifi\service\tools\verify_artifacts.py
```

引擎测试使用内存 transport；模拟拨号/短信数据不会连接真实引擎。PDU 测试含独立 AOSP 固定字节样例，非仅自编码后自解码。真实设备状态、系统绑定和注册必须另行记录；编译及 host 测试通过不代表运营商注册成功。

原始指令重放和 ELF 校验另外需要 Python 的 `unicorn`、`pyelftools`、`capstone`；这些依赖仅用于离线二进制检查，不是 APK 的运行依赖。

## 明确的功能边界

- 本版只处理 IWLAN 普通语音及引擎可表达的文字短信；不声明支持视频、UT、会议、保持/恢复、原始二进制短信或紧急 IMS。
- 早期媒体、回铃及持续按住 DTMF 尚未实现；当前 DTMF 为有界音。入站短信的待确认和去重状态尚未持久化。
- 若目标槽已经有来源不明的非空运营商 MMTEL 覆盖，本版保留原配置并显示冲突，不会盲目覆盖。因此当前自动规则不等于已经证明适用所有运营商配置。
- 接管粒度是 Android 的每卡 MMTEL feature，不是按每次呼叫的 Wi-Fi/蜂窝传输单独替换。大陆卡的绑定不变；海外卡在独立服务期间不能同时由原厂提供另一套 MMTEL。
- 不支持的 PDU 语义（如二进制、特殊 DCS、送达回执请求及非默认有效期）会明确失败，不能静默变成普通短信。引擎接受发送不是送达证明。
- 音频、真实呼叫建立及运营商短信收发尚不能因本地模拟测试而宣称通过。按用户要求，真机验收不拨号、不发送短信。
- 目前运营商实测仅覆盖这张 CTExcel；自动分卡规则已实现，不等于所有海外运营商均已验证。验证模式关闭普通语音/短信能力上报，系统界面不一定显示可拨号的 Wi-Fi Calling 标识。
- 原 SoftVoWiFi 与本服务不能同时使用同一 SIM 的引擎 socket 和内核会话。此次首次接管前清理了经当前 SIM、隔离 ID、时间戳和完整属性核对的旧策略及路由；产品没有加入全局清空路由或 XFRM 的动作。

设备配置、SIM 身份、私有日志不包含在交付源码包或用户报告中。安装前的回滚记录必须直接保存在 `C:\ProjBackup\NewVoWiFi\<timestamp-purpose>\`，核验 SHA-256 后再实施部署。
