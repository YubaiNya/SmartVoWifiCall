# SmartVoWifiCall

独立 Android `ImsService` 开发版，当前版本 **0.4.5-dev**（eSIM 切卡与按卡自动切换 Clash 节点优化）。模块内包含 APK（Apple Design + Miuix + Kyant AndroidLiquidGlass 四页面液态玻璃 UI）、VoWiFi 引擎、内置 `mihomo` Clash 内核（`wfctun0`）、External Controller 调试与实时连接管理、SIM 配置生成与实时网络快照，无需安装外部 VoWiFi Engine 或外部 VPN 应用。源码位于 `app/src/main/java/com/smartvowificall/ims/`。

0.4.5 优化 Wi-Fi Calling 期间 eSIM 配置文件切换：按 `simKey` 自动记忆并切换内置 Clash 出口节点、切节点或点刷新立即清理旧连接与注册退避、切换 eSIM 时清理旧槽位绑定与 `FallbackPolicy` 计时、修正锁内租约迁移与 eSIM 停用/切回大陆卡的 6 秒防抖射频释放，并修复网络页版本号硬编码问题。

0.4.4 修复呼叫开始后失败却误发 StartFailed 回调造成的系统通话残留；将 Android 音频处理通知放到实际呼叫阶段，并在录音期间保持麦克风前台服务，结束后释放。**仅通话录音期间显示必要的无声通知，待机不常驻。** DTMF 正常不代表麦克风正常；诊断只记录电平、帧数和系统静音状态，不保存音频。详见 [通话修复验证](docs/CALL_FIX_VALIDATION.md)。

0.4.3 让代理随网络通话服务启动/停止：先启动代理再注册，关闭时先结束通话并恢复手机网络，最后停止代理。稳定状态减少 root 全量检查，关闭后停止应用定时检查；UI 切后台停止刷新、精简文案。已完成本地回归与打包，手机拔线后未部署本版，实际续航及通话仍待复验。详见 [节能验证与边界](docs/BUILTIN_CLASH_VALIDATION.md)。

0.4.2 包含挂断后系统清除语音服务覆盖的自动恢复、按卡槽保活 IMS 配置实例和无号码通话诊断；已由用户在 Xiaomi 15 / Android 17 上确认连续三个电话稳定。关闭物理 Wi-Fi 时仍可用大陆卡数据承载独立 VoWiFi。总开关关闭或自动回退后，海外卡会恢复蜂窝并可能建立原厂 IMS 专用承载；4636 的数据状态不等同于默认互联网出口。详见 [通话与数据状态验证](docs/BUILTIN_CLASH_VALIDATION.md)。

0.4.0 采用 Apple Design 视觉规范、Miuix (`0.9.4`) 与 Kyant AndroidLiquidGlass (`2.0.1`) 重构为「概览 / 代理 / 连接 / 网络」四页面 UI 与 SukiSU-Ultra 同源三层液态玻璃弹簧底栏；新增内置 Clash External Controller 本机/局域网调试与实时连接查看/断开；新增 `FallbackPolicy` 在所选接口非 TUN、接口不可用或注册超时时自动释放目标卡蜂窝射频并恢复系统 IMS 服务（解决切换失效接口后状态栏 VoWiFi 图标残留与无法回退蜂窝呼出的问题），并移除状态栏常驻通知。详见 [内置 Clash 验证记录](docs/BUILTIN_CLASH_VALIDATION.md)。

0.3.2 在 0.3.1 独立 Network Namespace 内置 Clash 的基础上，修复单卡关闭蜂窝协议栈下拨号被 Android `TelephonyConnectionService` 以 `STATE_OUT_OF_SERVICE` 拦截的问题（仅在 VoWiFi `REGISTERED` 时对目标 `subId` 启用 `ITelephony.setVoiceServiceStateOverride`，掉注册/恢复时清除），并修复强停/升级后小米 `ImsServiceController` 跳过重连的问题。详见 [内置 Clash 验证记录](docs/BUILTIN_CLASH_VALIDATION.md)。

0.3.1 在 0.3.0 内置 `MetaCubeX/mihomo v1.19.34` 内核的基础上，将 `wfctun0` 与 `iface:wfctun0` 引擎进程迁入独立 Linux Network Namespace（`mihomo-arm64` 留在根命名空间走系统默认路由，根命名空间零 `ip rule` / 零路由改动 / 零 Android VPN），彻底解决引擎 `requireInterfaceRoute` 拒绝非 VPN TUN 的问题并在真实海外订阅下完成端到端 `REGISTERED / WLAN / READY` 实测；App 内支持订阅链接拉取、YAML 导入、节点切换与测速，同时保留 `/data/adb/smartvowificall_ims/clash/profile.yaml` 本地维护与外部 TUN 接口下拉选择。详见 [内置 Clash 验证记录](docs/BUILTIN_CLASH_VALIDATION.md)。

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

新写的是 Android 系统服务、分卡策略、接口选择、生命周期、SIM 凭据读取和网络快照；底层将 **VoWiFi Engine 0.3.48 arm64 引擎二进制**直接打包进本模块，其完整源码不在这个项目中。引擎来源仍是 VoWiFi Engine，但手机不再需要安装原项目。独立副本修改四条文件路径中的 16 个数据字节，代码、鉴权、国家码与 ELF 布局不变；原始引擎保留。见 [路径隔离补丁](docs/ENGINE_ISOLATED_LOG_POOL.md)。

SIM 配置直接通过 Android `IPhoneSubInfo` 读取当前 IMSI、IMEI 和可用的 ISIM 字段，核对前后身份，再由内置引擎严格校验并原子写入。本项目不再查找原 APK 或调用其 Java 类，也不复制旧 SIM 配置。

