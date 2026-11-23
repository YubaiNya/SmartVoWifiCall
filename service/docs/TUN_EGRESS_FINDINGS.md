# Selected TUN egress: bounded native audit

## Result and scope

For explicit `iface:tun0`, the traced native selection, ePDG DNS, and IKE/NAT-T socket-construction paths preserve the selected binding or return an error. No branch was found in those paths that changes the selector to `auto`, `wifi`, `sim1`, `sim2`, or a default-network session after a selection/bind failure.

This is **not packet-level proof that every ePDG/ESP packet is blocked when the TUN disappears**. Kernel XFRM routing, already-created sockets and routes, and FlClash's own transport underlay remain separate concerns. Selecting `tun0` cannot by itself ensure that FlClash's proxy connection does not use the target SIM's cellular data.

The analysis reads the original 0.3.48 artifact SHA-256 `11864d79088731cb898518d21694e4d4068eb680e0ae11cc7f5b7f0a06a53be7`; the isolated candidate has identical code. Earlier target-free REA metadata evidence: `ev_d212bc450f5880a42bb62de1203cffbf6cb2754ea7eb1a6d35fa1199e10e7099`. Findings below are from existing Go metadata and local Capstone disassembly, not live packets. No phone, target process, network operation, implementation, or binary change was performed.

## Two different tunnel interfaces

- FlClash's selected `tun0` is the **outer access interface** used to reach ePDG/DNS and carry the software IMS tunnel's outer traffic.
- The engine's `sv` plus SIM-derived hash interface is its **inner IMS data-plane interface**. Its existence and its per-SIM XFRM IF_ID do not identify the physical internet egress. The full chain may be inner IMS packets → engine encryption/XFRM → FlClash TUN → FlClash proxy connection → a physical Wi-Fi/cellular interface.

## Confirmed code paths

| Path | Artifact evidence | Bound conclusion |
| --- | --- | --- |
| Explicit interface selection | `Snapshot.SelectObserved` `0x746cb0`, exact name comparison `0x746fbc..0x746fe8`; `Snapshot.selectProxy` `0x745290` | An unavailable explicit name does not turn into a different observed interface. Its direct-TUN alternative still checks the same named interface. A valid, fresh independent network snapshot is required first. |
| Selection error | `Supervisor.Run`, selectedNetwork call `0x794ba8`, error branch `0x794c08`, BACKOFF setup `0x794cf4..0x794d28` | Returns to backoff/reselection rather than constructing a default-network replacement session. |
| Binding transfer | `Supervisor.Run` `0x794d74..0x794f80` | Copies the selected source/DNS and closures for `Binding.Control` / `Binding.SourceIP` into the SWu session config before `NewSession`. |
| Socket binding | `Binding.Control.func1` `0x744130` | Applies Android platform-network binding, then `SO_BINDTODEVICE` for the selected interface. The direct-interface flag skips the platform handle operation but retains device binding. Binding errors propagate; earlier controlled replay checked this ordering. |
| DNS missing endpoint | `resolveUDPAddrAllWithObserver` `0x73dc40`, checks `0x73dc8c..0x73dcec` | With selected control/source callbacks, a hostname and no explicit DNS endpoint cause `selected network requires an explicit DNS endpoint` (literal VA `0x2888e`, length 50). It does not silently resolve that hostname through the system default DNS path. A numeric ePDG address needs no hostname lookup. |
| DNS with endpoint | Resolver construction `0x73dfe8..0x73e09c`, callback `resolveUDPAddrAllWithObserver.func1` `0x73e530` | Uses its own Go resolver Dial callback. That callback installs the selected socket control at `0x73e5c0`, computes a local source through `selectSourceIP` at `0x73e6ac`, and calls `Dialer.DialContext` at `0x73e888`/`0x73ea04`. The two sites distinguish observer wrapping; they are not an unbound retry after bind failure. |
| IKE/NAT-T socket creation | `NewSocketManagerWithObserver` `0x73d420`; source selection `0x73d534`; `ListenConfig.Control` assignment `0x73d820..0x73d824`; `ListenPacket` `0x73d8a0` | The UDP listener receives the selected control callback. On error, `0x73daac..0x73db1c` reports/returns the error; it does not create a second unbound socket. |
| Userspace packet sending | `SocketManager.SendIKE` `0x73f7b0`, `WriteToUDP` `0x73fb08`; `SendESP` `0x73fbb0`, `WriteToUDP` `0x73fd14` | These paths use the SocketManager UDP connection. The userspace ESP sender is not a separately discovered default-network socket in the inspected code. |
| Network change watcher | `watchSelectedNetwork.func2` `0x793120`, one-second ticker and selected-binding comparison | Re-evaluates the selected network and cancels when it becomes invalid or changes. This is polling, not a guarantee of instantaneous packet rejection during a change. |

The generic resolver also contains an ordinary `net.ResolveUDPAddr` branch (`0x73dd80`) when no selected control/source callback is supplied, or when resolving an already numeric endpoint. Its existence must not be confused with fallback after a failed selected-interface DNS lookup.

## Kernel XFRM boundary and an unresolved generic branch

`Session.setupXFRMDataPlane` (`0x777e60`) obtains the SocketManager's local and remote addresses (`0x778058..0x77808c`) and may read its output mark (`0x7780d8`). It searches interfaces by the selected source address (`net.Interfaces` at `0x77867c`, address comparison later at `0x779ad8`), passes an interface index to `AddXFRMInterface` (`0x778704`), and installs SAs/SPs through `AddSA` and `initialChildXFRMPolicies`. IF_ID and output marks are routing/isolation inputs; their presence alone does not prove that every kernel-generated encrypted packet exits a particular live interface.

There is a generic route/source probe: if the SocketManager local IP is unspecified (`0x778164..0x778178`), the function invokes plain `net.Dial("udp", ePDG-address)` at `0x778380` and inspects the selected local address. The non-unspecified source path skips that probe. The selected-binding/source path is expected to supply a concrete source, but this bounded audit did not prove that the unspecified branch is unreachable in every lifetime/race/configuration. Nor did it establish that the probe sends an application payload. It is therefore evidence against claiming that the entire artifact contains no default-route code, not evidence of an observed cellular leak or a confirmed fallback triggered by TUN disappearance.

The live device's actual chosen data-plane mode, XFRM output-routing attributes, policy-rule priorities, and interface-removal behavior were not verified in this task. The earlier observation that legacy Android `ip` omits IF_ID attributes remains relevant: text output alone is insufficient to establish the full kernel state.

## What remains unproved

1. No packet trace here proves zero direct ePDG/DNS/ESP traffic over the target SIM during TUN loss, bind error, reconnect, or interface recreation. Existing bound sockets, cached routes and kernel XFRM output need device-specific verification.
2. The native selector names an interface; it does not authenticate that interface as FlClash or determine FlClash's selected proxy/rules. A same-named replacement interface and FlClash DIRECT rules are outside this static selector guarantee.
3. FlClash can carry its upstream proxy connection over the system's chosen physical network. If the requirement forbids even that connection using the target SIM's data, FlClash's own underlay selection must also satisfy the requirement. Engine `SO_BINDTODEVICE=tun0` alone cannot enforce it.
4. Ordinary OEM voice fallback is a separate framework/policy decision and is not resolved by this native egress audit.

No additional network guard or stack modification is proposed or implemented by this document. The supported statement is limited to the traced selected-binding/error behavior above; complete packet-level fail-closed behavior remains an acceptance question.
