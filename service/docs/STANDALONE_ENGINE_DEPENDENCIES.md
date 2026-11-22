# Standalone engine dependency audit

## Scope and result

The bundled 0.3.48 VoWiFi protocol engine can use its built-in QRTR SIM providers without the installed `com.legacy.ims` APK or `/data/adb/modules/legacy_ims` files. A standalone wrapper must supply its own configuration, fresh network snapshot, private state directories, and required Android/kernel facilities. Bundling the engine does not make those inputs unnecessary.

This is offline static analysis of baseline SHA-256 `11864d79088731cb898518d21694e4d4068eb680e0ae11cc7f5b7f0a06a53be7`, plus local verification of the four-path derived candidate SHA-256 `f53df163c7731ebeba45ebf913e85cace0af2884647359155ed515e137b479b3`. All code is identical. Earlier target-free REA metadata evidence is `ev_d212bc450f5880a42bb62de1203cffbf6cb2754ea7eb1a6d35fa1199e10e7099`; addresses below come from `.gopclntab`, Go type metadata, and Capstone, not a successful native REA provider. No phone/ADB operation or real engine execution was performed.

## APK, module, and SIM dependencies

- `module/control.sh` launches the bundled engine with explicit `--sim-mode=qrtr`, config, PID, and log paths. Preserve that argument: the engine's default SIM mode is `java`, with bridge address `127.0.0.1:18080`.
- The QRTR startup branch in `main.main` calls `bridge.NewQRTRSIMProvider` at VA `0x8391ac`. That constructor (`0x822700`) converts slot to the provisioning session and calls `qmiqrtr.New` at `0x822744`.
- `qmiqrtr.New` (`0x808cb0`) calls `NewQRTR`; the latter invokes `socket(42, 2, 0)` at `0x808be0..0x808bec` (AF_QIPCRTR / datagram). Service discovery is for service `0x0b` at `0x808ce8..0x808cec`. AKA uses the native client's `Authenticate3G` or `AuthenticateIMS`, evidenced by provider interface tables at `0x9a4b58` and `0x9a5ba0`.
- `config-verify --strict` also uses native QRTR, independently of the runtime SIM-mode flag. `main.verifyConfig` (`0x82c280`) reads the candidate and invokes `config.ParseJSON` at `0x82c364`. Its factory global `0x9bdc48` points through `0x99cf90` to `main.init.func1` (`0x825540`), which calls `qmiqrtr.New` at `0x825564`. The returned interface table (`0x9a57c0`) contains `Close`, `MatchICCID`, and `MatchIMSI`; `matchConfig` calls the two identity comparisons at `0x82c8dc` and `0x82c9a4`. Strict mode converts unverifiable or mismatching identity into failure. No old Java provisioning helper is part of this validator chain.
- The wrapper originally invoked `com.legacy.ims.ProvisioningIdentityMain` via a `pm path com.legacy.ims` classpath lookup. During this audit the parent replaced that path with internal `SimCredentials.read` in `RootIdentityMain` and removed the installer/package-query requirement. This audit did not implement or independently validate that replacement's phone behavior.
- The engine's entire artifact contains no `com.vowifi.`, `app_process`, `CLASSPATH`, or `legacy_ims` literal. This negative search supports the traced QRTR path; it is not a proof about every possible indirect Android system dependency.
- ELF interpreter is `/system/bin/linker64`. `DT_NEEDED` lists only `libandroid.so`, `liblog.so`, `libdl.so`, and `libc.so`. No old module-specific shared library appears in that dependency table. The imported `android_setsocknetwork` requires real Android network handles. QRTR/UIM, Android platform networking, TUN, XFRM, routing, and permissions remain device dependencies.
- `deps/legacy-lib` contains historical shell references to the old APK/module. They are reference material, not sourced by `module/control.sh`. Do not introduce them into the standalone runtime.

## Four relocated data paths

The approved data-only patch changes the six-character component `vowifi` to `nvwims` in four unique literals; there are exactly 16 changed bytes. There is no remaining `/data/adb/vowifi/` literal in the derived artifact. Original binary, all code sections, headers, mappings, relocations, and Go metadata remain unchanged.

| Consumer | Original literal VA/file offset | Current destination and obligation |
| --- | --- | --- |
| Default log root and diagnostic exporter | `0x1302d`, 21 bytes | `/data/adb/nvwims/logs`. Explicit `--log-dir` overrides normal startup's root. The diagnostic exporter still uses its fixed root, so it does not export an unrelated explicit log directory. |
| Managed log pool | `0x17b35`, 27 bytes | `/data/adb/nvwims/state/logs`. Original pointer/length global `0x9be860` remains unchanged. |
| Call admission | `0x1a65d`, 30 bytes | `/data/adb/nvwims/run/call.lock`. The caller must create the parent directory. |
| Network registry | `0x1e2b3`, 34 bytes | `/data/adb/nvwims/run/networks.json`. Requires an independent, continuously refreshed producer. |

