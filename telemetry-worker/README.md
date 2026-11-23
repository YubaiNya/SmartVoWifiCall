# SmartVoWifiCall Telemetry Relay (Cloudflare Worker)

将 App 端可选开启的**脱敏遥测 JSON** 安全中转写入你的私有 GitHub 仓库（默认 `YubaiNya/SmartVoWifiCall-Telemetry`），无需把 GitHub Token 写进开源代码或 APK。

## 工作流程

1. App 默认关闭遥测；用户手动开启或点击「立即上传脱敏日志」后，向本 Worker 的 `POST /v1/telemetry` 发送严格白名单脱敏 JSON（不含 ICCID、IMSI、IMEI、手机号、节点名、订阅链接、IP 和密钥）。
2. Worker 校验 schema（`smartvowificall.telemetry.v1`）与 64 KB 体积上限，并执行二次深度脱敏。
3. Worker 使用服务端加密变量 `GITHUB_TOKEN`，按日期写入私有仓库 `logs/YYYY/MM/DD/<timestamp>-<installId前8位>-<rand>.json`。

## 三步部署

1. 在 GitHub 生成一枚 **Fine-grained Personal Access Token**：
   - **Repository access**：仅选择 `YubaiNya/SmartVoWifiCall-Telemetry`（私有仓库）
   - **Permissions**：`Contents` 设为 **Read and write**（其余全部 No access）
2. 在本目录配置密钥并部署：
   ```bash
   npx wrangler login
   npx wrangler secret put GITHUB_TOKEN
   npx wrangler deploy
   ```
3. 默认已部署地址：`https://smartvowificall-telemetry.yubainya.workers.dev`（App 默认接口为 `https://smartvowificall-telemetry.yubainya.workers.dev/v1/telemetry`，也可在「网络 → 日志与遥测」自定义为自己的 Worker 域名）。

## 接口说明

- `GET /health`：返回 Worker 健康状态与配置就绪标志。
- `POST /v1/telemetry`：接收 `smartvowificall.telemetry.v1` 脱敏 JSON，成功返回 `{"ok":true,"code":"TELEMETRY_STORED","path":"logs/..."}`。
