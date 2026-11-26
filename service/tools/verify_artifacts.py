"""Read-only validation of the APK/module pair, self-owned Go engine, and pinned mihomo binary."""
import hashlib,json,pathlib,struct,subprocess,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
dist=root/'dist'
hashes=json.loads((dist/'SHA256.json').read_text())
for name,expected in hashes.items():
 assert hashlib.sha256((dist/name).read_bytes()).hexdigest()==expected,name
mihomo=json.loads((root/'deps/mihomo.source.json').read_text())
apk=(dist/'SmartVoWifiCall.apk').read_bytes()
with zipfile.ZipFile(dist/'SmartVoWifiCall.apk') as z:
 assert z.testzip() is None
 assert 'AndroidManifest.xml' in z.namelist()
 assert 'assets/licenses/APP-GPL-3.0.txt' in z.namelist()
 assert 'assets/licenses/Apache-2.0.txt' in z.namelist()
 assert not any('/.ai-local/' in n or n.startswith(('research/','logs/','docs/')) for n in z.namelist())
 assert b'ic_launcher' in z.read('resources.arsc') and b'ic_launcher_round' in z.read('resources.arsc')
 dex=b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
 assert b'Lcom/smartvowificall/ims/SmartVoWifiImsService;' in dex
 assert b'Lcom/smartvowificall/ims/MainActivity;' in dex
 assert b'Lcom/smartvowificall/ims/LiquidGlassBottomBarKt;' in dex
 assert b'Lcom/smartvowificall/ims/AppScreensKt;' in dex
 assert b'Lcom/smartvowificall/ims/root/SimCredentials;' in dex
 assert b'Lcom/smartvowificall/ims/network/TunEgress;' in dex
 assert b'Lcom/smartvowificall/ims/policy/ExclusiveRadio;' in dex
 assert b'Lcom/smartvowificall/ims/policy/FallbackPolicy;' in dex
 assert b'Lcom/smartvowificall/ims/policy/BehaviorSummary;' in dex
 assert b'Lcom/smartvowificall/ims/policy/VoiceServiceOverride;' in dex
 assert b'Lcom/smartvowificall/ims/policy/PolicyLoop;' in dex
 assert b'Lcom/smartvowificall/ims/policy/ServiceLifecycle;' in dex
 assert b'Lcom/smartvowificall/ims/clash/ClashConfig;' in dex
 assert b'Lcom/smartvowificall/ims/root/RootClashMain;' in dex
 assert b'Lcom/smartvowificall/ims/root/RootSimAuthMain;' in dex
 assert b'Lcom/smartvowificall/ims/root/RootDaemonMain;' in dex
 assert b'com.legacy.ims' not in dex
 assert b'Lcom/legacy/ims/' not in dex
with zipfile.ZipFile(dist/'SmartVoWifiCall-module.zip') as z:
 assert z.testzip() is None
 assert z.read('system/priv-app/SmartVoWifiCall/SmartVoWifiCall.apk')==apk
 engine_bin=z.read('bin/vowifi-engine-arm64')
 assert engine_bin[:6]==b'\x7fELF\x02\x01','engine must be 64-bit little-endian ELF'
 assert struct.unpack_from('<H',engine_bin,18)[0]==183,'engine must target EM_AARCH64'
 assert b'smartvowificall-engine' in engine_bin,'engine must be self-owned SmartVoWifiCall Go build'
 assert hashlib.sha256(z.read('bin/mihomo-arm64')).hexdigest()==mihomo['sha256']
 assert b'id=smartvowificall\n' in z.read('module.prop')
 assert b'com.legacy.ims' not in z.read('customize.sh')
 assert b'nv_install_app "$MODPATH"' in z.read('customize.sh')
 assert b'nv_install_app "$MODDIR"' in z.read('service.sh')
 assert b'install -r --user 0 -S "$nv_size" < "$nv_apk"' in z.read('app-install.sh')
 assert b'RootNetworkMain watch' in z.read('service.sh')
 assert b'RootSimAuthMain' in z.read('service.sh')
 assert b'RootDaemonMain' in z.read('service.sh')
 assert b'RootSimAuthMain' in z.read('control.sh')
 assert b'--sim-mode=auto' in z.read('control.sh')
 assert b'radio-release-all' in z.read('service.sh')
 assert b'radio-release-all' in z.read('uninstall.sh')
 assert b'clash-control.sh" stop' in z.read('service.sh')
 assert b'clash-control.sh" stop' in z.read('uninstall.sh')
 assert b'check_tun_egress || fail TUN_EGRESS_UNAVAILABLE' in z.read('control.sh')
 assert b'/data/adb/vowifi/' not in engine_bin
 assert b'/data/adb/nvwims/' not in engine_bin
 for name in ['control.sh','clash-control.sh','customize.sh','service.sh','uninstall.sh','app-install.sh']:
  assert b'\r' not in z.read(name),name+' must use LF line endings'
 for name in z.namelist():
  assert not name.startswith('/') and '..' not in pathlib.PurePosixPath(name).parts
  assert not name.endswith(('.p12','.key'))
  assert not any(part in {'docs','research','knowledge','logs','agent-memory','.ai-local','upstream-sources'} for part in pathlib.PurePosixPath(name).parts)
  assert not name.endswith(('.log','.jsonl','.pcap','.pcapng'))
java=pathlib.Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin/java.exe')
subprocess.run([str(java),'-jar','D:/Android/Sdk/build-tools/37.0.0/lib/apksigner.jar','verify',str(dist/'SmartVoWifiCall.apk')],check=True)
print('PASS APK signature, standalone DEX, ZIP integrity, matching embedded APK, self-owned arm64 Go engine, mihomo hash, scripts and package paths.')
