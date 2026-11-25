/**
 * SmartVoWifiCall Telemetry Relay (Cloudflare Worker)
 *
 * Receives sanitized telemetry JSON (v1, v2 or v3) from the Android app and stores it as a dated JSON
 * file indexed by Log ID (`logs/YYYY/MM/DD/<logId>.json`) in a private GitHub repository via the
 * GitHub Contents API. All private fields/values are enforced as `[Hidden]` while preserving full
 * diagnostic, network request/packet, and raw log structure.
 */

const MAX_BYTES = 512 * 1024;
const HIDDEN = "[Hidden]";
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const LOG_ID_RE = /^SVW-\d{8}-[0-9A-F]{6,8}$/i;
const SUPPORTED_SCHEMAS = new Set([
  "smartvowificall.telemetry.v1",
  "smartvowificall.telemetry.v2",
  "smartvowificall.telemetry.v3",
]);

const SENSITIVE_KEYS = new Set([
  "iccid", "imsi", "imei", "meid", "msisdn", "phone", "phonenumber", "number", "peer",
  "remote", "caller", "callee", "dialnumber", "destinationnumber", "simkey",
  "subscriptionurl", "controllersecret", "secret", "token", "password", "passwd",
  "authorization", "cookie", "set-cookie", "x-telemetry-key", "apikey", "api_key",
  "nonce", "autn", "rand", "res", "ik", "ck", "msk", "emsk", "skd", "skpi", "skpr", "skei", "sker", "skai", "skar", "privatekey", "private_key", "psk",
  "impi", "impu", "sipimpu", "sipuri", "currentnode", "savednode", "preferredclashnode",
  "now", "lanipv4", "serial", "androidid", "ssid", "bssid", "wifissid", "wifibssid",
  "email", "username", "uuid", "server", "sni", "servername", "peerip", "localip"
]);

const NODE_MAP_KEYS = new Set(["proxies", "delays"]);
const NODE_LIST_KEYS = new Set(["chains", "all"]);

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

function sanitizeUrl(url) {
  const lower = url.toLowerCase();
  const isLocal =
    lower.startsWith("http://127.0.0.1:") ||
    lower.startsWith("http://localhost:") ||
    lower.startsWith("http://127.0.0.1/") ||
    lower.startsWith("http://localhost/");
  const isKnownPublic =
    lower.startsWith("https://smartvowificall-telemetry.") ||
    lower.startsWith("http://www.gstatic.com/generate_204") ||
    lower.startsWith("https://www.gstatic.com/generate_204");
  if (!isLocal && !isKnownPublic) return HIDDEN;
  const q = url.indexOf("?");
  let base = q >= 0 ? url.slice(0, q) : url;
  if (isLocal && base.includes("/proxies/") && !base.endsWith("/proxies/VoWiFi")) {
    const idx = base.indexOf("/proxies/");
    base = base.slice(0, idx + "/proxies/".length) + HIDDEN;
  }
  if (q < 0) return base;
  const query = url.slice(q + 1).split("&").map((pair) => {
    const eq = pair.indexOf("=");
    if (eq <= 0) return pair;
    const k = pair.slice(0, eq);
    const v = pair.slice(eq + 1);
    const kl = k.toLowerCase();
    if (kl === "timeout" || (kl === "url" && v.toLowerCase().includes("gstatic.com"))) {
      return `${k}=${v}`;
    }
    return `${k}=${HIDDEN}`;
  }).join("&");
  return `${base}?${query}`;
}

