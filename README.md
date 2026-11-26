<div align="center">

<br>

<img src="./assets/logo.png" width="96" height="96" alt="SmartVoWifiCall">

<h1>SmartVoWifiCall</h1>

<h3>海外卡网络通话，本该如此。</h3>

<p>大陆卡稳在网，海外卡独立走代理，不占系统 VPN。</p>

<br>

<p>
  <a href="https://github.com/YubaiNya/SmartVoWifiCall/releases"><img src="https://img.shields.io/badge/Release-v0.6.3--dev-0071E3?style=flat" alt="Release v0.6.3-dev"></a>
  <img src="https://img.shields.io/badge/Android-14%E2%80%9317%20Root-1D1D1F?style=flat" alt="Android 14-17 Root">
  <img src="https://img.shields.io/badge/Engine-Pure%20Go%20Self--Owned-00ADD8?style=flat&logo=go&logoColor=white" alt="Pure Go Self-Owned Engine">
  <a href="https://github.com/YubaiNya/SmartVoWifiCall/issues"><img src="https://img.shields.io/github/issues/YubaiNya/SmartVoWifiCall?label=Issues&color=6E6E73&style=flat" alt="Issues"></a>
  <br>
  <a href="https://github.com/YubaiNya/SmartVoWifiCall/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-1D1D1F?style=flat" alt="License GPL-3.0"></a>
  <a href="https://ifdian.net/a/YubaiNya"><img src="https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-%E8%B5%9E%E5%8A%A9%E4%BD%9C%E8%80%85-946CE6?style=flat&logo=buy-me-a-coffee&logoColor=white" alt="爱发电"></a>
  <a href="https://linux.do/u/YubaiNya"><img src="https://img.shields.io/badge/Linux.Do-YubaiNya-1D1D1F?style=flat&logo=discourse&logoColor=white" alt="Linux.Do"></a>
  <a href="https://linux.sb/u/YubaiNya"><img src="https://img.shields.io/badge/Linux.sb-YubaiNya-0071E3?style=flat&logo=linux&logoColor=white" alt="Linux.sb"></a>
  <a href="https://forum.naixi.net/home.php?mod=space&username=yubai"><img src="https://img.shields.io/badge/%E5%A5%B6%E6%98%94%E8%AE%BA%E5%9D%9B-yubai-6E6E73?style=flat&logo=discourse&logoColor=white" alt="奶昔论坛"></a>
  <br>
  <a href="https://github.com/lilyzhaun/softvowifi-client"><img src="https://img.shields.io/badge/%E8%87%B4%E8%B0%A2-SoftVoWiFi-1D1D1F?style=flat&logo=github&logoColor=white" alt="SoftVoWiFi"></a>
  <a href="https://github.com/MetaCubeX/mihomo"><img src="https://img.shields.io/badge/Core-mihomo-6E6E73?style=flat&logo=github&logoColor=white" alt="mihomo"></a>
  <a href="https://github.com/compose-miuix-ui/miuix"><img src="https://img.shields.io/badge/UI-Miuix-6E6E73?style=flat&logo=github&logoColor=white" alt="Miuix"></a>
  <a href="https://github.com/Kyant0/AndroidLiquidGlass"><img src="https://img.shields.io/badge/Glass-AndroidLiquidGlass-6E6E73?style=flat&logo=github&logoColor=white" alt="AndroidLiquidGlass"></a>
</p>

<br>

</div>

---

## 为什么做它

在双卡手机上使用海外卡 Wi-Fi Calling，传统方案往往要全局挂 VPN、担心误触海外漫游，或者一换 eSIM 就掉线卡死。SmartVoWifiCall 通过独立的 Android `ImsService`，把整条通话链路彻底收拢在后台静默运转。

