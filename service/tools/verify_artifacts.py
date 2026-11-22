"""Read-only validation of the APK/module pair and pinned engine dependency."""
import hashlib,json,pathlib,subprocess,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
dist=root/'dist'
hashes=json.loads((dist/'SHA256.json').read_text())
for name,expected in hashes.items():
 assert hashlib.sha256((dist/name).read_bytes()).hexdigest()==expected,name
engine=json.loads((root/'deps/engine.source.json').read_text())
apk=(dist/'NewVoWiFi.apk').read_bytes()
with zipfile.ZipFile(dist/'NewVoWiFi.apk') as z:
 assert z.testzip() is None
 assert 'AndroidManifest.xml' in z.namelist()
 assert b'Lcom/newvowifi/ims/NewVoWifiImsService;' in z.read('classes.dex')
with zipfile.ZipFile(dist/'NewVoWiFi-module.zip') as z:
 assert z.testzip() is None
 assert z.read('system/priv-app/NewVoWiFi/NewVoWiFi.apk')==apk
 assert hashlib.sha256(z.read('bin/vowifi-engine-arm64')).hexdigest()==engine['sha256']
 assert b'id=newvowifi\n' in z.read('module.prop')
 for name in ['control.sh','customize.sh','service.sh','uninstall.sh']:
  assert b'\r' not in z.read(name),name+' must use LF line endings'
 for name in z.namelist():
  assert not name.startswith('/') and '..' not in pathlib.PurePosixPath(name).parts
  assert not name.endswith(('.p12','.key'))
java=pathlib.Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin/java.exe')
subprocess.run([str(java),'-jar','D:/Android/Sdk/build-tools/37.0.0/lib/apksigner.jar','verify',str(dist/'NewVoWiFi.apk')],check=True)
print('PASS APK signature, DEX service, ZIP integrity, matching embedded APK, pinned engine hash, script line endings and package paths.')
