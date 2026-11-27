"""Host tests for LPA input boundaries; no phone, server or eUICC is contacted."""
import os
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[3]
jdk = pathlib.Path(os.environ.get("JAVA_HOME", "C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"))
suffix = ".exe" if os.name == "nt" else ""
with tempfile.TemporaryDirectory(prefix="smartvowifi-lpa-tests-") as output:
    subprocess.run([str(jdk / "bin" / ("javac" + suffix)), "-encoding", "UTF-8", "-d", output,
                    str(root / "service/app/src/main/java/com/smartvowificall/ims/lpa/LpaProtocol.java"),
                    str(pathlib.Path(__file__).with_name("LpaProtocolTest.java")),
                    str(root / "service/app/src/main/java/com/smartvowificall/ims/root/LpaSlotLock.java"),
                    str(pathlib.Path(__file__).with_name("LpaSlotLockTest.java"))], check=True)
    subprocess.run([str(jdk / "bin" / ("java" + suffix)), "-ea", "-cp", output, "LpaProtocolTest"], check=True)
    subprocess.run([str(jdk / "bin" / ("java" + suffix)), "-ea", "-cp", output,
                    "com.smartvowificall.ims.root.LpaSlotLockTest"], check=True)