<table>
  <tr>
    <td width="50%" valign="top">
      <strong>双卡各走各路</strong><br><br>
      大陆卡保留系统原厂蜂窝与 IMS；海外卡独立接管语音与短信，并通过单卡协议栈开关关闭海外卡蜂窝，普通通话绝不误回退漫游。
    </td>
    <td width="50%" valign="top">
      <strong>零 VPN 占位，自动绕开系统代理</strong><br><br>
      内置 <code>mihomo</code> 运行在独立 Linux 网络命名空间，自动识别物理网卡（<code>wlan0</code> / <code>rmnet_data*</code>）并绕开系统 <code>tun0</code> VPN，不套娃、不改系统路由表，不代理手机里的其他任何应用。
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <strong>纯自研 Go 引擎，跨基带通用</strong><br><br>
      底层 VoWiFi 协议栈（IKEv2 / IPsec ESP / IMS SIP / RTP）100% 源码自研零外部依赖，常驻内存低至 1.2 MB；高通走 QRTR、天玑 / Tensor / Exynos 走通用 Binder AKA，彻底摆脱外部预编译二进制依赖。
    </td>
    <td width="50%" valign="top">
      <strong>安静省电，多 eSIM 自动跟节点</strong><br><br>
      常驻 Root 守护进程毫秒级响应，按 SIM 自动记住并切换地区节点；内置 25 家海外运营商规则，断链即下状态栏 VoWiFi 图标，挂断即释录音前台服务。
    </td>
  </tr>
</table>

<br>

## 工作原理

```mermaid
flowchart LR
    SIM["海外卡 / eSIM<br/>关闭单卡蜂窝 · 跨基带 SIM AKA"] --> IMS["SmartVoWifiCall<br/>独立 ImsService"]
    IMS --> ENG["自研 Go VoWiFi 引擎<br/>IKEv2 · IPsec · SIP · RTP"]
    ENG --> NS["独立网络命名空间<br/>wfctun0 (198.19.0.1/30)"]
    NS --> CLASH["内置 mihomo<br/>仅代理 VoWiFi · 零系统 VPN"]
    CLASH --> EPDG["运营商 ePDG / IMS"]
    CN["大陆卡 (MCC 460 / 461)"] -. 原厂蜂窝与数据独立运行 .-> SYS["系统原厂网络"]
```

- **按需接管与自动回退**：只有独立引擎真实完成运营商 SIP `REGISTER` 后才启用语音覆盖；遇到接口失效或超时自动释放蜂窝并恢复原厂 IMS，手动切节点或点击刷新可立刻重试。
- **液态玻璃管理界面**：内置「概览 / 代理 / 连接 / 网络」四页面，支持订阅拉取、YAML 导入、节点延迟测速、实时连接查看与 External Controller 外部调试。

<br>

## 三步上手

**适用设备**：已 Root（KernelSU / SukiSU / Magisk）的 arm64 Android 14–17（API 34–37）设备，支持高通骁龙与天玑 / Tensor / Exynos 跨基带 SIM AKA（内置「系统接口兼容性自检」），重点在 Xiaomi 15 / Android 17 上实网验证。

1. **刷入模块并重启**
   前往 Releases 下载 `SmartVoWifiCall-module.zip`，在 KernelSU / Magisk 中刷入后重启。模块会自动安装或覆盖升级随包 APK，保留原有设置。
2. **导入代理并选节点**
   打开 SmartVoWifiCall 并授予 Root 权限，在「代理」页填入订阅链接或导入 YAML，选择对应海外卡归属地区的节点。
3. **开启海外卡网络通话**
   回到「概览」页，确认出口为 `wfctun0` 后打开总开关；显示「已注册」后，按需开启「允许通话和发送短信」。

> [!TIP]
> 同一 eSIM 卡槽装有多张不同地区的海外卡时，每张卡只需选一次对应地区节点并注册成功，后续切卡就会自动切换对应节点。

> [!CAUTION]
> 开启「允许通话和发送短信」后将直接通过系统电话和短信发起真实运营商业务，资费按套餐标准收取。本项目仅用于个人合法自用与技术研究。

<br>

## 日志与遥测

SmartVoWifiCall 内置可选的脱敏日志上报与本地导出能力，全部位于 App 的 **「网络 → 日志与遥测」** 卡片中。

