# Authentication repair and telemetry v3 — v0.6.2-dev

Baseline: `560ed28553979c45c349babaae2c6e1a107f356e`. Candidate version: `0.6.2-dev` / 22.

## What the evidence establishes

POCO F3 telemetry `SVW-20261125-F9295B` contains six separate IKE_SA_INIT / EAP-AKA challenge / final Notify 24 failures. The generic rejection does not establish whether the carrier rejected the AUTH calculation or another authorization requirement. Subsequent Xiaomi 15 / 3HK registration passed as described below; POCO F3 / giffgaff remains unverified.

An independent loopback verifier uses public AOSP AKA keys and expected responses, captured initial IKE bytes, and an independently implemented HMAC/key expansion. It accepts the actual Go client's final AUTH with both AES-128/SHA-256 and the F3's AES-256/SHA-256 combination. A one-bit-corrupted AUTH is rejected. The harness shares packet encoding/decoding and DH utilities with the product; it is not a deployed strongSwan or AOSP interoperability test.

## Repairs

- Send EAP_ONLY_AUTHENTICATION (16417) in the first IKE_AUTH request.
- Require an authenticated AKA challenge and an explicit EAP-Success before final AUTH. Stop with a specific error when the bounded 16-round exchange is exhausted.
- Require the AKA server MAC; retain DEVICE_IDENTITY requests across rounds and answer only after authenticating the challenge.
- Validate the final responder AUTH using the full EAP MSK, the responder's original IKE_SA_INIT message, initiator nonce, responder ID and SK_pr before accepting the tunnel.
- Distinguish final-phase Notify errors from pre-EAP rejection. Final failures are not blindly retried with every identity profile.
- Preserve the original radio-release error code instead of replacing it with RADIO_RELEASE_FAILED.

The first three defects were reproduced before repair. The round-exhaustion sequence does not match the F3 trace, so fixing it is not proof of resolving that device's failure.

## Authentication journal and privacy

The engine records sanitized IKE_SA_INIT/IKE_AUTH packets and SIP REGISTER requests/responses in `auth.jsonl`, separately from ordinary diagnostic logs. Records contain direction, slot, session, sequence, exchange/message identifiers, negotiated/public payload bytes, EAP attribute structure, explicit success/failure stages and actual engine build metadata. No derived session/SIM keys are logged.

| Data | Retained representation |
| --- | --- |
| NAI / SIP identity | Protocol prefix and carrier realm preserved; subscriber part replaced with `[Hidden]` |
| IKE header, SA proposals, public DH values, IKE nonce, AKA RAND | Nonprivate fields and bytes preserved |
| AUTH / EAP MAC / RES / AUTS | Method, attribute type, lengths and nonsecret header bits retained; proof/key material masked |
| AUTN | AMF retained; concealed sequence and authentication token masked |
| DEVICE_IDENTITY | Notify, length and identity type retained; IMEI/IMEISV masked |
| SIP Digest | Scheme, realm, algorithm, qop and nonce count retained; AKA nonce public RAND/AMF retained separately; original token, response and private authentication material masked |
| Assigned UE address, contact address, display name, cell location | Private portions masked; other header parameters retained |
| Unclassified opaque extensions | Type and length retained, value explicitly marked as redacted rather than assuming arbitrary bytes are public |

Each engine retains the current and previous 256 KiB journals. This is a bounded retention window, not an unlimited archive. Collection reads both generations as complete JSON records. Invalid/partial lines and conflicting sequence records fail explicitly. Write failures and sequence gaps are reported in `authCapture`; the upload summary exposes capture gaps. The telemetry toggle remains off by default. Enabling it or manually uploading automatically includes retained authentication records.

## Upload completeness and compatibility

Telemetry v3 separates `authPackets` from the ordinary log tail. Packets never pass through the old per-string/per-array truncation. Uploads are partitioned below 480 KiB with `authBatch.id`, `index`, `count`, `recordCount` and `totalRecordCount`. Each part has its own log ID. The client checks the server's v3 receipt for the batch ID, part index and stored record count before reporting success. Oversized individual records, partial upload and receipt mismatch are explicit failures.

