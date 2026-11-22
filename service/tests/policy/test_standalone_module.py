"""Exercise installer dependency checks with no SoftVoWiFi package present."""
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASH = pathlib.Path('C:/Program Files/Git/bin/bash.exe')
script = r'''
set -eu
MODPATH="$1"
installer="$2"
api="$3"
getprop() {
 case "$1" in ro.build.version.sdk) printf '%s\n' "$api";; ro.product.cpu.abi) printf 'arm64-v8a\n';; *) exit 90;; esac
}
pm() { printf 'External app lookup is forbidden\n' >&2; exit 91; }
abort() { exit 42; }
set_perm_recursive() { :; }
set_perm() { :; }
. "$installer"
'''
with tempfile.TemporaryDirectory(prefix='standalone-install-') as directory:
    fixture = pathlib.Path(directory) / 'fixture.sh'
    fixture.write_text(script, encoding='utf-8')
    for api, expected in [('37', 0), ('36', 42)]:
        result = subprocess.run([str(BASH), str(fixture), directory,
                                 str(ROOT / 'module/customize.sh'), api], capture_output=True)
        assert result.returncode == expected, (api, result.returncode)
    for name in ['customize.sh', 'service.sh', 'control.sh', 'uninstall.sh']:
        subprocess.run([str(BASH), '-n', str(ROOT / 'module' / name)], check=True)
print('PASS standalone installer accepts no external app, retains API gate; all four scripts parse.')
