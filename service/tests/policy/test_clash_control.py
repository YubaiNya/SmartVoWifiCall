"""Test real clash-control.sh routing isolation and lifecycle with mocked binaries.

Verifies ONLY oif wfctun0 (pref 1050) and from 198.19.0.1/32 (pref 1051) -> table 209
are installed/removed, and no main-table default route or iptables mutation occurs.
"""
from pathlib import Path
import json
import re
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BASH = Path(r"C:\Program Files\Git\bin\bash.exe")
SCRIPT = ROOT / "module/clash-control.sh"

source_bytes = SCRIPT.read_bytes()
assert b"\r" not in source_bytes, "clash-control.sh must use LF line endings"
subprocess.run([str(BASH), "-n", str(SCRIPT)], check=True)
source = source_bytes.decode("utf-8")

def function(name):
    match = re.search(r"^" + name + r"\(\) \{\n.*?^\}", source, re.M | re.S)
    assert match, name
    return match.group()

harness = r'''set -eu
base=$1
TUN_DEV=wfctun0
TUN_IP=198.19.0.1
ROUTE_TABLE=209
PREF_OIF=1050
PREF_SRC=1051
: > "$base/ip.log"
ip() {
 printf '%s\n' "$*" >> "$base/ip.log"
 return 0
}
iptables() { printf 'FORBIDDEN_IPTABLES\n' >&2; exit 91; }
ip6tables() { printf 'FORBIDDEN_IP6TABLES\n' >&2; exit 92; }
''' + function("install_routes") + "\n" + function("remove_routes") + r'''
install_routes
remove_routes
'''

with tempfile.TemporaryDirectory(prefix="clash-mock-", dir=HERE) as directory:
    fixture = Path(directory)
    sh = fixture / "routes.sh"
    sh.write_text(harness, encoding="utf-8", newline="\n")
    subprocess.run([str(BASH), sh.as_posix(), fixture.as_posix()], check=True)
    lines = (fixture / "ip.log").read_text(encoding="utf-8").splitlines()
    assert lines == [
        "link set dev wfctun0 up",
        "route replace default dev wfctun0 table 209",
        "rule del oif wfctun0 lookup 209 priority 1050",
        "rule del from 198.19.0.1/32 lookup 209 priority 1051",
        "rule add oif wfctun0 lookup 209 priority 1050",
        "rule add from 198.19.0.1/32 lookup 209 priority 1051",
        "rule del oif wfctun0 lookup 209 priority 1050",
        "rule del from 198.19.0.1/32 lookup 209 priority 1051",
        "route flush table 209",
    ], lines
    assert not any("table main" in x or ("default" in x and "table 209" not in x) for x in lines)

    # Verify start branch rejects disabled module and invalid config before launching binary.
    start_branch = re.search(r"^ start\)\n(.*?)^  ;;", source, re.M | re.S)
    assert start_branch
    for case, expected_code in [("disabled", "MODULE_DISABLED"), ("badcfg", "CLASH_CONFIG_INVALID")]:
        mod = fixture / case
        mod.mkdir()
        runtime = mod / "runtime.yaml"
        runtime.write_text("mode: rule\n", encoding="utf-8")
        fake_bin = mod / "fake-mihomo.sh"
        fake_bin.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8", newline="\n")
        if case == "disabled":
            (mod / "disable").write_text("", encoding="utf-8")
        check_sh = mod / "start-check.sh"
        check_sh.write_text(r'''set -eu
MODDIR=''' + mod.as_posix() + r'''
BASE=''' + mod.as_posix() + r'''
CLASH_DIR=''' + mod.as_posix() + r'''
BIN=''' + fake_bin.as_posix() + r'''
runtime=''' + runtime.as_posix() + r'''
fail() { printf '{"ok":false,"code":"%s"}\n' "$1"; exit 1; }
owned_pid() { printf 'UNEXPECTED_LAUNCH\n'; exit 88; }
''' + start_branch.group(1), encoding="utf-8", newline="\n")
        res = subprocess.run([str(BASH), check_sh.as_posix()], capture_output=True, text=True)
        assert res.returncode == 1 and json.loads(res.stdout)["code"] == expected_code, (case, res.stdout, res.stderr)

print("PASS clash-control.sh surgical wfctun0 routing (table 209, pref 1050/1051 only), teardown cleanup, and start guards.")