- **默认关闭，完全自愿**：遥测开关默认处于关闭状态，未经你手动开启或点击「立即上传一次脱敏日志」，App 不会向任何服务器发送日志。
- **默认走 Wi-Fi Calling 链路，支持切系统路由**：上传默认复用内置 `mihomo` 的 Wi-Fi Calling 隔离代理链路（`wfctun0`），无需手机主网络挂系统代理即可直达中转接口；未开启内置代理时也可一键切换为「系统路由」。
- **双重脱敏，隐私统一显示为 `[Hidden]`**：上传前在 App 端与 Cloudflare Worker 服务端执行双重脱敏，保留完整诊断结构、原始日志、网络请求与引擎 RPC 包体结构，仅将敏感值统一替换为 `[Hidden]`；同时支持上传前点击「预览脱敏内容」检查实际报文。
- **认证包与分包上传（v3）**：开启遥测或手动上传时，默认附带留存的 IKE/EAP 与 SIP 注册认证记录；保留公开 Payload、协议前缀及运营商域，隐私部分打码。超过单包限制会分包，回执不一致、捕获缺口和部分失败明确提示。每个引擎留存当前及上一份 256 KiB 认证日志。
- **日志 ID 快速排查**：每次上传都会生成唯一可读日志 ID（如 `SVW-20261124-19B351`）并保存在 App 的「日志上传记录」中，支持一键复制 ID 附在 Issue 里，方便开发者快速定位问题。
- **本地完整导出**：下方随时可通过系统文件选择器手动导出「脱敏日志包（ZIP）」或「原始全部日志包（ZIP）」（原始日志仅存本地，绝不上传）。

<details>
<summary><strong>查看脱敏上传字段与隐藏范围</strong></summary>

<br>

| 类别 | 会上传的非隐私信息 | 统一替换为 `[Hidden]` 的隐私字段 |
| --- | --- | --- |
| **设备与系统** | 机型 / 市场名 / 认证型号、SoC 处理器、运行内存、分辨率 / DPI、厂商系统版本（HyperOS / MIUI / ColorOS / OneUI 等）、Android 版本与安全补丁、基带版本、内核版本、硬件版本 | 设备序列号（`serial`）、`Android ID` |
| **SIM 与通话状态** | 卡槽号、SIM 所属国家（`countryIso`）、运营商名称（`carrierName` / `simOperatorName`）、PLMN、是否 eSIM、IMS / 隧道 / 回退状态 | `ICCID`、`IMSI`、`IMEI`、`simKey`、手机号 / 通话双方号码、SIP 账号用户名、AKA 鉴权密钥 |
| **代理与网络日志** | 接口列表、Clash 节点数量与运行状态、Clash HTTP 与引擎 RPC 完整包体结构、脱敏后的原始引擎 / IMS / `logcat` 日志 | 代理节点名称、订阅链接、控制器密钥、`Authorization` / Token、真实局域网与公网 IP |

</details>

<br>

## 更新日志

> 完整更新记录同步维护于 [UpLog.md](./UpLog.md)（详细技术验证见 [VERSION_HISTORY.md](./VERSION_HISTORY.md)）。

### `v0.6.3-dev`（2026-11-26）— CTExcel 注册修复

- 修复内置代理改写 UDP 回包来源导致的握手超时，保留 IKE SPI、完整性和最终 AUTH 校验。
- 补齐 CTExcel 的 IPv6 IMS 通路与 `ipsec-3gpp` 安全协商；精简 REGISTER，避免双层 ESP 封装后超过代理 TUN MTU。
- 小米 15 + CTExcel + 现有英国节点已实测注册成功并持续超过 6 分钟。新增安全协商日志与 IPv6 脱敏，详见[验证记录](service/docs/CTEXCEL_VALIDATION.md)。电话、短信及 POCO F3 / giffgaff 本轮未验证。

### `v0.6.2-dev`（2026-11-26）— 认证流程修复与认证包遥测

- 修复 EAP 成功条件、最终 AUTH 校验和设备身份发送时机；记录 IKE/EAP 与 SIP 注册认证阶段、Payload 和引擎版本。
- 遥测 v3 默认附带留存的认证记录，保留非隐私内容及混合字段中的公开部分，隐私部分打码；超限分包并校验回执，缺口和上传失败明确提示。
- 小米 15 + 3HK 已实测注册成功，Worker v3 已完成真实入库及脱敏验证。**POCO F3 / giffgaff 仍需更新模块、重启后上传新遥测，尚不能确认已修复。**

### `v0.6.1-dev`（2026-11-25）— 应用内更新直链下载、扫码订阅与欧美 ePDG 兼容升级