function sanitizeString(input, maxLen = 8192) {
  let s = String(input);
  if (s.length > maxLen) s = s.slice(s.length - maxLen);
  s = s.replace(/\b([06])\d{15}(@[A-Za-z0-9.-]+)/g, `$1${HIDDEN}$2`);
  s = s.replace(/\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{6,}/gi, `$1 ${HIDDEN}`);
  s = s.replace(/\bhttps?:\/\/[^\s"'<>\]})]+/gi, (u) => sanitizeUrl(u));
  s = s.replace(/\b89\d{13,20}[0-9A-Fa-f]{0,2}\b/g, HIDDEN);
  s = s.replace(/\bsip:[^@\s"'<>]+@/gi, `sip:${HIDDEN}@`);
  s = s.replace(/\btel:\+?[0-9][0-9\-().\s]{4,18}/gi, `tel:${HIDDEN}`);
  s = s.replace(/\b[A-Za-z0-9._%+-]+(@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b/g, `${HIDDEN}$1`);
  s = s.replace(/(?<!\w)\+[1-9]\d{6,14}\b/g, HIDDEN);
  s = s.replace(/(?<![\w.])(?!127\.0\.0\.1(?![\w.]))(?!198\.19\.0\.\d+(?![\w.]))(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}(?![\w.])/g, HIDDEN);
  s = s.replace(/\b(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{0,4}\b/gi, HIDDEN);
  s = s.replace(/\b\d{14,22}\b/g, HIDDEN);
  return s;
}

function maskSensitiveValue(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === "string") return value === "" ? "" : HIDDEN;
  if (Array.isArray(value)) return value.map(() => HIDDEN);
  if (typeof value === "object") {
    const out = {};
    let n = 0;
    for (const k of Object.keys(value)) {
      n++;
      out[k] = HIDDEN;
    }
    return out;
  }
  return HIDDEN;
}

function deepSanitize(value, parentKey = "", depth = 0, complete = false) {
  if (value === null || value === undefined) return null;
  if (depth > (complete ? 48 : 12)) { if(complete) throw new Error("TELEMETRY_STRUCTURE_TOO_DEEP"); return HIDDEN; }
  if (typeof value === "boolean" || typeof value === "number") return value;
  if (typeof value === "string") {
    if (parentKey === "profile" && /^[06]@(nai\.epc|wlan)\/ims(-fqdn)?$/.test(value)) return value;
    const maxLen = complete ? value.length : (parentKey === "rawLogs" ? 260 * 1024 : 8192);
    return sanitizeString(value, maxLen);
  }
  if (Array.isArray(value)) {
    const pk = parentKey.toLowerCase();
    const maskItems = NODE_LIST_KEYS.has(pk);
    return value.slice(0, complete ? value.length : 140).map((item) => {
      if (maskItems && typeof item === "string") {
        return item === "DIRECT" || item === "REJECT" || item === "VoWiFi" ? item : HIDDEN;
      }
      return deepSanitize(item, parentKey, depth + 1, complete);
    });
  }
  if (typeof value === "object") {
    const pk = parentKey.toLowerCase();
    const maskChildKeys = NODE_MAP_KEYS.has(pk);
    const out = {};
    let count = 0;
    for (const [k, v] of Object.entries(value)) {
      if (count++ >= 140 && !complete) break;
      const kl = k.toLowerCase();
      const outKey =
        maskChildKeys && k !== "VoWiFi" && k !== "DIRECT" && k !== "REJECT" && k !== "GLOBAL"
          ? `${HIDDEN}#${count}`
          : k;
      if (SENSITIVE_KEYS.has(kl)) {
        if (typeof v === "string" && ["username","impi","impu","sipimpu","sipuri","peer","remote","caller","callee","email"].includes(kl) && v.includes("@")) {
          out[outKey] = sanitizeString(v, complete ? v.length : 8192);
        } else if (kl === "authorization" && v && typeof v === "object" && !Array.isArray(v)) {
          out[outKey] = deepSanitize(v,k,depth+1,complete);
        } else { out[outKey] = maskSensitiveValue(v); }
      } else if (kl === "name" && (pk === "nodes" || pk === "proxies" || pk === "requestbody" || pk === "responsebody")) {
        const sv = v ? String(v) : "";
        out[outKey] = (!sv || sv === "VoWiFi" || sv === "DIRECT" || sv === "REJECT" || sv === "GLOBAL") ? sv : HIDDEN;
      } else {
        out[outKey] = deepSanitize(v, k, depth + 1, complete);
      }
    }
    return out;
  }
  return null;
}

function base64Utf8(str) {
  const bytes = new TextEncoder().encode(str);
  const chunkSize = 0x8000;
  let binary = "";
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

function randomHex(len = 6) {
  const bytes = new Uint8Array(Math.ceil(len / 2));
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("").slice(0, len).toUpperCase();
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
      return jsonResponse({
        ok: true,
        service: "smartvowificall-telemetry-relay",
        schema: "smartvowificall.telemetry.v3",
        supportedSchemas: Array.from(SUPPORTED_SCHEMAS),
        maxBytes: MAX_BYTES,
        configured: Boolean(env.GITHUB_TOKEN && env.GITHUB_REPO),
      });
    }

    if (request.method !== "POST" || url.pathname !== "/v1/telemetry") {
      return jsonResponse({ ok: false, code: "NOT_FOUND" }, 404);
    }

    if (env.INGEST_TOKEN) {
      const provided = request.headers.get("X-Telemetry-Key") || "";
      if (provided !== env.INGEST_TOKEN) {
        return jsonResponse({ ok: false, code: "UNAUTHORIZED" }, 401);
      }
    }

    if (!env.GITHUB_TOKEN || !env.GITHUB_REPO) {
      return jsonResponse({ ok: false, code: "RELAY_NOT_CONFIGURED" }, 503);
    }

    const contentLength = Number(request.headers.get("content-length") || "0");
    if (contentLength > MAX_BYTES) {
      return jsonResponse({ ok: false, code: "PAYLOAD_TOO_LARGE" }, 413);
    }

    const raw = await request.text();
    if (!raw || raw.length > MAX_BYTES) {
      return jsonResponse({ ok: false, code: "PAYLOAD_TOO_LARGE" }, 413);
    }

    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return jsonResponse({ ok: false, code: "INVALID_JSON" }, 400);
    }

    if (
      !parsed ||
      typeof parsed !== "object" ||
      !SUPPORTED_SCHEMAS.has(parsed.schema) ||
      typeof parsed.installId !== "string" ||
      !UUID_RE.test(parsed.installId) ||
      typeof parsed.app !== "object" ||
      typeof parsed.device !== "object" ||
      typeof parsed.service !== "object"
    ) {
      return jsonResponse({ ok: false, code: "INVALID_SCHEMA" }, 400);
    }

    const now = new Date();
    const yyyy = now.getUTCFullYear();
    const mm = String(now.getUTCMonth() + 1).padStart(2, "0");
    const dd = String(now.getUTCDate()).padStart(2, "0");
    const candidateId = typeof parsed.logId === "string"
      ? parsed.logId.trim().toUpperCase()
      : (request.headers.get("X-SmartVoWifiCall-Log-Id") || "").trim().toUpperCase();
    const logId = LOG_ID_RE.test(candidateId)
      ? candidateId
      : `SVW-${yyyy}${mm}${dd}-${randomHex(6)}`;

    const complete = parsed.schema === "smartvowificall.telemetry.v3";
    if (complete) {
      const b = parsed.authBatch;
      if (!b || !LOG_ID_RE.test(b.id) || !Number.isInteger(b.index) || !Number.isInteger(b.count)
          || b.index < 1 || b.index > b.count || b.count > 256
          || !Array.isArray(parsed.authPackets) || b.recordCount !== parsed.authPackets.length
          || !Number.isInteger(b.totalRecordCount) || b.totalRecordCount < b.recordCount) {
        return jsonResponse({ok:false,code:"INVALID_AUTH_BATCH"},400);
      }
    }
    let sanitized;
    try { sanitized = deepSanitize(parsed,"",0,complete); }
    catch { return jsonResponse({ok:false,code:"TELEMETRY_STRUCTURE_TOO_DEEP"},400); }
    sanitized.logId = logId;

    const recordPath = `logs/${yyyy}/${mm}/${dd}/${logId}.json`;
    const record = {
      logId,
      receivedAt: now.toISOString(),
      cfCountry: request.cf && request.cf.country ? String(request.cf.country) : "",
      payload: sanitized,
    };

    const branch = env.GITHUB_BRANCH || "main";
    const ghUrl = `https://api.github.com/repos/${env.GITHUB_REPO}/contents/${recordPath}`;
    const ghToken = String(env.GITHUB_TOKEN || "").trim();
    const ghRes = await fetch(ghUrl, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${ghToken}`,
        Accept: "application/vnd.github+json",
        "User-Agent": "SmartVoWifiCall-Telemetry-Relay/3.0",
        "Content-Type": "application/json",
        "X-GitHub-Api-Version": "2022-11-28",
      },
      body: JSON.stringify({
        message: `telemetry: ${logId}`,
        branch,
        content: base64Utf8(JSON.stringify(record, null, 2) + "\n"),
      }),
    });

    if (!ghRes.ok) {
      let ghMsg = "";
      try {
        const errJson = await ghRes.json();
        if (errJson && typeof errJson.message === "string") ghMsg = errJson.message.slice(0, 120);
      } catch {}
      return jsonResponse(
        { ok: false, code: `GITHUB_HTTP_${ghRes.status}`, logId, detail: ghMsg },
        ghRes.status === 429 ? 429 : 502
      );
    }

    return jsonResponse({
      ok: true,
      code: "TELEMETRY_STORED",
      schema: parsed.schema,
      ...(complete ? {authBatchId: sanitized.authBatch.id, authPartIndex: sanitized.authBatch.index,
          authRecordCount: sanitized.authPackets.length} : {}),
      logId,
      path: recordPath,
    });
  },
};
