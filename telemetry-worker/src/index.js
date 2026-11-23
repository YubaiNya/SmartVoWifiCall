/**
 * SmartVoWifiCall Telemetry Relay (Cloudflare Worker)
 *
 * Receives strictly sanitized telemetry JSON from the Android app and stores it as a dated JSON
 * file in a private GitHub repository via the GitHub Contents API. The GitHub token lives ONLY
 * in Cloudflare Worker secrets (never inside the APK or public repository).
 *
 * Required secrets / env vars:
 *   - GITHUB_TOKEN (secret): Fine-grained PAT with `Contents: Read and write` scoped ONLY to GITHUB_REPO
 *   - GITHUB_REPO (var): e.g. "YubaiNya/SmartVoWifiCall-Telemetry"
 *   - GITHUB_BRANCH (var): e.g. "main"
 *   - INGEST_TOKEN (optional secret): if set, requires matching `X-Telemetry-Key` header
 */

const MAX_BYTES = 64 * 1024;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const FORBIDDEN_KEYS = new Set([
  "iccid", "imsi", "imei", "msisdn", "phone", "number", "peer", "caller", "callee",
  "simkey", "subscriptionurl", "controllersecret", "secret", "token", "password",
  "authorization", "nonce", "autn", "rand", "res", "ik", "ck", "privatekey",
  "impi", "impu", "sipimpu", "currentnode", "savednode", "preferredclashnode",
  "lanipv4", "serial", "androidid"
]);

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

function sanitizeString(input) {
  let s = String(input);
  s = s.replace(/\b89\d{13,20}[0-9A-Fa-f]{0,2}\b/g, "[REDACTED_ICCID]");
  s = s.replace(/\bhttps?:\/\/[^\s"'<>]+/gi, "[REDACTED_URL]");
  s = s.replace(/\bsip:[^@\s"'<>]+@/gi, "sip:[REDACTED]@");
  s = s.replace(/\btel:\+?[0-9][0-9\-().\s]{5,18}/gi, "tel:[REDACTED]");
  s = s.replace(/\b(?!127\.0\.0\.1\b)(?!198\.19\.0\.\d+\b)(?:\d{1,3}\.){3}\d{1,3}\b/g, "[REDACTED_IP]");
  s = s.replace(/\b(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{0,4}\b/gi, "[REDACTED_IP6]");
  s = s.replace(/\b\d{14,22}\b/g, "[REDACTED_ID]");
  return s.length > 512 ? s.slice(0, 512) : s;
}

function deepSanitize(value, depth = 0) {
  if (depth > 8 || value === null || value === undefined) return null;
  if (typeof value === "boolean" || typeof value === "number") return value;
  if (typeof value === "string") return sanitizeString(value);
  if (Array.isArray(value)) {
    return value.slice(0, 100).map((item) => deepSanitize(item, depth + 1));
  }
  if (typeof value === "object") {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      if (FORBIDDEN_KEYS.has(k.toLowerCase())) continue;
      out[k] = deepSanitize(v, depth + 1);
    }
    return out;
  }
  return null;
}

function base64Utf8(str) {
  const bytes = new TextEncoder().encode(str);
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}

function randomHex(len = 6) {
  const bytes = new Uint8Array(Math.ceil(len / 2));
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("").slice(0, len);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
      return jsonResponse({
        ok: true,
        service: "smartvowificall-telemetry-relay",
        schema: "smartvowificall.telemetry.v1",
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
      parsed.schema !== "smartvowificall.telemetry.v1" ||
      typeof parsed.installId !== "string" ||
      !UUID_RE.test(parsed.installId) ||
      typeof parsed.app !== "object" ||
      typeof parsed.device !== "object" ||
      typeof parsed.service !== "object"
    ) {
      return jsonResponse({ ok: false, code: "INVALID_SCHEMA" }, 400);
    }

    const sanitized = deepSanitize(parsed);
    const now = new Date();
    const yyyy = now.getUTCFullYear();
    const mm = String(now.getUTCMonth() + 1).padStart(2, "0");
    const dd = String(now.getUTCDate()).padStart(2, "0");
    const stamp = now.toISOString().replace(/[:.]/g, "-");
    const shortId = parsed.installId.slice(0, 8).toLowerCase();
    const recordPath = `logs/${yyyy}/${mm}/${dd}/${stamp}-${shortId}-${randomHex(6)}.json`;

    const record = {
      receivedAt: now.toISOString(),
      cfCountry: request.cf && request.cf.country ? String(request.cf.country) : "",
      payload: sanitized,
    };

    const branch = env.GITHUB_BRANCH || "main";
    const ghUrl = `https://api.github.com/repos/${env.GITHUB_REPO}/contents/${recordPath}`;
    const ghRes = await fetch(ghUrl, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: "application/vnd.github+json",
        "User-Agent": "SmartVoWifiCall-Telemetry-Relay/1.0",
        "Content-Type": "application/json",
        "X-GitHub-Api-Version": "2022-11-28",
      },
      body: JSON.stringify({
        message: `telemetry: ${yyyy}-${mm}-${dd} ${shortId}`,
        branch,
        content: base64Utf8(JSON.stringify(record, null, 2) + "\n"),
      }),
    });

    if (!ghRes.ok) {
      return jsonResponse(
        { ok: false, code: `GITHUB_HTTP_${ghRes.status}` },
        ghRes.status === 429 ? 429 : 502
      );
    }

    return jsonResponse({
      ok: true,
      code: "TELEMETRY_STORED",
      path: recordPath,
    });
  },
};
