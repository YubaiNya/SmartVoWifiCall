"""Execute real installer/customize/boot scripts with a fake PackageManager.

All application files, settings and command traces remain in a temporary local
fixture. No phone, real package installation or app data operation is performed.
"""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
BASH = pathlib.Path('C:/Program Files/Git/bin/bash.exe')
HARNESS = r'''
set -eu
test_root=$(cygpath -u "$1")
scenario="$2"
test_module="$test_root/module"
fixture_base="$test_root/runtime"
active="$test_root/installed.apk"
bundled="$test_module/system/priv-app/SmartVoWifiCall/SmartVoWifiCall.apk"
NV_PM=pm
NV_APP_PROCESS=app_process
BOOTMODE=true
export NV_PM NV_APP_PROCESS
printf '0\n' > "$test_root/installs"
printf '0\n' > "$test_root/checks"
printf '0\n' > "$test_root/starts"
printf '0\n' > "$test_root/retries"
printf '0\n' > "$test_root/radio_releases"
printf 'enabled=true;iface=tun0;billable=true;uid=10703\n' > "$test_root/preferences"
printf 'existing-sim-config\n' > "$test_root/sim-config"
before_prefs=$(sha256sum "$test_root/preferences")
before_config=$(sha256sum "$test_root/sim-config")
case "$scenario" in fresh|custom_fresh|recovery|api_old|abi_wrong|missing) ;;
 identical|boot_identical) cp "$bundled" "$active";;
 *) printf 'old-apk\n' > "$active";;
esac
[ "$scenario" != missing ] || rm "$bundled"

inc() { n=$(cat "$test_root/$1");printf '%s\n' "$((n+1))" > "$test_root/$1"; }
pm() {
 case "$1" in
  path)
   [ "$2 $3 $4" = '--user 0 com.smartvowificall.ims' ] || exit 91
   [ -f "$active" ] || return 1
   printf 'package:%s\n' "$active"
   ;;
  install)
   [ "$#" -eq 6 ] && [ "$2 $3 $4 $5" = '-r --user 0 -S' ] || exit 92
   inc installs
   cat > "$test_root/stream.apk"
   [ "$(wc -c < "$test_root/stream.apk" | tr -d '[:space:]')" = "$6" ] || exit 93
   cmp -s "$test_root/stream.apk" "$bundled" || exit 94
   case "$scenario" in
    signature|custom_signature|boot_signature) printf 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: private detail]\n';return 1;;
    downgrade) printf 'Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n';return 1;;
    failure_zero) printf 'Failure [INSTALL_FAILED_INTERNAL_ERROR: private detail]\n';return 0;;
    boot_retry) if [ "$(cat "$test_root/installs")" = 1 ];then printf 'Failure [INSTALL_FAILED_INTERNAL_ERROR]\n';return 1;fi;;
    verify_bad) printf 'Success\n';return 0;;
   esac
   cp "$test_root/stream.apk" "$active"
   printf 'Success\n'
   ;;
  grant) [ "$2" = com.smartvowificall.ims ] || exit 95;return 0;;
  *) printf 'Unexpected PackageManager operation\n' >&2;exit 96;;
 esac
}
app_process() {
 case "$*" in
  *install-check)
   inc checks
   case "$scenario" in
    call_active|custom_call) printf '{"ok":false,"code":"INSTALL_WAIT_CALL_IDLE"}\n';;
    call_unknown) return 1;;
    boot_call) if [ "$(cat "$test_root/checks")" = 1 ];then printf '{"ok":false}\n';else printf '{"ok":true}\n';fi;;
    *) printf '{"ok":true,"code":"INSTALL_IDLE"}\n';;
   esac;;
  *ims-restore-all) printf '{"ok":true}\n';;
  *radio-release-all)
   inc radio_releases
   if [ "$scenario" = boot_radio_retry ] && [ "$(cat "$test_root/radio_releases")" = 1 ]; then
    printf '{"ok":false,"released":false,"code":"RADIO_RESTORE_PENDING"}\n'; return 1
   fi
   printf '{"ok":true,"released":true,"code":"RADIO_RELEASED"}\n';;
  *RootNetworkMain\ watch) return 0;;
  *) exit 97;;
 esac
}
getprop() {
 case "$1" in
  ro.build.version.sdk) if [ "$scenario" = api_old ];then printf '36\n';else printf '37\n';fi;;
  ro.product.cpu.abi) if [ "$scenario" = abi_wrong ];then printf 'x86_64\n';else printf 'arm64-v8a\n';fi;;
  sys.boot_completed) printf '1\n';;
  *) exit 98;;
 esac
}
ui_print() { printf '%s\n' "$*"; }
abort() { printf 'ABORT:%s\n' "$*";exit 42; }
set_perm_recursive() { :; }
set_perm() { :; }
sleep() {
 inc retries
 [ "$(cat "$test_root/retries")" -le 4 ] || exit 99
}
cmd() {
 [ "$*" = 'activity start-foreground-service --user 0 -n com.smartvowificall.ims/.PolicyService' ] || exit 100
 cmp -s "$active" "$bundled" || exit 101
 inc starts
 touch "$test_module/disable"
}
am() { [ "$*" = 'stopservice --user 0 -n com.smartvowificall.ims/.PolicyService' ] || exit 102; }

rc=0
case "$scenario" in
 custom_*|recovery|api_old|abi_wrong)
  MODPATH="$test_module"
  [ "$scenario" != recovery ] || BOOTMODE=false
  . "$test_module/customize.sh"
  result=custom_done
  ;;
 boot_*)
  # Same boot loop and helpers; only Android executable/private path locations
  # are redirected to the fixture and the declared shell substitutes above.
  . "$test_module/service-host.sh"
  exit 103
  ;;
 *)
  . "$test_module/app-install.sh"
  nv_install_app "$test_module" || rc=$?
  result="$NV_APP_RESULT"
  ;;
esac
[ "$(sha256sum "$test_root/preferences")" = "$before_prefs" ] || exit 104
[ "$(sha256sum "$test_root/sim-config")" = "$before_config" ] || exit 105
printf 'RESULT:%s:%s\n' "$rc" "$result"
'''

