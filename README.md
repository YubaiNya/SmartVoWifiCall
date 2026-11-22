# NewVoWiFi

面向 Android 17 的独立 IMS 服务，按 SIM 归属与用户选择的网络接口接管海外卡；大陆卡保留原厂 IMS。项目说明、构建方法与功能边界见 [service/README.md](service/README.md)。

## 版本管理

本地 Git 仓库位于 `D:\NewVowifi`，主分支为 `main`。首个实机基线为 `v0.1.0-dev`；当前 `v0.4.0-dev` 采用 Apple Design + Miuix (`0.9.4`) + Kyant AndroidLiquidGlass (`2.0.1`) 四页面 UI 与 SukiSU-Ultra 同源三层液态玻璃阻尼底栏，内置 `MetaCubeX/mihomo` 内核并通过独立 Linux Network Namespace + `wfctun0`（`auto-route: false`、根命名空间零路由/规则改动）仅全局代理 Wi-Fi Calling 流量，支持 External Controller 外部调试与实时连接查看/断开，具备接口失效/注册超时自动蜂窝回退（`FallbackPolicy`）与无常驻通知后台运行。版本说明见 [VERSION_HISTORY.md](VERSION_HISTORY.md)。

仓库记录服务源码、测试、模块脚本、二进制依赖的来源与哈希、交付物哈希和验证文档。签名密钥、手机身份、原始日志、固件、工作证据与生成的 APK/ZIP 不纳入 Git。被忽略的文件保留在本地，不会因初始化仓库而删除。

主要入口：

- [服务源码](service/app/src/main/java/com/newvowifi/ims/)
- [模块脚本](service/module/)
- [真机验证记录](service/docs/VALIDATION.md)
- [交付物 SHA-256](service/dist/SHA256.json)

本次只建立本地仓库，未配置或推送任何远程仓库。Git 不保存完整的 SoftVoWiFi 引擎源码；引擎为本地固定版本的二进制依赖，其来源与精确哈希记录在 `service/deps/`。恢复构建环境时须另外保留这些被忽略的依赖，以及需要延续安装签名时的本地签名文件。
