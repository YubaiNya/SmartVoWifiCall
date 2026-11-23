# VoWiFi Engine 0.3.48 REGISTER PANI on a non-WLAN binding

## Conclusion and limits

The exact 0.3.48 artifact constructs initial and authenticated REGISTER requests **without `P-Access-Network-Info`** when its selected `underlay.Binding.Transport` is not exactly `wifi`. The reason string `non_wlan_access_pani_unavailable` identifies this branch. It does not mean a fallback containing `country=GB`, `country=CN`, or a country derived from MCC.

This conclusion is supported by the original machine code, Go type metadata, and six controlled instruction-replay cases. The runtime operator reported this reason for the independent service using `iface:tun0`, followed by SIP 401 and SIP 200 at 04:59:22.963 on 2026-11-22 and engine registration. **No final outbound REGISTER header was captured in this analysis.** Registration success alone is not evidence of a transmitted GB country parameter, nor does this analysis determine the operator's country-validation policy.

The configured selector `iface:tun0` and the selected binding's `Transport` are different concepts. The decisive evidence for the actual non-WLAN branch is the observed reason string, together with its unique branch in the version-bound code.

## Artifact identity and method

- Baseline: `service/deps/vowifi-engine-arm64`, SHA-256 `11864d79088731cb898518d21694e4d4068eb680e0ae11cc7f5b7f0a06a53be7`.
- Independent logging variant used for the reported registration: SHA-256 `15f8be3814736aa33ca4605c4facb787e13e75cd383ab609d3d4f51b33a3d47d`; it changed four bytes in the log-pool literal.
- The later standalone `service/deps/vowifi-engine-arm64-isolated` candidate is SHA-256 `f53df163c7731ebeba45ebf913e85cace0af2884647359155ed515e137b479b3`. It changes 16 bytes across four directory literals. Both derived variants and the original remain 10,964,848 bytes. All code and PANI metadata cited here are identical; the earlier registration observation is not a device test of the newer candidate. Exact differences are in `vowifi-engine-arm64-isolated.patch.json`.
- Earlier target-free REA Go metadata evidence: `ev_d212bc450f5880a42bb62de1203cffbf6cb2754ea7eb1a6d35fa1199e10e7099` (Go 1.26.9, Android/arm64). The address-level analysis below uses the existing local pyelftools/Capstone reader. It is not a successful REA native-provider decompilation.
- Addresses below are ELF virtual addresses, not raw file offsets or absolute live process addresses. An observer would need the verified ELF load bias.
- No Android engine process, phone operation, network request, call, SMS, or runtime hook was performed by this analysis.

## Exact construction chain

| Stage | Evidence in the artifact | Result |
| --- | --- | --- |
| Resolve REGISTER PANI | `main.resolveREGISTERPANIForNetworkWith`, `0x833cc0`; comparison at `0x833ce8..0x833d04` | Compares the binding's transport with the four-byte string `wifi`. |
| Non-WLAN return | `0x833d08..0x833d2c` | Returns three Go strings: initial `""`, protected `""`, reason `non_wlan_access_pani_unavailable` (literal VA `0x1c0d7`, length 32). Returns before the policy resolver, country, MCC, or node lookup is read. |
| Registration factory | `main.startIMSOnPCSCF.newProductionRegistrationFactory.func4`, `0x837480`; calls resolver at `0x83780c` | Saves the two strings. At `0x837864..0x83788c`, sets the non-WLAN boolean from the same exact `wifi` comparison. Stores initial/protected strings and boolean at `0x83797c..0x837994`. |
| Named configuration fields | Go `ims.Config` type VA `0x98e300`, size 488 bytes | `+0x190 InitialPANI`, `+0x1a0 ProtectedPANI`, `+0x1b0 CallPANI`, `+0x1c0 NonWLANAccess`. `ims.IMS` type VA `0x997660` embeds `cfg` at offset zero. |
| Constructor | `ims.New`, `0x7b53d0`; copy at `0x7b54cc..0x7b54fc` | Copies the entire 488-byte config into the new IMS object without changing these fields. |
| Initial REGISTER | `(*IMS).buildRegisterWithExpires`, `0x7d8fa0`; load/call at `0x7d98e8..0x7d98f0` | Passes `InitialPANI` directly to `setAccessNetworkInfo`. |
| Authenticated REGISTER | `(*IMS).buildAuthenticatedRegister`, `0x79b850`; selection at `0x79c0bc..0x79c0e8` | Reads `ProtectedPANI`. For an empty string, checks `NonWLANAccess` at `0x79c0c8..0x79c0cc`. True skips the default `IEEE-802.11`; the empty value reaches `setAccessNetworkInfo`. |
| Final header setter | `ims.setAccessNetworkInfo`, `0x7de400` | Repeatedly removes existing `P-Access-Network-Info` at `0x7de43c..0x7de440`. At `0x7de448`, an empty value returns without appending a header. The header-name literal is VA `0x131e6`, length 21. |