class ModuleInstallTest(unittest.TestCase):
    def run_scenario(self, scenario):
        with tempfile.TemporaryDirectory(prefix='install-fixture-',dir=HERE) as directory:
            root=pathlib.Path(directory).resolve()
            self.assertTrue(root.is_relative_to(HERE.resolve()))
            module=root/'module'
            module.mkdir()
            for name in ['app-install.sh','customize.sh','service.sh','control.sh','clash-control.sh']:
                shutil.copy2(ROOT/'module'/name,module/name)
            (module/'clash-control.sh').write_text('#!/bin/sh\nprintf \'{"ok":true,"code":"CLASH_STOPPED"}\\n\'\n',encoding='utf8',newline='\n')
            apk=module/'system/priv-app/SmartVoWifiCall/SmartVoWifiCall.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'new-apk-payload\x00\r\n')
            service=(module/'service.sh').read_text().replace('MODDIR=${0%/*}','MODDIR="$test_module"').replace(
                'BASE=/data/adb/smartvowificall_ims','BASE="$fixture_base"').replace('/system/bin/app_process','app_process')
            (module/'service-host.sh').write_text(service,encoding='utf8')
            script=root/'test.sh';script.write_text(HARNESS,encoding='utf8')
            result=subprocess.run([str(BASH),str(script),str(root),scenario],capture_output=True,text=True,timeout=15)
            count=lambda name:int((root/name).read_text())
            data={'code':result.returncode,'out':result.stdout,'err':result.stderr,
                  'installs':count('installs'),'checks':count('checks'),'starts':count('starts'),
                  'radio_releases':count('radio_releases'),
                  'status':(root/'runtime/app-install.status').read_text().strip() if (root/'runtime/app-install.status').exists() else '',
                  'active':(root/'installed.apk').read_bytes() if (root/'installed.apk').exists() else None}
            self.assertEqual((root/'preferences').read_text(),'enabled=true;iface=tun0;billable=true;uid=10703\n')
            self.assertEqual((root/'sim-config').read_text(),'existing-sim-config\n')
            self.assertNotIn('private detail',result.stdout)
            return data

    def test_fresh(self):
        r=self.run_scenario('fresh');self.assertEqual(r['code'],0);self.assertIn('RESULT:0:APP_INSTALLED',r['out']);self.assertEqual(r['installs'],1)
    def test_upgrade(self):
        r=self.run_scenario('upgrade');self.assertEqual(r['code'],0);self.assertIn('RESULT:0:APP_INSTALLED',r['out']);self.assertEqual(r['checks'],1)
    def test_identical(self):
        r=self.run_scenario('identical');self.assertIn('RESULT:0:APP_CURRENT',r['out']);self.assertEqual(r['installs'],0);self.assertEqual(r['checks'],0)
    def test_signature(self):
        r=self.run_scenario('signature');self.assertIn('RESULT:2:APP_SIGNATURE_MISMATCH',r['out']);self.assertEqual(r['active'],b'old-apk\n')
    def test_downgrade(self):
        r=self.run_scenario('downgrade');self.assertIn('RESULT:2:APP_DOWNGRADE_REJECTED',r['out']);self.assertEqual(r['active'],b'old-apk\n')
    def test_failure_exit_zero(self):
        r=self.run_scenario('failure_zero');self.assertIn('RESULT:1:APP_INSTALL_FAILED',r['out']);self.assertEqual(r['active'],b'old-apk\n')
    def test_readback_mismatch(self):
        r=self.run_scenario('verify_bad');self.assertIn('RESULT:1:APP_VERIFY_FAILED',r['out'])
    def test_missing_apk(self):
        r=self.run_scenario('missing');self.assertIn('RESULT:2:APP_APK_MISSING',r['out']);self.assertEqual(r['installs'],0)
    def test_call_active(self):
        r=self.run_scenario('call_active');self.assertIn('RESULT:10:APP_WAIT_CALL_IDLE',r['out']);self.assertEqual(r['installs'],0)
    def test_call_unknown(self):
        r=self.run_scenario('call_unknown');self.assertIn('RESULT:10:APP_WAIT_CALL_IDLE',r['out']);self.assertEqual(r['installs'],0)
    def test_customize_installs(self):
        r=self.run_scenario('custom_fresh');self.assertEqual(r['code'],0);self.assertEqual(r['installs'],1)
    def test_customize_defers_active_call(self):
        r=self.run_scenario('custom_call');self.assertEqual(r['code'],0);self.assertIn('deferred',r['out']);self.assertEqual(r['installs'],0)
    def test_customize_rejects_wrong_signature(self):
        r=self.run_scenario('custom_signature');self.assertEqual(r['code'],42);self.assertEqual(r['active'],b'old-apk\n')
    def test_recovery_defers(self):
        r=self.run_scenario('recovery');self.assertEqual(r['code'],0);self.assertEqual(r['installs'],0);self.assertIn('automatically on boot',r['out'])
    def test_platform_gate(self):
        for case in ['api_old','abi_wrong']:
            with self.subTest(case=case):
                r=self.run_scenario(case);self.assertEqual(r['code'],42);self.assertEqual(r['installs'],0)
    def test_boot_retries_then_starts(self):
        r=self.run_scenario('boot_retry');self.assertEqual(r['code'],0,r);self.assertEqual(r['installs'],2);self.assertEqual(r['starts'],1);self.assertEqual(r['status'],'APP_INSTALLED')
    def test_boot_identical_skips_reinstall(self):
        r=self.run_scenario('boot_identical');self.assertEqual(r['code'],0,r);self.assertEqual(r['installs'],0);self.assertEqual(r['starts'],1);self.assertEqual(r['status'],'APP_CURRENT')
    def test_boot_waits_for_call_idle(self):
        r=self.run_scenario('boot_call');self.assertEqual(r['code'],0,r);self.assertEqual(r['checks'],2);self.assertEqual(r['installs'],1);self.assertEqual(r['starts'],1);self.assertEqual(r['radio_releases'],1)
    def test_boot_rollback_retries_pending_radio_release(self):
        r=self.run_scenario('boot_radio_retry');self.assertEqual(r['code'],0,r);self.assertEqual(r['starts'],1);self.assertEqual(r['radio_releases'],2)
    def test_boot_signature_failure_starts_nothing(self):
        r=self.run_scenario('boot_signature');self.assertEqual(r['code'],1,r);self.assertEqual(r['starts'],0);self.assertEqual(r['status'],'APP_SIGNATURE_MISMATCH')
    def test_scripts_parse(self):
        for name in ['app-install.sh','customize.sh','service.sh','control.sh','clash-control.sh','uninstall.sh']:
            subprocess.run([str(BASH),'-n',str(ROOT/'module'/name)],check=True)

if __name__=='__main__':unittest.main(verbosity=2)