Call-lock evidence: `main.productionIMSConfigWithInterface` copies the config template at `0x9afc60` through `0x830694`; `CallAdmissionPath` is template offset `0x78`, containing the string pair at `0x9afcd8` (file offset `0x9a7cd8`). `ims.acquireCallAdmission` (`0x79d3a0`) performs `OpenFile(path, 0x42, 0600)` at `0x79d3c0..0x79d3c8`, then `Flock(LOCK_EX|LOCK_NB)` through `lockCallAdmission`. It does not call `MkdirAll`. Direct callers include `IMS.Dial` at `0x79c284` and incoming `acceptCreatedInvite` at `0x7f1908`. Relocate the lock; do not disable its admission semantics. No calls were executed.

The shared engine control/audio socket directory remains `/data/local/tmp/vowifi/`; this is an IPC namespace, not proof of a running old APK/module. Existing owner/token checks must still prevent simultaneous ownership of a SIM endpoint.

## Network registry contract

`underlay.RuntimeRegistry` (`0x7460a0`) reads and trims `/proc/sys/kernel/random/boot_id`, then constructs a registry with the fixed network-file literal at `0x746140`. The runtime flag list has no registry-path override. Patching the literal changes both readers and `network-update` publication together; no code branch was patched.

The explicit interface path does not eliminate the registry requirement. `tunnelConfigFromCard.func3` calls `RuntimeRegistry` at `0x8441cc`, recognizes `iface:` at `0x844344`, and calls `Registry.SelectProxy` at `0x8443d0`. `SelectProxy` first calls `Registry.Read` at `0x744f58`. A missing, unreadable, malformed, wrong-boot, or stale snapshot returns an error before direct TUN fallback. `Registry.Read` uses `os.OpenFile` at `0x74620c` and a 32,769-byte limited reader before parsing; the parser's maximum accepted input is 32,768 bytes.

Exact JSON field names come from Go `Snapshot` type VA `0x975600` and `Network` type VA `0x9698e0`:

```text
{
  version: integer,
  bootId: string,
  observedMs: signed integer,
  defaultHandle: unsigned 64-bit integer,
  airplaneMode?: boolean,
  accessGeneration?: unsigned 64-bit integer,
  networks: [{
    handle: unsigned 64-bit integer,
    interface: string,
    transport: string,
    slots?: integer[],
    validated: boolean,
    addresses: string[],
    dns: string[]
  }]
}
```

Validation in `ParseObserved` (`0x747ee0`):

- Versions 1 and 2 are accepted (`0x748088..0x74809c`). Version 1 accepts `wifi` and `cellular`; version 2 additionally accepts `vpn` and `other` (`0x748450..0x748510`). Use version 2 when publishing VPN networks.
- `bootId` must exactly match the trimmed current kernel boot ID. `observedMs` is **CLOCK_BOOTTIME milliseconds**, equivalent to Android elapsed realtime, not wall time. It must be nonnegative, no greater than the reader's current time, and at most 15,000 ms old (`0x7480dc..0x748100`). The clock implementation is `RuntimeRegistry.func1` (`0x749080`), invoking `ClockGettime(7, ...)` and converting nanoseconds to milliseconds. A five-second refresh is the integration's chosen interval.
- At most 16 network entries; handles must be nonzero and unique. Use **`Network.getNetworkHandle()`**, not netId. The historical app projection writes that exact API result in `AndroidUnderlayObserver.smali:822..828`; the engine passes the handle to Android's native socket-binding API.
- Interface regex is `^[A-Za-z0-9_.-]{1,15}$` (literal VA `0x141e8`, compiled in `underlay.init`).
- Each entry needs 1–16 addresses and 1–8 DNS addresses. Values are plain numeric IP addresses, **without CIDR suffixes**, because the parser calls `netip.ParseAddr` at `0x748720`. It rejects zones, IPv4-mapped IPv6, non-global-unicast and loopback addresses. Here global-unicast is Go's address classification; ordinary private unicast addresses are not excluded merely for being private.
- `slots` has at most two entries, only for `cellular`, containing distinct slot 0/1 values (`0x7485cc`, `0x748630..0x748678`). Preserve actual observed `validated` state rather than inventing it.
- A malformed entry rejects the whole snapshot; filter unsuitable observations before publication. The snapshot parser accepts an empty network list, but that does not guarantee a usable underlay.

