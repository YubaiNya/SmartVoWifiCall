# VoWiFi Engine 0.3.48 interface configuration evidence

The bundled original engine already supports `iface:<name>`. The observed failure
of `config-create-validate` does **not** establish lack of interface support:
creation deliberately requires a `wifi` initial template. No binary patch or
`deps/vowifi-engine-arm64-iface` output was made.

## Artifact and method

- Input: `service/deps/vowifi-engine-arm64`, 10,964,848 bytes.
- SHA-256: `11864d79088731cb898518d21694e4d4068eb680e0ae11cc7f5b7f0a06a53be7`.
- REA Go metadata Evidence: `ev_d212bc450f5880a42bb62de1203cffbf6cb2754ea7eb1a6d35fa1199e10e7099`.
  Embedded compiler metadata reports `go1.26.9`, Android/arm64. Metadata does not
  authenticate upstream source or supply a redistribution license.
- Function boundaries: the original `.gopclntab`, with the exact artifact's
  relocated text start `.text + 0x100`. Instructions: installed Capstone.
- Executed evidence: installed Unicorn replays selected original instructions
  with explicit boundary substitutes. The Android executable is never launched.
- REA native Windows ELF provider was previously unavailable; it was not retried,
  installed, or reconfigured. These instruction findings are local analysis,
  separate from the REA metadata Evidence above.

## Verified configuration chain

| Function / VA | Observed behavior |
| --- | --- |
| `config.ValidTransport`, `0x6c1990` | Accepts legacy `wifi`, `sim1`, `sim2`, `auto`, `route:system`, or delegates to `netselector.Interface`. |
| `netselector.Interface`, `0x6c0090` | Requires literal `iface:` at file-backed VA `0x85dd`; suffix 1–15 bytes; rejects `.` / `..`; accepts ASCII letters, digits, underscore, hyphen, dot. |
| `config.(*CardConfig).Validate`, `0x6c20f0` | Calls `ValidTransport` at `0x6c21b0`; also retains carrier, IMS identity, DNS, and policy validation. |
| `config.ParseJSON`, `0x6c2500` | JSON decode, then `CardConfig.Validate` at `0x6c25cc`. |
| `config.validateCreateBytes`, `0x6c2780` | First calls `ParseJSON`; additional creation checks at `0x6c292c..0x6c2948` require transport exactly `wifi`. An otherwise valid `iface:tun0` runtime configuration fails this separate template requirement. |
| `main.verifyConfig`, `0x82c280` | Reads the candidate, calls `ParseJSON` at `0x82c364`, then obtains SIM identity and calls `matchConfig`. Thus `config-verify --strict` includes runtime field validation, followed by identity verification. |
| `main.runConfigApply`, `0x82bbd0` | Requires two positional arguments: target configuration path, patch-file path; calls `config.ApplyConfig`. |
| `config.ApplyConfig`, `0x6c0670` | Reads patch file and target; calls `MergeAllowlistedPatch`; tests for unchanged allowed fields; writes successful changes with `writeAtomic` at `0x6c0740`. |
| `config.MergeAllowlistedPatch`, `0x6c6980` | Decodes patch directly into a top-level object, checks each top-level key, merges those keys, marshals, and calls `ParseJSON` at `0x6c6bc8` before output. There is no `fields` wrapper. |

The patch file shape is:

```json
{"transport":"iface:tun0"}
```

For this independently owned service, the complete preparation sequence is:

1. Construct and validate the allowed initial `wifi` template using
   `config-create-validate`.
2. Copy that template to a separate temporary final configuration and use
   `config-apply <temporary-final-file> <patch-file>` to apply the chosen transport.
   This passes the actual merge allowlist and complete runtime field validation.
3. Run `config-verify --strict <temporary-final-file>` against the intended SIM.
4. Commit the verified temporary file atomically to this service's destination.

The parent agent separately reported on-device `config-verify --strict` success
for the same `iface:tun0` candidate (`IDENTITY_MATCH`). This document's replay
does not claim that device observation as its own execution. No call or SMS was
attempted by this analysis.

## Selection, source addresses, and binding

`main.selectRuntimeNetwork` (`0x8439e0`) forwards nonempty selector bytes to
`Registry.Select` (`0x746ad0`), which invokes `Snapshot.SelectObserved`
(`0x746cb0`). The latter calls `netselector.Interface` and compares the complete
suffix against candidate `Network.Interface` using length plus `runtime.memequal`
at `0x746fbc..0x746fe8`. No matching usable candidate follows the existing
no-available-network return. Replay confirms `tun0` does not match `tun01` or
`tun`, an explicit interface named `wifi` is not interpreted as Wi-Fi mode, and
an absent interface does not fall back to `wlan0`.

Selected candidates pass through `bindingFor` (`0x747a00`) and `sourceBinding`
(`0x747d10`). Static instructions copy the candidate interface and transport,
then parse candidate address strings into the first IPv4 / IPv6 source fields
at Binding offsets `0x48` / `0x58`. `Binding.SourceIP` is at `0x748c20`.
This is static source-address flow evidence, not a proof of the source address
seen on a real packet after Android routing or a VPN implementation.

The original `Binding.Control.func1` (`0x744130`) calls Android platform-network
binding first, then `SetsockoptString(fd, SOL_SOCKET=1, SO_BINDTODEVICE=25, iface)`.
A platform-binding error prevents the second call. Binding byte `+0x78` skips
the platform-network bind while preserving device bind. Replay verifies these
arguments and ordering, with both socket boundaries substituted.

`directTUNBinding` (`0x7457e0`) exists, but the observed direct call comes from
`Snapshot.selectProxy` at `0x745668`, through `Registry.SelectProxy`. Its callers
include `main.tunnelConfigFromCard.func1` / `.func3`. Ordinary `SelectObserved`
does not directly fall back to this path. Therefore, the presence of native TUN
support does not prove that every ordinary selector can use a handle-free TUN;
that depends on the actual snapshot and proxy path. This distinction remains
relevant for device testing.

No `pinBoundSocketUDP` function name was found in the recovered Go function
inventory, and the inspected old interface patch does not implement such a
patch. This bounded negative result does not rule out equivalent inlined or
differently named socket logic. No UDP header or source-IP patch was applied.

## Reproduction and limits

```powershell
python D:\SmartVoWifiCall\service\tests\engine\test_native_selectors.py
python D:\SmartVoWifiCall\service\tools\engine_iface\inspect_native.py 'vowifi_engine/internal/config.ValidTransport'
```

Result: **290 passing assertions** against the unmodified, hash-locked input:
selector modes and rejection cases, all 256 interface byte values, full transport
validation, strict creation checks, exact candidate selection, absence behavior,
and socket-control ordering. JSON decoding, unrelated policy checks, allocation,
candidate binding construction, observer logging, and OS socket operations have
explicit substitutes. No actual Android process, socket, IKE, IMS, call, or SMS
executes. Hardware routing, actual source packets, and carrier registration
remain device-test questions.

Historical comparison source:
`D:\vowifi\patch\engine\patch_engine.py`, `test_engine.py`, and `native-report.md`
describe a different 0.3.45 artifact and a different persisted schema: bare names
plus explicit `mode:` tokens. Its offsets and migration are not transferable to
this 0.3.48 artifact without new evidence, and are unnecessary for this blocker.