应用数据位于 `/data/adb/smartvowificall_ims`；引擎网络快照、通话锁和日志池位于 `/data/adb/nvwims`。本模块与应用策略每 5 秒根据当前 Android 网络生成快照，包含真实 Network handle、地址和 DNS；引擎拒绝超过 15 秒的旧快照。详见 [引擎独立运行依赖](docs/STANDALONE_ENGINE_DEPENDENCIES.md)。

0.1 真机测试选择了经所有者核实的 FlClash `tun0` 和现有 qianlink 英国出口。出口探测 GB 不代表 SIP 写入 `country=GB`；二进制分析表明该分支省略 PANI。0.2 保留这段协议逻辑，不伪造 GPS 坐标。

## 安装与首次设置

模块面向已 root 的 Android 17 / arm64 设备。**从 0.1.0 或之后版本升级，只需覆盖刷入 `SmartVoWifiCall-module.zip`，然后重启；无需另外安装 APK。**

正常系统中刷入时会自动安装/覆盖随包 APK；通话中、电话状态未知或安装服务暂不可用时，自动留待下次启动补装。Recovery 中刷入也由开机脚本完成安装。开机再次核对 APK 内容一致后，模块才继续启动网络快照和前台服务。

覆盖升级使用相同包名和签名，保留应用开关、每张卡的接口选择、SIM 配置和其他已有数据。安装器不卸载应用、不清数据、不重置偏好；签名冲突或不允许的降级会明确失败并保留旧数据。首次使用仍需授予 SmartVoWifiCall root、开启海外卡接管并确认网络接口；已有用户沿用原设置。

所选 TUN 必须已经能访问运营商 ePDG；不要求物理 Wi-Fi 在线。使用 FlClash 时由 FlClash 自己提供 VPN/代理，模块不代办代理订阅或 VPN 配置。普通电话回退仍需该卡具备蜂窝服务。移除对 VoWiFi Engine 的依赖不等于所有手机、运营商都已验证。本版原生 SIM 通道为 Qualcomm QRTR，其他基带尚未支持。

TUN 选择与启动校验不是全链路抓包证明：FlClash 的实际代理规则/物理上游、内核 XFRM 输出及 TUN 断开瞬间仍需真机确认；本模块也未修改原厂 IMS 的 WFC 偏好。不能据此声称全机所有 Wi-Fi Calling 都受本模块出口限制。

## 构建

在当前 Windows 开发机运行：

```powershell
python D:\SmartVoWifiCall\service\tools\build.py
```

需要本机 JDK 21、Android build-tools 37.0.0、Android 37 资源 SDK、`deps/android-system.jar` 和 `deps/vowifi-engine-arm64-isolated`。源包保留原始引擎，可运行 `tools/isolate_engine_log_pool.py` 复现独立副本。本版以当前手机 Android 17 / API 37 为目标，未声明兼容旧版系统。依赖路径、版本及来源记录在 `deps/`；脚本通过 `javac → d8 → aapt2 → zipalign → apksigner` 生成 APK 与 KernelSU/Magisk 模块。签名文件仅供本机开发，不放入源码包。

输出：`dist/SmartVoWifiCall-module.zip` 是完整安装/升级包；另保留 `dist/SmartVoWifiCall.apk` 供开发验证，日常刷入不需要单独使用。`dist/SHA256.json` 记录校验值，再运行 `tools/package_source.py` 生成源码与固定依赖包。模块使用独立 ID `smartvowificall`，不覆盖原 `legacy_ims`。

此前 0.1 实机采用数据分区 APK、应用 root 授权和模块脚本即时运行；普通应用域无法直连 socket 时使用 root 子进程中继。本版沿用此实现。0.2 新模块尚未安装到离线手机，不把此前结果计为本版实测。

## 本地验证

```powershell
python D:\SmartVoWifiCall\service\tests\engine\run_tests.py
python D:\SmartVoWifiCall\service\tests\policy\run_tests.py
& D:\SmartVoWifiCall\service\tests\sms\run.ps1
& D:\SmartVoWifiCall\service\tests\sms\run-adapter.ps1
python D:\SmartVoWifiCall\service\tests\engine\run_call_tests.py
python D:\SmartVoWifiCall\service\tests\engine\run_feature_tests.py
python D:\SmartVoWifiCall\service\tests\policy\test_module_control.py
python D:\SmartVoWifiCall\service\tests\policy\test_standalone_module.py
python D:\SmartVoWifiCall\service\tests\engine\test_native_selectors.py
python D:\SmartVoWifiCall\service\tests\engine\test_isolated_log_pool.py
python D:\SmartVoWifiCall\service\tools\verify_artifacts.py
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
- 0.4.2 已由用户手动验证三个稳定电话；本地模拟测试不能替代音质、长通话和运营商短信收发验收。代理不自动拨号、不发送短信。
- 目前运营商实测仅覆盖这张 CTExcel；自动分卡规则已实现，不等于所有海外运营商均已验证。验证模式关闭普通语音/短信能力上报，系统界面不一定显示可拨号的 Wi-Fi Calling 标识。
- 原 VoWiFi Engine 与本服务不能同时使用同一 SIM 的引擎 socket 和内核会话。此次首次接管前清理了经当前 SIM、隔离 ID、时间戳和完整属性核对的旧策略及路由；产品没有加入全局清空路由或 XFRM 的动作。

设备配置、SIM 身份、私有日志不包含在交付源码包或用户报告中。安装前的回滚记录必须直接保存在 `C:\ProjBackup\SmartVoWifiCall\<timestamp-purpose>\`，核验 SHA-256 后再实施部署。
