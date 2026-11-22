"""Build NewVoWiFi release APK (Gradle + Kotlin/Compose/Miuix/Backdrop) and standalone KernelSU/Magisk module."""
import hashlib,json,os,pathlib,shutil,subprocess,zipfile

ROOT=pathlib.Path(__file__).resolve().parents[1]
SDK=pathlib.Path(os.environ.get('ANDROID_HOME','D:/Android/Sdk'))
BT=SDK/'build-tools/37.0.0'
JAVA_HOME=pathlib.Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot')
JAVA=JAVA_HOME/'bin'
GRADLE=pathlib.Path('C:/Users/yubai/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle.bat')
OUT=ROOT/'dist'
OUT.mkdir(parents=True,exist_ok=True)

def run(args,env=None):
 subprocess.run([str(x) for x in args],check=True,cwd=ROOT,env=env)

key=ROOT/'local-signing.p12'
if not key.exists():
 run([JAVA/'keytool.exe','-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass','local-development','-keypass','local-development','-alias','newvowifi','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=NewVoWiFi Local Development'])

env=os.environ.copy()
env['JAVA_HOME']=str(JAVA_HOME)
env['ANDROID_HOME']=str(SDK)
run([GRADLE,'-p',ROOT,':app:assembleRelease','--console=plain'],env=env)

gradle_apk=ROOT/'app/build/outputs/apk/release/app-release.apk'
if not gradle_apk.exists():
 raise RuntimeError(f'Expected Gradle release APK at {gradle_apk}')
shutil.copy2(gradle_apk,OUT/'NewVoWiFi.apk')
run([JAVA/'java.exe','-jar',BT/'lib/apksigner.jar','verify',OUT/'NewVoWiFi.apk'])

module=ROOT/'module'
if module.exists():
 engine_meta=json.loads((ROOT/'deps/engine.source.json').read_text())
 engine_path=ROOT/'deps'/engine_meta['artifact']
 if hashlib.sha256(engine_path.read_bytes()).hexdigest()!=engine_meta['sha256']:
  raise RuntimeError('Bundled engine does not match pinned isolated artifact')
 mihomo_meta=json.loads((ROOT/'deps/mihomo.source.json').read_text())
 mihomo_path=ROOT/'deps'/mihomo_meta['artifact']
 if hashlib.sha256(mihomo_path.read_bytes()).hexdigest()!=mihomo_meta['sha256']:
  raise RuntimeError('Bundled mihomo binary does not match pinned artifact')
 with zipfile.ZipFile(OUT/'NewVoWiFi-module.zip','w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted(module.rglob('*')):
   if p.is_file():z.write(p,p.relative_to(module).as_posix())
  z.write(OUT/'NewVoWiFi.apk','system/priv-app/NewVoWiFi/NewVoWiFi.apk')
  z.write(engine_path,'bin/vowifi-engine-arm64')
  z.write(mihomo_path,'bin/mihomo-arm64')

hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in OUT.iterdir() if p.is_file() and p.suffix in ['.apk','.zip']}
(OUT/'SHA256.json').write_text(json.dumps(hashes,indent=2)+'\n')
print(json.dumps(hashes,indent=2))
