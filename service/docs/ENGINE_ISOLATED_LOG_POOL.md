# Independent engine data-directory patch

The 0.3.48 engine accepts an independent `--log-dir`, but `main.main` loads the
managed logging pool from a separate global default. The original default is
`/data/adb/vowifi/state/logs`. Device inspection by the parent agent found all
three original pool groups admitted and the independent engine reporting that
logging initialization failed while service execution continued. This is a
logging blocker; it does not establish the cause of IKE/IMS registration failure.

The first revision isolated only that log pool. The standalone revision also
relocates the default log root, call-admission lock, and network snapshot. This
removes the four embedded `/data/adb/vowifi/` paths without changing code. The
network snapshot still needs this service's own producer; see
`STANDALONE_ENGINE_DEPENDENCIES.md`.

## Candidate

| Item | Value |
| --- | --- |
| Original | `service/deps/vowifi-engine-arm64` |
| Original SHA-256 | `11864d79088731cb898518d21694e4d4068eb680e0ae11cc7f5b7f0a06a53be7` |
| Output | `service/deps/vowifi-engine-arm64-isolated` |
| Output SHA-256 | `f53df163c7731ebeba45ebf913e85cace0af2884647359155ed515e137b479b3` |
| Size | 10,964,848 bytes, unchanged |
| Replacements | Four equal-length paths below: `vowifi` → `nvwims` |
| Literals | `.rodata`; each VA equals its file offset |
| Go string global | VA `0x9be860`, file offset `0x9b2860`; unchanged pointer `0x17b35`, length 27 |
| Actual changed file bytes | 16 total; exact offsets in the patch manifest and test |

| Original path | Literal VA/file offset | Length |
| --- | --- | --- |
| `/data/adb/vowifi/logs` | `0x1302d` | 21 |
| `/data/adb/vowifi/state/logs` | `0x17b35` | 27 |
| `/data/adb/vowifi/run/call.lock` | `0x1a65d` | 30 |
| `/data/adb/vowifi/run/networks.json` | `0x1e2b3` | 34 |

Only those four existing equal-length literals change. All code, other data,
ELF headers/mappings, Go metadata, and relocations remain byte-for-byte identical.
The original input is retained. No network, authentication, country, call, or SMS
logic is changed. This derived binary is not an upstream release; upstream
licensing and provenance limits still apply.

## Log-pool reference scope

The full path occurs once in the original file. Its confirmed consumers are:

- `main.main`: global load at `0x838964`, passed to `log.New` at `0x8389ac`.
- `main.runDiagnosticStream.func1`: direct literal address at `0x844a7c`, used as
  the managed-log export pool argument. This is also logging functionality.

No nonlogging direct reference was observed in the recovered Go code. A numeric
match at VA `0x8c8a48` is the `rtype.Str` relative name offset `0x17b3d`, not a path
pointer: types base `0x88f4c0` resolves it to `*cipher.cbcDecAble` at `0x8a6ffd`.
That type metadata and name remain unchanged.

The diagnostic-stream wrapper's root now resolves to `/data/adb/nvwims/logs`.
That still differs from the launcher's explicit `/data/adb/newvowifi_ims/logs`
directory. Do not use its output as evidence of those explicitly relocated
logs. The correct read-only pool inspection command is **`log-budget`**:

```text
<isolated-engine> log-budget /data/adb/nvwims/state/logs
```

`log-pool` is not this CLI command. The earlier analysis inferred that name from
an adjacent string incorrectly; device invocation and the packaged `logs.sh`
confirmed `log-budget`.

## Startup conditions established by original code

- A new pool member's `--log-dir` must be empty when admitted.
  `reserveManaged.func1` constructs an empty allowlist and calls
  `validateManagedDir` at `0x817d2c`; a precreated `launcher.log` makes first
  admission fail and is mapped to `unsafe_path`.
- Existing admitted log directories allow `engine.log`, `launcher.log`, and
  `diagnostic.jsonl` and their managed rotation names. This does not remove the
  empty-directory requirement for first admission.
- Shell redirection that creates a launcher file before engine startup belongs
  outside the managed log directory, in this service's separate run directory.
- Create `/data/adb/nvwims` as a private directory (0700, owned by the engine UID)
  before launch. The engine creates/checks `state/logs` beneath it and derives
  `/data/adb/nvwims/run/locks` for pool/writer locks. The existing original
  SoftVoWiFi pool and history are not part of this setup.
- For the standalone revision, also create `/data/adb/nvwims/run` (0700).
  The call-admission lock opens/creates its file but does not create its parent.
  Publish the independently observed network snapshot there at intervals below
  the engine's 15-second freshness limit; the parent integration uses five seconds.

These are integration requirements for the parent agent; this local patch task
performed no phone operations and made no changes to any original pool.

Integration result for the first, log-only revision on 2026-11-22: the installed independent engine reported the
new pool initialized, one admitted group and no admission error. Its own
`engine.log` recorded the successful SIP REGISTER 200. The original engine file
and original pool were retained. Its SHA-256 was
`15f8be3814736aa33ca4605c4facb787e13e75cd383ab609d3d4f51b33a3d47d`.
That device result does not constitute a device test of the newer four-path
candidate. See `VALIDATION.md` for the device results.

## Reproduce and verify

```powershell
python D:\NewVowifi\service\tools\isolate_engine_log_pool.py
python D:\NewVowifi\service\tests\engine\test_isolated_log_pool.py
```

Five test groups pass: exact candidate identity/differences; ELF mappings and
unchanged sections; reproducibility/version rejection; dependency/manifest
consistency; refusal to overwrite the baseline. The script can replace only the
exact earlier log-only derived output, or reproduce the current output; any
other existing output is rejected. The machine-readable manifest is
`deps/vowifi-engine-arm64-isolated.patch.json`.

The separate original-instruction replay of directory validation used a virtual
filesystem: empty new directory succeeded, while a new directory containing
`launcher.log` failed. This does not replace device verification. The parent
agent owns deployment and the next registration/logging observations; no real
call or SMS test is authorized.
