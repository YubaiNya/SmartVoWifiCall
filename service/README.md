# NewVoWiFi

独立 Android `ImsService` 开发版，当前版本 **0.3.0-dev**。模块内包含 APK、VoWiFi 引擎、内置 `mihomo` Clash 内核（`wfctun0`）、SIM 配置生成与实时网络快照，无需安装外部 SoftVoWiFi 或外部 VPN 应用。源码位于 `app/src/main/java/com/newvowifi/ims/`。

0.3.0 内置 `MetaCubeX/mihomo v1.19.34` 内核，在**不开启 Android VPN、不代理手机其他任何流量**的前提下，通过独立 `wfctun0` 与仅针对 `oif wfctun0` / `from 198.19.0.1/32` 的路由表 `209` 全局代理 Wi-Fi Calling 流量（mihomo 上游走系统路由）；App 内支持订阅链接拉取、YAML 导入、节点切换与测速，同时保留 `/data/adb/newvowifi_ims/clash/profile.yaml` 本地维护与外部 TUN 接口下拉选择。详见 [内置 Clash 验证记录](docs/BUILTIN_CLASH_VALIDATION.md)。

0.2.5 按用户要求实现海外卡 Wi-Fi Calling 与蜂窝二选一互斥：开启 Wi-Fi Calling 时通过 `enableModemForSlot` 关闭该卡蜂窝协议栈（不连带影响大陆卡，保留 UICC/QRTR 鉴权），普通呼叫不可用时直接失败而不回退蜂窝；关闭总开关、禁用或卸载模块后才恢复系统 IMS 与该卡蜂窝。同时修复 Android 17 自动覆盖安装参数与 `control.sh` 进程退出时 `tr` 死循环锁死问题。详见 [单卡互斥与验证记录](docs/EXCLUSIVE_RADIO_VALIDATION.md)。

0.2.4 将独立 VoWiFi 的出口限制为真实 Linux TUN；所选接口失效时不自动替换为蜂窝或物理 Wi-Fi；配置生成、提交和模块启动前均检查实际 TUN 类型。全部接口仍可查看，旧选择保留。详见 [TUN 策略与验证边界](docs/TUN_POLICY_VALIDATION.md)。

0.2.3 将系统 IMS 接管与实际注册状态关联：独立引擎注册成功后才接管；注册丢失且通话空闲时恢复原厂 IMS，即使所选 VPN 接口仍然 UP。恢复注册后自动重新接管，保留接口选择。详见 [回退验证](docs/IMS_FALLBACK_VALIDATION.md)。

0.2.2 将 APK 首次安装和覆盖升级纳入模块，保留原配置；升级只需刷一个模块 ZIP 并重启。安装流程的本地验证见 [自动安装记录](docs/AUTO_APK_VALIDATION.md)。

0.2.1 修复通话终结事件遗漏、共享通话线程被音频关闭阻塞、旧会话残留和来电号码展示状态缺失。旧版在本地测试中复现，修正后通过；手机离线，尚未安装复测。详见 [通话修复记录](docs/CALL_FIX_VALIDATION.md)。

0.1.0-dev 曾在 Xiaomi 15 / Android 17 实测 CTExcel SIP 200 和系统 `REGISTERED / WLAN / READY`。0.2.0-dev 移除了外部运行依赖并完成本地检查；本轮手机离线，**尚未验证新版本安装、独立注册和重启恢复**。两个版本的证据分开记录，见 [0.2 独立版验证](docs/STANDALONE_VALIDATION.md) 与 [0.1 真机记录](docs/VALIDATION.md)。

## 当前实现

- 按当前 SIM 的归属 PLMN 判断：460、461 保留系统 IMS；其余有效归属 PLMN（包括港澳台）由用户开启自动接管。身份未就绪时不接管。
- 按 SIM 身份保存接口选择，列出全部内核网络接口并标注能否用作 VoWiFi 出口；消失或非 TUN 的选择保留为失效状态。能核实 VPN 所有者和 TUN 类型时，首次默认选择 FlClash 的接口。
- 每卡独立引擎会话、配置和系统 MMTEL 绑定。先准备引擎注册，再选择系统 provider；未注册时原厂 IMS 保持接管。接口失效、注册丢失、换卡、关闭服务时，在无通话状态恢复系统服务。
- 系统注册回调来自独立引擎的真实 `getStatus.ims == REGISTERED`，不会引用系统原有 IMS 注册结果。
- 接入 Android MmTel、普通语音会话、PCM 音频及文字短信适配。通话和发送短信开关默认关闭，RPC 层再次检查；设备验收不得开启或触发收费动作。

新写的是 Android 系统服务、分卡策略、接口选择、生命周期、SIM 凭据读取和网络快照；底层将 **SoftVoWiFi 0.3.48 arm64 引擎二进制**直接打包进本模块，其完整源码不在这个项目中。引擎来源仍是 SoftVoWiFi，但手机不再需要安装原项目。独立副本修改四条文件路径中的 16 个数据字节，代码、鉴权、国家码与 ELF 布局不变；原始引擎保留。见 [路径隔离补丁](docs/ENGINE_ISOLATED_LOG_POOL.md)。

SIM 配置直接通过 Android `IPhoneSubInfo` 读取当前 IMSI、IMEI 和可用的 ISIM 字段，核对前后身份，再由内置引擎严格校验并原子写入。本项目不再查找原 APK 或调用其 Java 类，也不复制旧 SIM 配置。

