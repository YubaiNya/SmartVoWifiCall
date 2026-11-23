# 0.3.48 call contract: offline findings after the incoming-call report

## Scope

Adapter observations below refer to the pre-fix `v0.2.0-dev` source. The subsequent
`0.2.1-dev` changes and regression results are recorded in `CALL_FIX_VALIDATION.md`.

The user reports that SMS works, but after answering and ending one incoming call neither SIM can originate, and caller ID displays Unknown. No device was available to this audit. Those symptoms are user observations; their immediate runtime cause is not established here.

Evidence is the immutable 0.3.48 engine SHA-256 `11864d79088731cb898518d21694e4d4068eb680e0ae11cc7f5b7f0a06a53be7`, its unchanged code in the isolated candidate, Go type metadata, Capstone disassembly, and controlled instruction replay with synthetic objects and substituted allocation/map/defer boundaries. The existing target-free REA metadata evidence is `ev_d212bc450f5880a42bb62de1203cffbf6cb2754ea7eb1a6d35fa1199e10e7099`. Native REA Windows ELF failures were not retried. No phone, real engine, dial, SMS, or audio operation was performed; no runtime source or binary was edited.

## Snapshot and caller identity

`main.(*callProtocol).getCallSnapshot` (`0x82a170`) returns the typed `ims.CallSnapshot` at type VA `0x970a00`, not an array of calls:

| JSON field | Type / behavior |
| --- | --- |
| `epoch` | String, embedded `CallVersion` |
| `revision` | Unsigned integer, embedded `CallVersion` |
| `stage` | String |
| `callId` | String, omitted when empty |
| `direction` | String, omitted when empty; `INCOMING` or `OUTGOING` |
| `peer` | String, omitted when empty |
| `confirmedElapsedMs` | Signed integer |
| `dtmfSupported` | Boolean |
| `ringback` | Boolean, omitted when false |
| `earlyMediaRevision` | Unsigned integer, omitted when zero |

`GetCallSnapshot` (`0x7ae1a0`) copies incoming pending peer from `pending+0x2b0` (`0x7ae41c..0x7ae438`) or dialog peer from `dialog+0x4a8` (`0x7ae564..0x7ae580`). Reading `peer` in `NewMmTelFeature.snapshot` is therefore the correct field name.

The value is an already filtered **phone number**, not a nested object or complete SIP URI. `extractIncomingCaller` (`0x7be6c0`) checks privacy, scans P-Asserted-Identity, and may fall back to From. `identityPeer` (`0x7be9a0`) rejects anonymous display/URI identities. `phonePeerFromURI` (`0x7bea60`) accepts SIP/SIPS/TEL and returns only a telephone user consisting of an optional leading `+` and 1–20 ASCII digits. It returns empty for rejected or nontelephone identities. Privacy or unsupported identity may legitimately produce no `peer`; a display name is not exported by this snapshot contract.

At audit time `NewMmTelFeature.java:181..182` only placed the peer into `ImsCallProfile.EXTRA_OI`, stripping `tel:`. The stripping is unnecessary for this engine's normalized peer. The code did not set a presentation extra such as `EXTRA_OIR`; whether that explains the device's Unknown display requires the Android framework mapping or device payload evidence. Do not replace a legitimately withheld peer with invented identity data.

## IDLE, ENDED, and temporary snapshot failure

The snapshot stages built by `GetCallSnapshot` are `IDLE`, `RINGING`, `CONNECTING`, `DIALING`, `IN_CALL`, `CANCELING`, and `ENDING`. `ENDED` is used in events, not as the normal idle snapshot.

- With both pending incoming and active dialog absent, it returns `stage=IDLE` with no callId/direction/peer (`0x7ae3f0..0x7ae404`, `0x7ae548..0x7ae54c`, `0x7ae85c`). Thus clearing known sessions on an accepted, authoritative IDLE snapshot is consistent with the native contract.
- Pending incoming state 0 gives RINGING; state 1 gives CONNECTING (`0x7ae43c..0x7ae46c`); a terminating pending call is ENDING.
- A confirmed dialog with ready media gives IN_CALL; a confirmed dialog without ready media is CONNECTING (`0x7ae5d4..0x7ae608`). Cancellation and ending flags override the phase (`0x7ae694..0x7ae6cc`).
- If a dialog object is still installed but its internal state is 0 or 3, or its registration generation is mismatched, snapshot returns an error (`0x7ae598..0x7ae648`, `0x7ae550..0x7ae55c`). The protocol handler maps an underlying snapshot error to code 2006, `call_snapshot_unavailable` (`0x82a2e4..0x82a300`). No current registered IMS may instead yield code 3002. Neither error is a successful IDLE snapshot or proof of a disconnected control socket.
- `currentCallWireState` (`0x828e80`), used by other RPC replies/status, is a coarser view: internal state 0 or 3 maps to the string IDLE. Do not treat that as equivalent to a successful versioned snapshot in every intermediate state.

Five in-memory instruction-replay cases confirmed: no dialog => IDLE with no callId; pending incoming => RINGING; answering pending => CONNECTING; confirmed incoming dialog => IN_CALL; retained terminal dialog => error. All successful active cases retained the synthetic peer. These are function-level results, not a reproduction of the reported device failure.

## Exact event shapes and ordering