- **应用内检查更新 & 模块直链下载**：「网络」页新增「检查更新」，发现新 Release 自动弹窗展示版本号与更新说明，支持直接下载模块 ZIP 到系统 `Download/` 文件夹（不经浏览器，带实时进度、ZIP 校验与取消）或一键前往 GitHub。
- **内置 Clash 摄像头 / 相册扫码订阅**：内核 1 / 内核 2 均支持 CameraX + 离线 ZXing 扫码与相册选图导入订阅（支持 `http(s)://`、`clash://`、`mihomo://`、`flclash://`、`sub://`）。
- **Go 引擎 3GPP IKEv2 兼容升级 & 全卡校验对齐**：支持 `@nai.epc...` / EAP-AKA' / `@wlan...` 自动协商与缓存（3HK 1.72 秒冷启无回归，适配 O2 UK / giffgaff 等欧美 ePDG），补齐 `INITIAL_CONTACT` / `MOBIKE_SUPPORTED` / 3GPP `DEVICE_IDENTITY (41101)`，并移除 Go 引擎残留的大陆卡 `MAINLAND_SIM_FORBIDDEN` 限制。
- **修复 `RootDaemonMain` 每 5 秒重复拉起**：守护进程抢锁后原子发布权威 PID/Owner，根治 `service.sh` 巡检误判反复启动 ART VM 的问题，并将守护进程日志纳入遥测。

<details>
<summary><strong>查看历史版本更新（v0.6.0-dev → v0.1.0-dev，同步自 UpLog.md）</strong></summary>

<br>

- **`v0.6.0-dev`（2026-11-25）**：解除国内外卡限制并支持双卡并发 VoWiFi；总开关省电停机与停止前 Clash 状态自动恢复；新增双内置 Clash 独立内核（`wfctun0` / `wfctun1`）；修复单卡/SM8250 `RADIO_COMMAND_REJECTED`、Android 17 `sepolicy.rule` 与遥测上传 `EPIPE`/大包裁剪/节点名脱敏。

- **`v0.5.0-dev`（2026-11-24）**：底层协议栈 100% 纯 Go 自研（零 SoftVoWiFi 依赖，待机 ~1.2 MB RSS）；内置 `mihomo` 通过 `fwmark 0x20000` 自动绕开系统 `tun0`（3HK 冷启动注册 1.78 秒）；新增常驻 `RootDaemonMain`、跨基带 SIM AKA（高通 QRTR + 天玑/Tensor/Exynos Binder/APDU）、Android 14–17 适配与自检、RFC 7296 DPD / 断链秒下图标 / RTP 重同步 / 连续拨号修复、硬件 AEC/NS/AGC、快捷磁贴与 25 家运营商适配库。

- **`v0.4.7-dev`（2026-11-24）**：遥测上传默认走 Wi-Fi Calling 隔离代理链路（支持一键切系统路由），升级 `[Hidden]` 双重脱敏、非隐私设备/SIM 排障信息与唯一日志 ID（`SVW-YYYYMMDD-XXXXXX`）。
- **`v0.4.6-dev`（2026-11-23）**：新增可选脱敏遥测上传（默认关闭）+ Cloudflare Worker 中转服务，新增本地手动导出「脱敏日志 ZIP」与「原始全部日志 ZIP」。
- **`v0.4.5-dev`（2026-11-23）**：按 SIM 记忆与自动切换代理节点，切节点/刷新立即破除退避，完善 eSIM 切卡租约迁移与恢复。
- **`v0.4.4-dev`（2026-11-23）**：修复远端挂断后下一通卡死 DIALING 与 Android 17 录音静音（silenced），仅通话期间持有无声 `microphone` 前台服务。
- **`v0.4.3-dev`（2026-11-23）**：开启网络通话联动启停内置代理，稳定态降频轮询与深度节能，精简中文状态文案。
- **`v0.4.2-dev`（2026-11-23）**：修复挂断后 Telecom 清除语音覆盖导致的再次呼出失败，强引用持有 `ImsConfigImplBase`。
- **`v0.4.0-dev`（2026-11-22）**：全新 Apple Design + Miuix + 液态玻璃四页面 UI，内置 Clash External Controller 与实时连接管理，新增 `FallbackPolicy` 自动回退并移除待机常驻通知。
- **`v0.3.2-dev`（2026-11-22）**：新增 `setVoiceServiceStateOverride` 解决关闭海外卡蜂窝后的拨号拦截，修复小米升级后 `ImsService` 不重连。
- **`v0.3.1-dev`（2026-11-22）**：内置 Clash 升级为独立 Linux Network Namespace 隔离（零 Android VPN、零根命名空间路由改动）。
- **`v0.3.0-dev`（2026-11-22）**：内置官方 `mihomo` 内核与独立 `wfctun0` 接口，新增订阅拉取、YAML 导入与节点测速切换。
- **`v0.2.5-dev`（2026-11-22）**：引入单卡蜂窝协议栈互斥（`enableModemForSlot`），仅关海外卡蜂窝防误漫游，大陆卡保持在网。
- **`v0.1.0-dev` – `v0.2.4-dev`（2026-11-22）**：建立独立 Android `ImsService` 框架、内置 SIM 凭据与网络快照、单 ZIP 自动覆盖安装 APK、真实 `IFF_TUN` 校验与按需注册接管。