`main.runNetworkUpdate` (`0x83c320`) reads stdin, obtains `RuntimeRegistry` at `0x83c494`, and calls `Registry.Publish` at `0x83c4d8`. Publish calls `ParseObserved` before `publishSnapshot`; the latter creates a private parent directory (`MkdirAll`, mode 0700, at `0x74687c`), writes a temporary JSON file, and renames it at `0x74690c`. Reusing this bundled CLI for publication retains the original parser and atomic-write behavior.

Direct TUN details: `Snapshot.selectProxy` tries normal observed selection first (`0x745370`). If that fails and the selected interface already occurs in the snapshot, it returns unavailable (`0x74548c..0x745534`) rather than bypassing the observed failure. Otherwise, explicit `iface:` may reach sysfs/interface checks and `directTUNBinding` at `0x745668`. That function obtains the TUN's own addresses and uses transport `other`. When no explicit DNS override is supplied, it looks up DNS from the snapshot's `defaultHandle` at `0x745da4..0x745dfc`; an empty list supplies no DNS and fails the later source/DNS checks. Therefore empty snapshots are not a working standalone strategy for the current empty-DNS config. A real usable VPN entry is supported through version 2; it must meet the same parser and selection conditions as other observed entries.

### Optional access-state fields

Omitting `airplaneMode` and `accessGeneration` does **not** block explicit-interface selection. It does remove supplementary change signals; it is not equivalent to publishing all observed access events.

- `Binding` type VA `0x97ea40` names offsets `+0x68 AirplaneModeKnown`, `+0x69 AirplaneMode`, and `+0x70 AccessGeneration`. `Snapshot.SelectObserved` only copies these snapshot values into the successfully selected binding at `0x747388..0x7473a4`; a nil airplane-mode pointer skips the copy and leaves `Known=false`. `directTUNBinding` does the same at `0x745bf0..0x745c0c`. Neither path requires a nonzero generation or known airplane mode to select the interface.
- `Supervisor.watchSelectedNetwork.func2` (`0x793120`) polls selected state every second. It treats a selection error or a changed binding as a reason to reconsider the connection (`0x793234`, `0x793298..0x7932c0`). The generated Binding equality function (`0x74e400`) compares handle, interface, transport, selected source/DNS values, and other binding properties; it also explicitly compares the three access fields at `0x74e48c..0x74e4b8`.
- To identify an access-only change, the watcher makes a second comparison after replacing the new binding's three access fields with the old values (`0x7932f0..0x79331c`). Access-only changes are gated by `Supervisor.CanRebindAccess` (`0x793338..0x793374`). The production callback is `main.startIMSOnPCSCF.func1` (`0x8373b0`), installed at `0x836e9c`; it permits this rebind when there is no current call-state provider or its `CallState` is zero/idle. A permitted access-only change cancels the current session with `access_context_changed`; a different selected binding uses `network_properties_changed`, and selection failure uses `snapshot_invalidated`.
- A permanently omitted generation becomes zero, so a later access event whose final selected handle/interface/source/DNS and published airplane mode remain unchanged is invisible to this extra generation comparison. Real selected-binding changes and invalidation detection still work. A short airplane-mode cycle occurring entirely between samples can similarly be missed even when the latest mode is published; this audit does not claim event-complete behavior from polling.

The parent's selected integration plan publishes the real airplane-mode value only when a fixed system query returns a known `0` or `1`, and leaves `accessGeneration` absent. This is sufficient for the analyzed interface-selection path and retains sampled airplane-state and selected-network changes. The residual limitation is missing access-event-only rebinds that leave those values unchanged. Do not fabricate a new generation on every heartbeat: the original watcher treats a changed generation as a real access change and can repeatedly tear down idle sessions. If generation is implemented later, it needs actual access-event semantics rather than a polling timestamp.

## Verification and remaining acceptance boundary

`tools/isolate_engine_log_pool.py` reproduces the derived candidate from the immutable original. `tests/engine/test_isolated_log_pool.py` passes five groups: exact SHA/delta, all non-rodata sections and ELF metadata unchanged, reproducibility and source-hash rejection, manifest/dependency metadata consistency, and refusal to overwrite the original. The generator permits replacing only the exact previous log-only derived candidate or reproducing the new candidate, never an unknown output.

These checks do not demonstrate successful registration with the old APK/module absent. The new independent snapshot producer, direct identity preparation, root transport, and lifecycle integration require their own validation. The earlier successful registration used the log-only candidate and cannot establish that the then-existing legacy network snapshot/observer was unnecessary. No real call or SMS test is included or authorized by this audit.
