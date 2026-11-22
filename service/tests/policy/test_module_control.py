"""Run real socket bookkeeping functions with mocked /proc, permissions and sockets.

No Android command, engine, real socket, chown or chmod is executed. All writable
fixtures live under this test directory.
"""
from pathlib import Path
import json
import re
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BASH = Path(r"C:\Program Files\Git\bin\bash.exe")
source = (ROOT / "module/control.sh").read_text(encoding="utf-8")

def function(name):
    match = re.search(r"^" + name + r"\(\) \{\n.*?^\}", source, re.M | re.S)
    assert match, name
    return match.group()

script = r'''set -eu
base=$1
socketfile="$base/proof"
iccid=8900000000000000019
printf '0\n' > "$base/clock"
printf '0:0\n' > "$base/owner"
printf '600\n' > "$base/mode"
printf '0\n' > "$base/deletes"
own=yes
live=no
wrong=no
owned_pid() { return 0; }
pid_owns_socket() { [ "$own" = yes ]; }
# Only -S is mocked. Every other test keeps Bash's real behavior.
[() {
 if [[ "$#" == 3 && "$1" == -S ]]; then [[ "$2" == *"/$iccid.sock" ]]; return;fi
 builtin [ "$@"
}
advance() { n=$(cat "$base/clock");printf '%s\n' "$((n+1))" > "$base/clock"; }
stat() {
 case "$2" in
  '%u:%g') cat "$base/owner";;
  '%a') cat "$base/mode";;
  *) return 1;;
 esac
}
chown() { printf '%s\n' "$1" > "$base/owner";advance; }
chmod() { printf '660\n' > "$base/mode";advance; }
socket_token() {
 if [ "$wrong" = yes ];then printf 'other-inode';else printf '1:2:%s' "$(cat "$base/clock")";fi
}
socket_kernel_inode() { [ "$live" = yes ]; }
rm() {
 if [[ "${@: -1}" == /data/local/tmp/* ]];then
  n=$(cat "$base/deletes");printf '%s\n' "$((n+1))" > "$base/deletes"
 else command rm "$@";fi
}
'''
script += "\n" + function("record_and_grant") + "\n" + function("cleanup_recorded_sockets") + "\n"
script += r'''
record_and_grant 12345
[ "$(cat "$base/clock")" = 2 ]
[ "$(cat "$socketfile")" = 'sock 1:2:2' ]
# Repair is idempotent; no extra chmod/chown may invalidate the sealed ctime.
record_and_grant 12345
[ "$(cat "$base/clock")" = 2 ]
[ "$(cat "$socketfile")" = 'sock 1:2:2' ]
cleanup_recorded_sockets
[ "$(cat "$base/deletes")" = 1 ]
# Replaced/unrecorded endpoints are never removed.
wrong=yes
if cleanup_recorded_sockets;then exit 11;fi
[ "$(cat "$base/deletes")" = 1 ]
wrong=no;live=yes
if cleanup_recorded_sockets;then exit 12;fi
[ "$(cat "$base/deletes")" = 1 ]
# Permission changes require proof that this exact engine owns the endpoint.
own=no
if record_and_grant 99999;then exit 13;fi
[ "$(cat "$base/clock")" = 2 ]
printf 'PASS module socket ownership, ctime, idempotent repair, replaced/live socket rejection.\n'
'''
with tempfile.TemporaryDirectory(prefix="module-mock-", dir=HERE) as directory:
    fixture = Path(directory)
    harness = fixture / "harness.sh"
    harness.write_text(script, encoding="utf-8", newline="\n")
    subprocess.run([str(BASH), harness.as_posix(), fixture.as_posix()], check=True)
    # Exercise the actual status branch with a fixture-only BASE and an impossible dead PID.
    base = fixture / "state"
    (base / "run").mkdir(parents=True)
    identity = "8900000000000000019"
    owner = base / "run" / (identity + ".owner")
    owner.write_bytes(b"2147483647 1\n")
    status_script = fixture / "status.sh"
    status_script.write_text(source.replace("BASE=/data/adb/newvowifi_ims", "BASE='" + base.as_posix() + "'", 1),
                             encoding="utf-8", newline="\n")
    before = {p.relative_to(base).as_posix(): p.read_bytes() for p in base.rglob("*") if p.is_file()}
    status = subprocess.run([str(BASH), status_script.as_posix(), "status", identity], capture_output=True, text=True)
    assert status.returncode == 0, (status.stdout, status.stderr)
    assert json.loads(status.stdout)["running"] is False
    after = {p.relative_to(base).as_posix(): p.read_bytes() for p in base.rglob("*") if p.is_file()}
    assert before == after and not (base / "config").exists() and not (base / "logs").exists()
    owner.unlink()
    unknown = subprocess.run([str(BASH), status_script.as_posix(), "status", identity], capture_output=True, text=True)
    assert unknown.returncode != 0 and json.loads(unknown.stdout)["code"] == "ENGINE_OWNERSHIP_UNKNOWN"
    assert not list((base / "run").iterdir())
    print("PASS status proves recorded process death; unknown ownership is an error; status creates no files or locks.")
for name in ("control.sh", "service.sh", "uninstall.sh"):
    subprocess.run([str(BASH), "-n", str(ROOT / "module" / name)], check=True)