完整说明请直接查看 **[UpLog.md](./UpLog.md)**。

</details>

<br>

## 开发与文档

仓库包含完整的 Android 上层应用、**纯自研零外部依赖 Go VoWiFi 协议栈引擎（`engine/`）**、测试、模块脚本、文档与交付物校验哈希；已彻底移除外部预编译 SoftVoWiFi 二进制依赖，不包含签名私钥或个人订阅配置（内置 `mihomo` 来源与 SHA-256 见 `service/deps/`）。

| 目录 | 用途 |
| --- | --- |
| `engine/` | 纯自研 Go VoWiFi 协议栈（`CGO_ENABLED=0` 静态编译 `arm64`：IKEv2/EAP-AKA/AKA'、用户态 IPsec ESP、IMS SIP/SMS、RTP/DTMF、通用 Binder + 高通 QRTR 跨基带 SIM AKA） |
| `service/app/` | 独立 `ImsService`、策略与回退引擎、跨基带 `RootSimAuthMain`、Root 辅助与 Compose 界面 |
| `service/module/` | 模块安装脚本、引擎控制与 Clash 命名空间隔离脚本 |
| `service/tests/` | 策略、单卡互斥、Clash 净化、通话/音频与生命周期回归测试 |
| `service/tools/` | 一键构建（含自动交叉编译 Go 引擎）、源码打包与交付物完整性校验脚本 |
| `telemetry-worker/` | 可选 Cloudflare Worker 脱敏遥测中转服务（写入私有 GitHub 仓库） |
| `UpLog.md` | 各版本更新日志（已嵌入首页展示） |
| `VERSION_HISTORY.md` | 完整版本迭代与真机实测记录 |
| `service/dist/SHA256.json` | 交付物哈希校验清单 |

<details>
<summary><strong>构建命令与技术验证文档</strong></summary>

<br>

```bash
# 自研 Go VoWiFi 协议栈离线单元测试与端到端回环验证
cd engine && go test -count=1 ./...

# 策略、回退、单卡互斥与 Clash 配置净化测试
python service/tests/policy/run_tests.py

# 引擎契约、通话/音频与 Feature 生命周期测试
python service/tests/engine/run_tests.py
python service/tests/engine/run_call_tests.py
python service/tests/engine/run_feature_tests.py

# 一键编译 Release APK + 静态 arm64 Go 引擎 + 模块 ZIP 并校验签名与完整性
python service/tools/build.py
python service/tools/verify_artifacts.py
```

- [版本迭代记录（VERSION_HISTORY.md）](./VERSION_HISTORY.md)
- [服务架构与构建详解（service/README.md）](./service/README.md)
- [内置 Clash 隔离命名空间、节能与实机验证](./service/docs/BUILTIN_CLASH_VALIDATION.md)
- [通话终结与麦克风上行修复验证](./service/docs/CALL_FIX_VALIDATION.md)
- [单卡蜂窝互斥（enableModemForSlot）验证](./service/docs/EXCLUSIVE_RADIO_VALIDATION.md)
- [TUN 出口策略验证](./service/docs/TUN_POLICY_VALIDATION.md) · [Provider 路由验证](./service/docs/IMS_FALLBACK_VALIDATION.md)
- [单 ZIP 自动安装 APK 验证](./service/docs/AUTO_APK_VALIDATION.md) · [引擎日志池隔离补丁](./service/docs/ENGINE_ISOLATED_LOG_POOL.md)

