# SmartVoWifiCall Telemetry Relay (Cloudflare Worker)

将 App 端可选开启的**脱敏遥测 JSON** 安全中转写入你的私有 GitHub 仓库（默认 `YubaiNya/SmartVoWifiCall-Telemetry`），无需把 GitHub Token 写进开源代码或 APK。

## 工作流程

1. App 默认关闭遥测；用户手动开启或点击「立即上传一次脱敏日志」后，向本 Worker 的 `POST /v1/telemetry` 发送完整的脱敏诊断、原始日志、网络请求/RPC 包体结构与 SIM 国家/运营商信息（所有隐私字段均保留结构并统一替换为 `[Hidden]`）。
2. Worker 校验 schema（`smartvowificall.telemetry.v2` / `v1`）与 512 KB 体积上限，并执行服务端二次深度脱敏（统一替换为 `[Hidden]`）。
3. Worker 使用服务端加密变量 `GITHUB_TOKEN`，按日志 ID 写入私有仓库 `logs/YYYY/MM/DD/<logId>.json`（例如 `logs/2026/11/23/SVW-20261123-7A4F9C.json`），App 端会保存上传记录并支持一键复制日志 ID 供开发者查询。

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


## Telemetry v3 (local candidate)

The v3 client includes protocol-sanitized IKE/EAP and SIP registration payload records.
Only private portions are masked; carrier realms, public protocol parameters and full
nonprivate values remain visible. Authentication records are not silently truncated.
A batch is split into requests below 480 KiB, grouped by `authBatch.id` and numbered
by `authBatch.index` / `count`. The response echoes `schema`, `authBatchId`,
`authPartIndex` and `authRecordCount`; the client requires matching receipts for every part.
The original 512 KiB request limit remains in place. Invalid batches and oversized
requests fail explicitly. v1/v2 uploads remain supported.

Deploy this Worker version before installing the v3 uploader. An older Worker returns
`INVALID_SCHEMA` for v3. Source changes alone do not update the running service.
See `service/docs/AUTH_TELEMETRY_VALIDATION.md` for retention, privacy boundaries and tests.