应用数据位于 `/data/adb/newvowifi_ims`；引擎网络快照、通话锁和日志池位于 `/data/adb/nvwims`。本模块与应用策略每 5 秒根据当前 Android 网络生成快照，包含真实 Network handle、地址和 DNS；引擎拒绝超过 15 秒的旧快照。详见 [引擎独立运行依赖](docs/STANDALONE_ENGINE_DEPENDENCIES.md)。

0.1 真机测试选择了经所有者核实的 FlClash `tun0` 和现有 qianlink 英国出口。出口探测 GB 不代表 SIP 写入 `country=GB`；二进制分析表明该分支省略 PANI。0.2 保留这段协议逻辑，不伪造 GPS 坐标。

## 安装与首次设置

模块面向已 root 的 Android 17 / arm64 设备。**从 0.1.0 或之后版本升级，只需覆盖刷入 `NewVoWiFi-module.zip`，然后重启；无需另外安装 APK。**

正常系统中刷入时会自动安装/覆盖随包 APK；通话中、电话状态未知或安装服务暂不可用时，自动留待下次启动补装。Recovery 中刷入也由开机脚本完成安装。开机再次核对 APK 内容一致后，模块才继续启动网络快照和前台服务。

覆盖升级使用相同包名和签名，保留应用开关、每张卡的接口选择、SIM 配置和其他已有数据。安装器不卸载应用、不清数据、不重置偏好；签名冲突或不允许的降级会明确失败并保留旧数据。首次使用仍需授予 NewVoWiFi root、开启海外卡接管并确认网络接口；已有用户沿用原设置。

所选 TUN 必须已经能访问运营商 ePDG；不要求物理 Wi-Fi 在线。使用 FlClash 时由 FlClash 自己提供 VPN/代理，模块不代办代理订阅或 VPN 配置。普通电话回退仍需该卡具备蜂窝服务。移除对 SoftVoWiFi 的依赖不等于所有手机、运营商都已验证。本版原生 SIM 通道为 Qualcomm QRTR，其他基带尚未支持。

TUN 选择与启动校验不是全链路抓包证明：FlClash 的实际代理规则/物理上游、内核 XFRM 输出及 TUN 断开瞬间仍需真机确认；本模块也未修改原厂 IMS 的 WFC 偏好。不能据此声称全机所有 Wi-Fi Calling 都受本模块出口限制。

## 构建

在当前 Windows 开发机运行：

```powershell
python D:\NewVowifi\service\tools\build.py
```

需要本机 JDK 21、Android build-tools 37.0.0、Android 37 资源 SDK、`deps/android-system.jar` 和 `deps/vowifi-engine-arm64-isolated`。源包保留原始引擎，可运行 `tools/isolate_engine_log_pool.py` 复现独立副本。本版以当前手机 Android 17 / API 37 为目标，未声明兼容旧版系统。依赖路径、版本及来源记录在 `deps/`；脚本通过 `javac → d8 → aapt2 → zipalign → apksigner` 生成 APK 与 KernelSU/Magisk 模块。签名文件仅供本机开发，不放入源码包。

输出：`dist/NewVoWiFi-module.zip` 是完整安装/升级包；另保留 `dist/NewVoWiFi.apk` 供开发验证，日常刷入不需要单独使用。`dist/SHA256.json` 记录校验值，再运行 `tools/package_source.py` 生成源码与固定依赖包。模块使用独立 ID `newvowifi`，不覆盖原 `vowifi_sw`。

此前 0.1 实机采用数据分区 APK、应用 root 授权和模块脚本即时运行；普通应用域无法直连 socket 时使用 root 子进程中继。本版沿用此实现。0.2 新模块尚未安装到离线手机，不把此前结果计为本版实测。

## 本地验证

```powershell
python D:\NewVowifi\service\tests\engine\run_tests.py
python D:\NewVowifi\service\tests\policy\run_tests.py
& D:\NewVowifi\service\tests\sms\run.ps1
& D:\NewVowifi\service\tests\sms\run-adapter.ps1
python D:\NewVowifi\service\tests\engine\run_call_tests.py
python D:\NewVowifi\service\tests\engine\run_feature_tests.py
python D:\NewVowifi\service\tests\policy\test_module_control.py
python D:\NewVowifi\service\tests\policy\test_standalone_module.py
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
- 恢复原厂 provider 不等于已取得蜂窝服务；正常蜂窝呼叫还受飞行模式、SIM 注册和运营商覆盖等条件限制。接口选择仍然不限于物理 Wi-Fi，可用的移动数据/VPN 出口在独立注册成功时也遵循同一接管条件。
- 不支持的 PDU 语义（如二进制、特殊 DCS、送达回执请求及非默认有效期）会明确失败，不能静默变成普通短信。引擎接受发送不是送达证明。
- 音频、真实呼叫建立及运营商短信收发尚不能因本地模拟测试而宣称通过。按用户要求，真机验收不拨号、不发送短信。
- 目前运营商实测仅覆盖这张 CTExcel；自动分卡规则已实现，不等于所有海外运营商均已验证。验证模式关闭普通语音/短信能力上报，系统界面不一定显示可拨号的 Wi-Fi Calling 标识。
- 原 SoftVoWiFi 与本服务不能同时使用同一 SIM 的引擎 socket 和内核会话。此次首次接管前清理了经当前 SIM、隔离 ID、时间戳和完整属性核对的旧策略及路由；产品没有加入全局清空路由或 XFRM 的动作。

设备配置、SIM 身份、私有日志不包含在交付源码包或用户报告中。安装前的回滚记录必须直接保存在 `C:\ProjBackup\NewVoWiFi\<timestamp-purpose>\`，核验 SHA-256 后再实施部署。