Events and snapshots use different phase keys: events use `state`; snapshots use `stage`.

- `incomingCall`: `callId`, `state`, optional nonempty `peer`, and `epoch`/`revision` when the epoch is nonempty. The same event name carries RINGING, IN_CALL, **and ENDED**. For ENDED it adds `reason`; `durationSec` is added only when positive. This is `main.incomingCallWireData` (`0x829340`) and `emitIncoming` (`0x8285f0`, final Emit at `0x828824` or `0x828a9c`). Three synthetic function replays confirmed these exact keys.
- Incoming ENDING instead emits `callState` with `callId`, `state`, and optional version (`0x828858..0x8289f8`).
- Outgoing terminal notification is `callEnded`, with `callId`, `reason`, `durationSec`, and optional version. It does **not** include `state=ENDED` (`main.(*callProtocol).emitOutgoing`, terminal map `0x829868..0x829a64`). A consumer must recognize the event name itself as terminal.
- Outgoing progress uses `callState`; DIALING may include `ringback` and `earlyMediaRevision` (`0x829ad0..0x829d64`).
- Incoming terminal reason values admitted by `emitIncoming` include `failed`, `hangup`, `remote`, `timeout`, `canceled`, and `rejected` (`0x828700..0x8287e8`).

`CallVersion` type VA `0x92dd20` is `{epoch:string, revision:uint64}`. `advanceCallVersionLocked` (`0x7ae110`) increments the revision under dialog state synchronization; the epoch is a UUID belonging to an IMS object, not a timestamp sortable across epochs. `takeActiveDialogLocked` (`0x7af420`) first clears the active dialog at `0x7af454`, then advances the version at `0x7af464` and records terminal version data on the retired dialog. An IDLE snapshot and that call's later terminal event can therefore have the same revision. A new IMS object's epoch must be treated as a resynchronization boundary.

For a normal local confirmed hangup, `Hangup.func1` calls `takeDialogTerminal` at `0x79d128`, `releaseDialogMedia` at `0x79d140`, may wait for dialog completion at `0x79d154`, and only then emits incoming ENDED at `0x79d224` (or outgoing ended at `0x79d260`). A poll can observe the cleared dialog before the final event. Arrival order between event delivery and independent RPC responses is not a global ordering guarantee.

## Concrete adapter gap and non-findings

At audit time `NewMmTelFeature.event` (`NewMmTelFeature.java:122..138`) discarded all call-event payloads after scheduling a snapshot request, and swallowed snapshot exceptions. Therefore even a matching, versioned terminal `incomingCall`/`callEnded` cannot directly terminate its Android session if the subsequent query fails. This is a concrete coverage gap: the native contract supplies an independent terminal event, but the adapter makes terminal handling contingent on another successful RPC. It is a plausible route to a stale framework call after registration loss or temporary snapshot failure, not a proven account of this phone incident.

A bounded repair should recognize both incoming `state=ENDED` and outgoing `callEnded`, verify the owning binding, callId, epoch and revision, and terminate only the matching session. Retain snapshot reconciliation and do not let a stale old-epoch event clear a newer call. Do not assume the event `stage` key exists or that `callEnded` contains `state`.

The adapter's `peer` field selection and successful-IDLE snapshot cleanup are **not** demonstrated contract mismatches. The lack of event fallback and the caller-presentation mapping warrant separate checks. This native audit does not establish that a real caller supplied a public peer, that Android received the terminal callback, or that its global call/audio state became idle.

## Admission lock cleanup and its limits

The isolated engine uses `/data/adb/nvwims/run/call.lock`, shared across engine instances rather than per-SIM. It acquires `Flock(LOCK_EX|LOCK_NB)` through `acquireCallAdmission` (`0x79d3a0`) and `lockCallAdmission` (`0x79d4b0`). This serializes engine calls; the existence of the lock file alone says nothing about whether it is currently held.

`callAdmission.Close` (`0x79d570`) is guarded by `sync.Once` and calls `release`. `release` (`0x79d950`) decrements a reference count and closes the file descriptor when the old count is 1 (`0x79d9ec..0x79da0c`). `holdMediaSetup` can retain an additional reference until its release closure runs.

Normal incoming and confirmed-dialog cleanup paths do release admission: `releaseIncomingLease.deferwrap1` calls Close at `0x7bfdec`; `releaseDialogMedia.deferwrap2` calls Close at `0x7af6ac`. The latter is deferred until media teardown finishes. `releaseDialogMedia` first closes an RTPMediaSession (`0x7af614`) or MediaLease (`0x7af5e0`); RTPMediaSession.Close itself can wait on its completion channel at `0x6a0404`. Thus a media teardown/reference that never completes could retain shared admission even after the dialog is removed. The audit found a normal release path; it did not prove a leak or deadlock in this runtime.

Do not unlink the lock file as a recovery mechanism: another process could then lock a new inode while the old holder still exists. A future read-only observation should distinguish an existing file from a live flock owner and record whether media teardown/dialog retirement remains pending, without exporting subscriber data.

The engine's flock does **not** directly block the OEM telephony stack. If the second SIM still uses the original system IMS, failure on both SIMs also points toward a stale Android Telecom call, shared application worker/audio state, or another system-level condition. The native lock hypothesis alone does not explain an OEM-SIM failure.
