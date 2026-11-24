"""Compile and run only pure Java policy/network scenarios; no device operations."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
JDK = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"))
SOURCE = ROOT / "app/src/main/java/com/smartvowificall/ims"
OUT = HERE / ".build"
OUT.mkdir(exist_ok=True)
sources = [SOURCE / "policy/PolicyEngine.java", SOURCE / "network/InterfaceSnapshot.java",
           SOURCE / "network/InterfaceParser.java", SOURCE / "network/TunEgress.java", HERE / "PolicyNetworkTest.java"]
subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8",
                "-d", str(OUT), *map(str, sources)], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "PolicyNetworkTest"], check=True)

subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                str(SOURCE / "policy/RegistrationRouting.java"), str(HERE / "RegistrationRoutingTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "RegistrationRoutingTest"], check=True)
subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                str(SOURCE / "policy/VoiceServiceOverride.java"), str(HERE / "VoiceServiceOverrideTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "VoiceServiceOverrideTest"], check=True)
subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                str(SOURCE / "policy/PolicyLoop.java"), str(SOURCE / "policy/ServiceLifecycle.java"),
                str(HERE / "PowerLifecycleTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "PowerLifecycleTest"], check=True)
subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                str(SOURCE / "policy/ExclusiveRadio.java"), str(HERE / "ExclusiveRadioTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "ExclusiveRadioTest"], check=True)
subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                str(SOURCE / "clash/ClashConfig.java"), str(SOURCE / "clash/SubscriptionQrParser.java"),
                str(HERE / "ClashConfigTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "ClashConfigTest"], check=True)

subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                str(SOURCE / "root/SimCredentials.java"), str(HERE / "SimCredentialsTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT),
                "com.smartvowificall.ims.root.SimCredentialsTest"], check=True)

subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                str(SOURCE / "network/EngineNetworkSnapshot.java"), str(SOURCE / "engine/JsonCodec.java"),
                str(HERE / "EngineNetworkSnapshotTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "EngineNetworkSnapshotTest"], check=True)

subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                 str(SOURCE / "policy/FallbackPolicy.java"), str(HERE / "FallbackPolicyTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "FallbackPolicyTest"], check=True)

subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                 str(SOURCE / "engine/JsonCodec.java"),
                 str(SOURCE / "telemetry/EventRingBuffer.java"),
                 str(SOURCE / "telemetry/LogSanitizer.java"),
                 str(SOURCE / "telemetry/NetworkLogBuffer.java"),
                 str(SOURCE / "telemetry/TelemetryClient.java"),
                 str(HERE / "TelemetrySanitizerTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "TelemetrySanitizerTest"], check=True)

subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                 str(SOURCE / "engine/JsonCodec.java"),
                 str(SOURCE / "root/RootDaemonProtocol.java"),
                 str(HERE / "RootDaemonProtocolTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "RootDaemonProtocolTest"], check=True)

subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                 str(SOURCE / "policy/CompatReport.java"),
                 str(HERE / "CompatReportTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "CompatReportTest"], check=True)

subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-d", str(OUT),
                 str(SOURCE / "policy/ChipsetStrategy.java"),
                 str(HERE / "ChipsetStrategyTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "ChipsetStrategyTest"], check=True)

subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8", "-cp", str(OUT), "-d", str(OUT),
                 str(SOURCE / "engine/JsonCodec.java"),
                 str(SOURCE / "policy/CarrierProfiles.java"),
                 str(HERE / "CarrierProfilesTest.java")], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(OUT), "CarrierProfilesTest"], check=True)

# Compile the Android adapters, but execute only their pure validation functions on the host.
SDK = Path(os.environ.get("VOWIFI_ANDROID_SDK", r"D:\Android\Sdk"))
ANDROID = SDK / "platforms/android-36/android.jar"
ANDROID_OUT = HERE / ".android-build"
ANDROID_OUT.mkdir(exist_ok=True)
android_sources = [SOURCE / "network/InterfaceInventory.java", SOURCE / "root/RootIdentityMain.java", SOURCE / "root/SimCredentials.java",
                   SOURCE / "policy/ExclusiveRadio.java", SOURCE / "policy/ChipsetStrategy.java", SOURCE / "clash/ClashConfig.java", HERE / "RootIdentityValidationTest.java"]
classpath = os.pathsep.join([str(ANDROID), str(ROOT / "deps/android-system.jar")])
subprocess.run([str(JDK / "bin/javac.exe"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                "-Xlint:-options", "-classpath", classpath, "-d", str(ANDROID_OUT),
                *map(str, sources[:4]), *map(str, android_sources)], check=True)
subprocess.run([str(JDK / "bin/java.exe"), "-cp", os.pathsep.join([str(ANDROID_OUT), str(ANDROID)]),
                "com.smartvowificall.ims.root.RootIdentityValidationTest"], check=True)