The factory's later call to `CardConfig.ResolveREGISTERPANI` (`0x8379a0`) supplies country to `main.callPANIWithCountry` (`0x8379b4`) for **CallPANI**, not the stored InitialPANI or ProtectedPANI. `callPANIWithCountry` (`0x833b20`) only appends country to a nonempty `IEEE-802.11` value; it does not turn an empty value into a country header.

The authenticated request's default `IEEE-802.11` constant (VA `0xc4be`, length 11) itself contains no country. The non-WLAN flag suppresses even that default.

## Reproduction and instruction replay

Static evidence can be regenerated with the existing, hash-locked reader:

```powershell
python D:\SmartVoWifiCall\service\tools\engine_iface\inspect_native.py 'main.resolveREGISTERPANIForNetworkWith' 'main.startIMSOnPCSCF.newProductionRegistrationFactory.func4'
python D:\SmartVoWifiCall\service\tools\engine_iface\inspect_native.py 'vowifi_engine/internal/ims.New' 'vowifi_engine/internal/ims.(*IMS).buildRegisterWithExpires' 'vowifi_engine/internal/ims.(*IMS).buildAuthenticatedRegister' 'vowifi_engine/internal/ims.setAccessNetworkInfo'
```

The six replay cases used the existing `Machine` and `GoImage` classes in `service/tests/engine/test_native_selectors.py`; the narrow replay was run in memory and was not saved as a device probe. Four cases supplied `other`, `iface:tun0`, `sim1`, and an empty string as the **binding transport**, with intentionally unmapped config and executor pointers. Each returned empty initial/protected strings and the exact reason without dereferencing those pointers. Two header cases supplied zero and two existing PANI headers: the setter called the mocked removal function once and three times respectively, and never called append. These are controlled CPU replay results, not end-to-end runtime tests.

Minimal reproduction for the four resolver cases:

```powershell
@'
import sys
sys.path.insert(0, r'D:\SmartVoWifiCall\service\tests\engine')
from test_native_selectors import Machine, GoImage, STACK
m = Machine(GoImage())
for transport in ('other', 'iface:tun0', 'sim1', ''):
    m.reset()
    m.writeq(STACK + 0x20, *m.string(transport))
    m.set(2, 0xffffffffffff0000)
    m.set(3, 0xffffffffffff0000)
    m.run('main.resolveREGISTERPANIForNetworkWith')
    assert all(m.get(i) == 0 for i in range(4))
    assert m.read_string(m.get(4), m.get(5)) == b'non_wlan_access_pani_unavailable'
print('PASS: 4 non-WLAN resolver cases')
'@ | python -
```

## Passive observation option, not installed

Existing `engine-after-rule-cleanup.log` contains the fallback reason but no complete REGISTER request/PANI-header capture. Its lack of a printed header is therefore not an independent proof of header omission. The current result does not require adding a hook or forcing registration.

If separately needed during a future natural registration refresh, the narrow constructor observation point is `setAccessNetworkInfo` at load-bias + `0x7de400`: Go ABI `x0=request`, `x1=value pointer`, `x2=value length`. Reporting only the value length, header presence, and any country parameter avoids exporting the rest of a SIP request. A constructor observation still precedes serialization.

For the protected sender, `(*protectedSIPTransport).do` serializes the request with `Request.StringWrite` at `0x7cfa14`, loads the resulting string into `x3/x4` at `0x7cfa1c`, and calls `writeFrame` at `0x7cfa68`. Thus `(*protectedSIPTransport).writeFrame`, load-bias + `0x7cfe00`, receives the complete outgoing serialized SIP string in `x3=pointer`, `x4=length`. Its path converts the same string to bytes at `0x7d0044..0x7d004c` and passes them to the connection write at `0x7d007c`. An observer must filter to REGISTER and emit only `paniPresent` and, if present, the country value; never export Authorization, subscriber identifiers, or the full request. This point proves application send intent, not packet delivery or network acceptance. No such observer was installed here.

`Request.String` alone is not a reliable coverage point: this protected path calls `Request.StringWrite` directly.

## Separate XFRM evidence correction

The runtime operator's raw NETLINK inspection confirmed that all six stale policies carried `XFRMA_IF_ID` matching this SIM's `NewDataPlaneIdentity` seed. The old Android `ip` display omitted this attribute. These policies must not be described as lacking per-SIM isolation or as proving inevitable multi-SIM collisions. The old and new generation for the same SIM intentionally share that identity, so stale generation cleanup is a distinct issue. No routing/XFRM source changes were made for this PANI analysis.
