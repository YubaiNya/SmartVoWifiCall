"""Compile and run dependency-free engine contract tests. Never opens a real transport."""
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
JAVA = Path(r'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin')
javac = str(JAVA / 'javac.exe') if (JAVA / 'javac.exe').exists() else shutil.which('javac')
java = str(JAVA / 'java.exe') if (JAVA / 'java.exe').exists() else shutil.which('java')
sources = sorted((ROOT / 'app/src/main/java/com/smartvowificall/ims/engine').glob('*.java'))
with tempfile.TemporaryDirectory(prefix='host-build-', dir=HERE) as build:
    subprocess.run([javac, '--release', '11', '-encoding', 'UTF-8', '-d', build,
                    *map(str, sources), str(HERE / 'EngineContractTest.java')], check=True)
    subprocess.run([java, '-ea', '-cp', build, 'com.smartvowificall.ims.engine.EngineContractTest'],
                   check=True, timeout=40)