The Worker retains v1/v2 compatibility and accepts v3 without silently truncating its arrays or strings. **The updated Worker must be deployed before using the candidate's v3 uploader.** An old Worker rejects v3 instead of silently losing data. Worker v3 was subsequently deployed and verified as described below.

## Reproduction

```powershell
cd D:\NewVowifi\engine
& 'C:\Program Files\Go\bin\go.exe' test ./...
cd D:\NewVowifi
python -X utf8 service/tests/policy/run_tests.py
$env:TELEMETRY_BATCH_FIXTURE = 'D:\NewVowifi\service\tests\policy\.build\telemetry-batch.json'
node --test telemetry-worker/tests/telemetry.test.mjs
python -X utf8 service/tools/build.py
python -X utf8 service/tools/verify_artifacts.py
```

The Java-to-Worker integration fixture has 260 records with 12,000-character public values. Tests require complete preservation across all parts, partial-field redaction, bounded part sizes and matching receipts. Engine tests also check private bytes do not enter journals, malformed payloads are handled without opaque dumps, and forged responder AUTH is refused.

## Reference boundaries

- [AOSP IKE authentication](https://android.googlesource.com/platform/packages/modules/IPsec/+/refs/heads/main/src/java/com/android/internal/net/ipsec/ike/message/IkeAuthPskPayload.java) and [EAP-AKA public vectors](https://android.googlesource.com/platform/packages/modules/IPsec/+/refs/heads/main/tests/iketests/src/java/com/android/internal/net/eap/EapAkaTest.java).
- [AOSP 3GPP device identity handling](https://android.googlesource.com/platform/packages/modules/IPsec/+/refs/heads/main/src/java/com/android/internal/net/ipsec/ike/ike3gpp/Ike3gppIkeAuth.java).
- [strongSwan EAP authenticator](https://github.com/strongswan/strongswan/blob/master/src/libcharon/sa/ikev2/authenticators/eap_authenticator.c) and [RFC 5998](https://www.rfc-editor.org/rfc/rfc5998.html).
- SoftVoWiFi public client reviewed at `a19adb51cce532c5d4d51f5c5aa0bcb37f758f51`: its public repository excludes the private engine; no claim was made to have inspected that engine's AUTH source.
- VoWiFiX reviewed at `0d71dc9e90c8f3369f09b038cca059cbe3d556ed`: its framework/baseband configuration behavior is not an independent IKE AUTH implementation. No EFS or baseband modification was copied or executed.


## Local execution result — 2026-11-26

- Go engine suite and focused independent authentication/privacy regressions: passed.
- `go vet ./...`: passed.
- Pure Java policy/network/telemetry runner: passed, including 260 retained authentication records across 7 upload parts.
- Worker tests: 4 passed, including real Java-generated multipart data processed without payload truncation.
- APK/module build, APK signature, archive integrity and embedded APK consistency: passed.
- Packaged ARM64 engine source digest matches the final Go source files.

## Subsequent device and production validation — 2026-11-26

- Xiaomi 15 (Android 17), 3HK in slot 2, slot 1 mobile data through the existing Hong Kong proxy: EAP-Success, final AUTH verification and SIP REGISTER 200 passed. Registration remained established for approximately 136 seconds. This was not a Wi-Fi transport test; no calls or SMS were attempted. Original slot switches and proxy selection were restored.
- Worker v3 deployed with the existing secret bindings preserved. Previous code, recoverable configuration and deployment version were backed up locally and verified with SHA-256 before upload.
- Production health reports v3 with v1/v2 compatibility. A synthetic upload retained all 180 authentication records and a 20,000-character public value; identity subscriber portions and synthetic secrets were masked while realm and algorithm remained visible. The matching receipt was verified; the synthetic record was deleted and its absence verified.
- These results do not establish the root cause or resolution of the POCO F3 / giffgaff rejection. New F3 telemetry is required.