</details>

<br>

---

## 开源参考与致谢 / References & Acknowledgements

以下列明技术研究来源和实际依赖。源码中的行为、接口或测试只能说明对应版本的实现；不等于 SmartVoWifiCall 已在所有厂商、Android 版本或运营商上验证，也不表示上游维护者参与或认可本项目。

| 项目 | 实际用途与使用方式 |
| --- | --- |
| [AOSP IWLAN](https://android.googlesource.com/platform/packages/services/Iwlan/+/b9ec687fee0110ee303921431a1eca2b1d35d500/) | 研究 ePDG 选择、隧道生命周期、按接入隔离的错误策略和退避；本项目行为与恢复逻辑独立实现，未引入该服务。 |
| [AOSP IPsec](https://android.googlesource.com/platform/packages/modules/IPsec/+/5c964bbdc7cdda748b75e231102577ea7148f7ca/) | 研究 IKE/EAP 认证、会话状态、错误及超时边界；未作为依赖打包。 |
| [AOSP Telephony](https://android.googlesource.com/platform/frameworks/opt/telephony/)、[Frameworks Base](https://android.googlesource.com/platform/frameworks/base/)、[Telephony Service](https://android.googlesource.com/platform/packages/services/Telephony/)、[CarrierConfig](https://android.googlesource.com/platform/packages/apps/CarrierConfig/)、[Connectivity](https://android.googlesource.com/platform/packages/modules/Connectivity/) | Android IMS/订阅、权限、运营商配置及网络选择的接口和架构研究；平台隐藏 API 的实际可用性仍取决于系统。 |
| [AOSP PhoneInterfaceManager](https://android.googlesource.com/platform/packages/services/Telephony/+/0f31d1f00721da0b6d830f51d621f15650956fd1/src/com/android/phone/PhoneInterfaceManager.java)、[AOSP CTS TelephonyManagerTest](https://android.googlesource.com/platform/cts/+/0d01543cbc74b7e7f311cc29fe854dda968881cb/tests/tests/telephony/current/src/android/telephony/cts/TelephonyManagerTest.java) | 区分物理无线电电源与合并后的通话服务状态，参考 voice-service override 的测试条件；状态读取和回归用例独立实现，未复制上游代码。所读文件为 Apache-2.0。 |
| [SoftVoWiFi](https://github.com/lilyzhaun/softvowifi-client/tree/a19adb51cce532c5d4d51f5c5aa0bcb37f758f51) | 参考客户端会话、网络观测、控制超时和离线生命周期测试设计；当前构建使用本仓库 Go 引擎，不打包 SoftVoWiFi 预编译引擎。本次行为引擎改动未复制其代码。 |
| [SoftVoWiFi SWu Go](https://github.com/lilyzhaun/softvowifi-swu-go/tree/8aae3b9be66b75c9cf8c9b4d1df8f8c4494e5617) | COOKIE 重试、EAP-only AUTH 的源码研究及独立算法对照参考；所读仓库根许可证为 MIT。未增加生产依赖或复制其代码进入本项目。 |
| [VoWiFiX](https://github.com/iniwex5/VoWiFiX/tree/0d71dc9e90c8f3369f09b038cca059cbe3d556ed) | 对照原生 VoWiFi、双卡、通话保护及权限降级方案；仅研究参考，未引入其 EFS 修改、LSPosed Hook、基带刷新或第三方代码。 |
| [strongSwan](https://github.com/strongswan/strongswan)、[osmo-epdg](https://gitea.osmocom.org/erlang/osmo-epdg)、[Kamailio](https://github.com/kamailio/kamailio) | IKE/IPsec、ePDG/AAA 网络侧及 SIP/IMS 注册机制研究，未作为运行依赖分发。 |
| [VoWiFi UE Testing Framework](https://github.com/hw5773/vowifi-ue-testing-framework) | 终端行为、协议故障分类与隔离测试设计参考；未复制测试脚本或用例。原 VoWiFi Core Network 链接现重定向至该仓库，不作为两个独立依赖列出。 |
| [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch)、[Shizuku](https://github.com/RikkaApps/Shizuku)、[libsu](https://github.com/topjohnwu/libsu) | CarrierConfig、Binder 权限代理与 Root 进程生命周期研究；本项目不因此获得非 Root IMS 接管能力，当前未引入这些依赖。 |
| [KernelSU](https://github.com/tiann/KernelSU)、[SukiSU Nova（原 Ultra）](https://github.com/SukiSU-Nova/SukiSU-Nova)、[Magisk](https://github.com/topjohnwu/Magisk) | Root 授权、模块生命周期和环境差异研究；源码研究不等于各 Root 管理器真机兼容验证。 |
| [mihomo v1.19.34](https://github.com/MetaCubeX/mihomo/tree/v1.19.34) | 实际打包的网络代理组件，GPL-3.0；版本与散列固定于构建元数据。分发时须提供适用的对应源码及许可证。 |
| [AndroidX](https://developer.android.com/jetpack/androidx)、[Kotlin](https://github.com/JetBrains/kotlin)、[Miuix](https://github.com/compose-miuix-ui/miuix)、[AndroidLiquidGlass / Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)、[ZXing](https://github.com/zxing/zxing) | 实际 Android 界面、玻璃效果和离线扫码依赖，Apache-2.0。现有底栏包含基于 Kyant/SukiSU 实现的移植逻辑；这与本次独立编写的恢复逻辑不同。 |
| [Go](https://go.dev/) | 实际引擎工具链与标准库，相关 BSD 许可证及 PATENTS 随模块保留。 |

协议行为参考：3GPP [TS 23.402](https://www.3gpp.org/DynaReport/23402.htm)、[TS 24.302](https://www.3gpp.org/DynaReport/24302.htm)、[TS 33.402](https://www.3gpp.org/DynaReport/33402.htm)、[TS 23.228](https://www.3gpp.org/DynaReport/23228.htm)、[TS 24.229](https://www.3gpp.org/DynaReport/24229.htm)、[TS 33.203](https://www.3gpp.org/DynaReport/33203.htm)；IETF [RFC 7296](https://www.rfc-editor.org/info/rfc7296/)（IKEv2）、[RFC 4187](https://www.rfc-editor.org/info/rfc4187/)（EAP-AKA）、[RFC 9048](https://www.rfc-editor.org/info/rfc9048/)（EAP-AKA′，更新 RFC 4187 与 RFC 5448）、[RFC 4555](https://www.rfc-editor.org/info/rfc4555/)（MOBIKE）、[RFC 4301](https://www.rfc-editor.org/info/rfc4301/)（IPsec）、[RFC 3261](https://www.rfc-editor.org/info/rfc3261/)（SIP 注册有效期与 Retry-After）。参考 MOBIKE 规范不表示本引擎已实现地址迁移；当前不声明该能力。

退避计时编码另参考 [3GPP TS 24.008 v19.5.0 §10.5.7.4a](https://www.etsi.org/deliver/etsi_ts/124000_124099/124008/19.05.00_60/ts_124008v190500p.pdf)，与 TS 24.302 的 ePDG 退避作用域结合使用；不同计时器的特殊单位不能直接混用。

GSMA [IR.51 v10.0](https://www.gsma.com/newsroom/wp-content/uploads/IR.51-v10.0-1.pdf) 与 [IR.92 v24.0](https://www.gsma.com/newsroom/wp-content/uploads/IR.92-v24.0.pdf) 用于核对 IMS/VoWiFi 互操作性要求。规范的不同 Release 和修订版适用范围不同，本项目不宣称完整标准认证。上述资料均为协议/技术参考，致谢不能替代代码来源、版权声明和对应源码义务。

---

<div align="center">

<p>如果 SmartVoWifiCall 帮到了你，欢迎赞助支持持续更新。</p>

<p>
  <a href="https://ifdian.net/a/YubaiNya">
    <img src="https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-%E8%B5%9E%E5%8A%A9%E4%BD%9C%E8%80%85-946CE6?style=for-the-badge&logo=buy-me-a-coffee&logoColor=white" alt="爱发电赞助作者" height="34">
  </a>
</p>

<p>基于 GPL-3.0 开源 · 特别致谢 SoftVoWiFi、mihomo、Miuix 与 AndroidLiquidGlass</p>

</div>
