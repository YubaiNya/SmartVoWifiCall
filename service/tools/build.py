"""Build with installed Android/JDK tools; no Gradle or remote execution required."""
import hashlib,json,os,pathlib,subprocess,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
SDK=pathlib.Path(os.environ.get('ANDROID_HOME','D:/Android/Sdk'))
BT=SDK/'build-tools/37.0.0'
JAVA=pathlib.Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin')
BUILD=ROOT/'build'; OUT=ROOT/'dist'
for p in [BUILD/'classes',BUILD/'dex',OUT]:p.mkdir(parents=True,exist_ok=True)
# Remove only compiler output files under these resolved build directories.
for directory in [BUILD/'classes',BUILD/'dex']:
 for p in directory.rglob('*'):
  if p.is_file():
   if not p.resolve().is_relative_to(BUILD.resolve()):raise RuntimeError('Build path escaped workspace')
   p.unlink()
def run(args):subprocess.run([str(x) for x in args],check=True,cwd=ROOT)
sources=sorted((ROOT/'app/src/main/java').rglob('*.java'))
argfile=BUILD/'sources.txt';argfile.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources),encoding='utf-8')
run([JAVA/'javac.exe','-encoding','UTF-8','-source','11','-target','11','-classpath',ROOT/'deps/android-system.jar','-d',BUILD/'classes','@'+str(argfile)])
run([JAVA/'jar.exe','cf',BUILD/'classes.jar','-C',BUILD/'classes','.'])
run([JAVA/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--lib',ROOT/'deps/android-system.jar','--min-api','30','--output',BUILD/'dex',BUILD/'classes.jar'])
run([BT/'aapt2.exe','link','-I',SDK/'platforms/android-37.0/android.jar','--manifest',ROOT/'app/src/main/AndroidManifest.xml','-o',BUILD/'unsigned.apk'])
with zipfile.ZipFile(BUILD/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
 for p in sorted((BUILD/'dex').glob('*.dex')):z.write(p,p.name)
run([BT/'zipalign.exe','-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk'])
key=ROOT/'local-signing.p12'
if not key.exists():run([JAVA/'keytool.exe','-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass','local-development','-keypass','local-development','-alias','newvowifi','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=NewVoWiFi Local Development'])
run([JAVA/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key,'--ks-pass','pass:local-development','--out',OUT/'NewVoWiFi.apk',BUILD/'aligned.apk'])
run([JAVA/'java.exe','-jar',BT/'lib/apksigner.jar','verify',OUT/'NewVoWiFi.apk'])
module=ROOT/'module'
if module.exists():
 engine_meta=json.loads((ROOT/'deps/engine.source.json').read_text())
 engine_path=ROOT/'deps'/engine_meta['artifact']
 if hashlib.sha256(engine_path.read_bytes()).hexdigest()!=engine_meta['sha256']:
  raise RuntimeError('Bundled engine does not match pinned isolated artifact')
 with zipfile.ZipFile(OUT/'NewVoWiFi-module.zip','w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted(module.rglob('*')):
   if p.is_file():z.write(p,p.relative_to(module).as_posix())
  z.write(OUT/'NewVoWiFi.apk','system/priv-app/NewVoWiFi/NewVoWiFi.apk')
  z.write(engine_path,'bin/vowifi-engine-arm64')
hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in OUT.iterdir() if p.is_file() and p.suffix in ['.apk','.zip']}
(OUT/'SHA256.json').write_text(json.dumps(hashes,indent=2)+'\n')
print(json.dumps(hashes,indent=2))
