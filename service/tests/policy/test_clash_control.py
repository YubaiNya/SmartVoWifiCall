"""Test real clash-control.sh anchor-PID netns isolation and lifecycle with mocked binaries.

Verifies:
1. setup_netns launches an isolated unshare -n anchor process, moves wfctun0 into it,
   configures lo/wfctun0 and default route ONLY inside the isolated netns, records
   <pid> <start> <netnsId> in netns.owner (visible across all mount namespaces without
   bind-mounts), and NEVER adds routes/rules/iptables to the root network namespace.
2. teardown_netns terminates the anchor process, removes netns.owner, and cleans up any
   legacy v0.3.0 policy routing rules.
3. start branch blocks disabled modules and invalid configs before launching mihomo.
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
NETNS_OWNER_FILE="$base/netns.owner"
LEGACY_NETNS_FILE="$base/netns"
LEGACY_NETNS_ID_FILE="$base/netns.id"
ROUTE_TABLE=209
PREF_OIF=1050
PREF_SRC=1051
: > "$base/root.log"
: > "$base/ns.log"
ip() {
 printf 'ROOT_IP %s\n' "$*" >> "$base/root.log"
 return 0
}
iptables() { printf 'FORBIDDEN_IPTABLES\n' >&2; exit 91; }
ip6tables() { printf 'FORBIDDEN_IP6TABLES\n' >&2; exit 92; }
proc_start() { printf '98765\n'; }
readlink() {
 case "$1" in
  /proc/1/ns/net|/proc/$$/ns/net) printf 'net:[100]\n';;
  /proc/*/ns/net) printf 'net:[200]\n';;
  *) return 1;;
 esac
}
setsid() {
 printf 'SETSID %s\n' "$*" >> "$base/root.log"
 sleep 2
}
nsenter() {
 printf 'NSENTER %s\n' "$*" >> "$base/ns.log"
 return 0
}
owned_netns_pid() {
 [ -f "$NETNS_OWNER_FILE" ] || return 1
 read -r nspid nsstart nsid < "$NETNS_OWNER_FILE" || return 1
 [ "$nsstart" = 98765 ] && [ "$nsid" = 'net:[200]' ]
}
''' + function("remove_legacy_routes") + "\n" + function("teardown_netns") + "\n" + function("setup_netns") + r'''
setup_netns
read -r rec_pid rec_start rec_ns < "$NETNS_OWNER_FILE"
[ -n "$rec_pid" ] && [ "$rec_start" = 98765 ] && [ "$rec_ns" = "net:[200]" ]
teardown_netns
[ ! -e "$NETNS_OWNER_FILE" ]
'''

with tempfile.TemporaryDirectory(prefix="clash-mock-", dir=HERE) as directory:
    fixture = Path(directory)
    sh = fixture / "netns.sh"
    sh.write_text(harness, encoding="utf-8", newline="\n")
    subprocess.run([str(BASH), sh.as_posix(), fixture.as_posix()], check=True)
    root_lines = (fixture / "root.log").read_text(encoding="utf-8").splitlines()
    ns_lines = (fixture / "ns.log").read_text(encoding="utf-8").splitlines()

    # Root netns MUST NEVER add ip rules or ip routes or bind-mount.
    assert not any("ROOT_IP rule add" in x or "ROOT_IP route add" in x or "ROOT_IP route replace" in x for x in root_lines), root_lines
    assert any("SETSID unshare -n -- sleep 2147483647" in x for x in root_lines), root_lines
    assert any("ROOT_IP link set dev wfctun0 netns " in x for x in root_lines), root_lines
    # Inside netns, nsenter configures lo, wfctun0, 198.19.0.1/30 and default route.
    ns_text = "\n".join(ns_lines)
    assert ns_text.count("NSENTER --net=/proc/") == 1, ns_lines
    assert 'ip link set dev lo up' in ns_text and 'ip link set dev "$1" up' in ns_text, ns_lines
    assert 'ip addr replace "$2/30" dev "$1"' in ns_text and 'ip route replace default dev "$1"' in ns_text, ns_lines

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

print("PASS clash-control.sh anchor-PID netns setup/teardown, zero root-netns route/rule mutation, and start guards.")
