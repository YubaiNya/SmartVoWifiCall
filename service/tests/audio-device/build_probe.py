"""Build only the same-signed audio instrumentation APK, using existing local tools."""
from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
SERVICE = HERE.parents[1]
SDK = Path(os.environ.get('ANDROID_HOME', r'D:\Android\Sdk'))
BT = SDK / 'build-tools' / '37.0.0'
JAVA = Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot')) / 'bin'
SYSTEM_JAR = SERVICE / 'deps' / 'android-system.jar'
PLATFORM_JAR = SDK / 'platforms' / 'android-37.0' / 'android.jar'
BUILD = HERE / '.build'
COMPONENT = 'com.smartvowificall.ims.audiotest/com.smartvowificall.ims.audiotest.AudioProbeInstrumentation'


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], cwd=HERE, check=True, **kwargs)


def signing_config():
    gradle = (SERVICE / 'app' / 'build.gradle.kts').read_text(encoding='utf-8')
    patterns = {
        'store': r'storeFile\s*=\s*rootProject\.file\("([^"\r\n]+)"\)',
        'store_pass': r'storePassword\s*=\s*"([^"\r\n]+)"',
        'alias': r'keyAlias\s*=\s*"([^"\r\n]+)"',
        'key_pass': r'keyPassword\s*=\s*"([^"\r\n]+)"',
    }
    values = {}
    for name, pattern in patterns.items():
        match = re.search(pattern, gradle)
        if not match:
            raise SystemExit('Existing signing configuration is unsupported: ' + name)
        values[name] = match.group(1)
    values['store'] = SERVICE / values['store']
    return values


def certificate(apk):
    result = run([JAVA / 'java.exe', '-jar', BT / 'lib' / 'apksigner.jar',
                  'verify', '--print-certs', apk], capture_output=True, text=True)
    match = re.search(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', result.stdout)
    if not match:
        raise SystemExit('Could not verify signer certificate')
    return match.group(1)


def main():
    signing = signing_config()
    tools = [JAVA / 'java.exe', JAVA / 'javac.exe', BT / 'aapt2.exe', BT / 'zipalign.exe',
             BT / 'lib' / 'd8.jar', BT / 'lib' / 'apksigner.jar', SYSTEM_JAR, PLATFORM_JAR, signing['store']]
    missing = [str(path) for path in tools if not path.is_file()]
    if missing:
        raise SystemExit('Missing existing dependencies; no downloads attempted:\n' + '\n'.join(missing))
    BUILD.mkdir(exist_ok=True)
    classes, dex = BUILD / 'classes', BUILD / 'dex'
    for directory in (classes, dex):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    sources = sorted((HERE / 'src').rglob('*.java'))
    run([JAVA / 'javac.exe', '-J-Duser.language=en', '--release', '11', '-encoding', 'UTF-8',
         '-cp', SYSTEM_JAR, '-d', classes, *sources])
    run([JAVA / 'java.exe', '-cp', BT / 'lib' / 'd8.jar', 'com.android.tools.r8.D8',
         '--min-api', '33', '--lib', SYSTEM_JAR, '--output', dex, *sorted(classes.rglob('*.class'))])
    unsigned, aligned = BUILD / 'unsigned.apk', BUILD / 'aligned.apk'
    apk = BUILD / 'SmartVoWifiCall-audio-probe.apk'
    run([BT / 'aapt2.exe', 'link', '--manifest', HERE / 'AndroidManifest.xml',
         '-I', PLATFORM_JAR, '--min-sdk-version', '33', '--target-sdk-version', '37', '-o', unsigned])
    with zipfile.ZipFile(unsigned, 'a') as archive:
        for item in sorted(dex.glob('*.dex')):
            archive.write(item, item.name, compress_type=zipfile.ZIP_DEFLATED)
    run([BT / 'zipalign.exe', '-f', '4', unsigned, aligned])
    environment = os.environ.copy()
    environment['AUDIO_PROBE_STORE_PASS'] = signing['store_pass']
    environment['AUDIO_PROBE_KEY_PASS'] = signing['key_pass']
    run([JAVA / 'java.exe', '-jar', BT / 'lib' / 'apksigner.jar', 'sign',
         '--ks', signing['store'], '--ks-key-alias', signing['alias'],
         '--ks-pass', 'env:AUDIO_PROBE_STORE_PASS', '--key-pass', 'env:AUDIO_PROBE_KEY_PASS',
         '--out', apk, aligned], env=environment)
    probe_cert = certificate(apk)
    product = SERVICE / 'dist' / 'SmartVoWifiCall.apk'
    if product.is_file() and certificate(product) != probe_cert:
        raise SystemExit('Probe signer differs from the existing product APK')
    run([BT / 'zipalign.exe', '-c', '4', apk])
    print('APK: ' + str(apk))
    print('SHA256: ' + hashlib.sha256(apk.read_bytes()).hexdigest())
    print('Same product signing configuration; APK signature and alignment verified.')
    print('Component: ' + COMPONENT)
    print('Device execution is intentionally not performed by this script.')


if __name__ == '__main__':
    main()
