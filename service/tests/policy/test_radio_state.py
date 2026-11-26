"""Exercise actual RootIdentityMain radio-read methods with AOSP-shaped Binder replies.

The mock deliberately keeps physical power OFF while the voice-service override
reports IN_SERVICE (AOSP CTS testSetVoiceServiceStateOverride). No device calls.
"""
import os
import pathlib
import re
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/smartvowificall/ims"
JAVA = pathlib.Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot")) / "bin"
source = (APP / "root/RootIdentityMain.java").read_text(encoding="utf-8")

def method(name):
    match = re.search(r"  private static (?:boolean|int) " + name + r"\([^\n]*\)(?: throws Exception)? \{", source)
    if not match:
        return ""
    start = source.index("{", match.start())
    depth = 1
    end = start + 1
    while depth:
        if source[end] == "{": depth += 1
        if source[end] == "}": depth -= 1
        end += 1
    return source[match.start():end]

methods = "\n".join(method(name) for name in [
    "isModemEnabledForSlot", "isSubRadioOn", "physicalRadioState", "radioState"])
assert method("radioState"), "Production radio selector missing"
harness = r'''
import com.smartvowificall.ims.policy.*;
import com.smartvowificall.ims.network.*;
import java.util.*;
public final class RadioStateAdapterTest {
  static final String RADIO_METHOD_MODEM="modem", RADIO_METHOD_SUB="radio_sub";
  static String leaseMethod=RADIO_METHOD_SUB;
  static Object physical=1;
  static boolean voiceOverride, modemEnabled=true, modemApi=true, physicalApi=true;
  static int legacyReads, physicalReads, writes;
  static final class TelephonyManager {
    static final int RADIO_POWER_OFF=0, RADIO_POWER_ON=1, RADIO_POWER_UNAVAILABLE=2;
  }
  static final class Failure extends Exception { Failure(String code) { super(code); } }
  static final class Identity { int phoneId=1, slotId=1, subId=71; }
  static String currentLeaseRadioMethod(int slot) { return leaseMethod; }
  static Object binderCall(String service,String iface,String method,Class<?>[] types,Object[] args) throws Exception {
    if (method.equals("getRadioPowerState")) {
      if (!physicalApi) throw new NoSuchMethodException();
      if (!Integer.valueOf(1).equals(args[0])) throw new AssertionError("Power read used subId instead of phone index");
      physicalReads++; return physical;
    }
    if (method.equals("isModemEnabledForSlot")) {
      if (!modemApi) throw new NoSuchMethodException();
      return modemEnabled;
    }
    if (method.startsWith("isRadioOn")) {
      legacyReads++;
      // AOSP PhoneInterfaceManager derives this from the merged ServiceState.
      return voiceOverride || Integer.valueOf(1).equals(physical);
    }
    throw new AssertionError("Unexpected Binder operation");
  }
  static final class Radio implements ExclusiveRadio.Backend {
    boolean lease, restore;
    public int state() throws Exception { return radioState(new Identity()); }
    public boolean hasLease() { return lease; }
    public void saveLease(boolean on) { lease=true;restore=on; }
    public boolean restoreOn() { return restore; }
    public void power(boolean on) { writes++;physical=on?1:0; }
    public void forget() { lease=false; }
  }
  static void require(boolean ok,String why) { if(!ok)throw new AssertionError(why); }
  public static void main(String[] args) throws Exception {
    Radio radio=new Radio();
    require(ExclusiveRadio.hold(radio),"Initial physical OFF must succeed");
    VoiceServiceOverride display=new VoiceServiceOverride();
    display.synchronize(true,false,v -> voiceOverride=v);
    InterfaceSnapshot.Builder inventory=new InterfaceSnapshot.Builder();
    InterfaceSnapshot.MutableEntry tun=inventory.entry("wfctun0");
    tun.up=true;tun.tunDevice=true;tun.address("198.19.0.1");
    PolicyEngine.Sim sim=new PolicyEngine.Sim("synthetic-sim",71,1,true,"23433");
    Map<Integer,PolicyEngine.AppliedOverride> applied=Collections.singletonMap(1,
        new PolicyEngine.AppliedOverride(sim.simKey,71,1,"wfctun0"));
    for(int cycle=0;cycle<24;cycle++) {
      boolean held=ExclusiveRadio.hold(radio);
      PolicyEngine.Plan plan=PolicyEngine.evaluate(held?Collections.singletonList(sim):Collections.emptyList(),
          Collections.singletonMap(sim.simKey,"wfctun0"),inventory.build(),applied,false);
      require(plan.actions.isEmpty(),"REGISTERED -> voice override -> false RADIO_ON -> CLEAR (restart loop)");
    }
    require(writes==1,"A voice override caused redundant RF power commands");
    require(legacyReads==0,"Merged ServiceState must not be used as a power sensor");
    require(physicalReads>0,"No physical state was read");
    for(Object invalid:new Object[]{2,-1,9,null,Boolean.FALSE,"0"}) {
      physical=invalid;int prior=writes;
      try { ExclusiveRadio.hold(new Radio());throw new AssertionError("Unknown power treated as OFF"); }
      catch(Failure expected) { require(writes==prior,"Unknown state changed RF"); }
    }
    physical=0;physicalApi=false;
    try { radio.state();throw new AssertionError("Unavailable API fell back to service state"); }
    catch(NoSuchMethodException expected) { }
    physicalApi=true;physical=1;
    require(radio.state()==ExclusiveRadio.ON,"Actual RF ON hidden by stale lease");
    physical=0;leaseMethod=RADIO_METHOD_MODEM;modemEnabled=false;
    require(radio.state()==ExclusiveRadio.OFF,"Existing modem lease regressed");
    modemApi=false;
    require(radio.state()==ExclusiveRadio.OFF,"Missing modem API must use physical power");
    require(legacyReads==0,"Unsafe service-state fallback was invoked");
    System.out.println("PASS radio adapter: 24 registered cycles preserved; physical ON/unknown/API/slot/modem boundaries checked");
  }
'''
harness += methods + "\n}\n"
with tempfile.TemporaryDirectory(prefix="radio-state-host-") as directory:
    build=pathlib.Path(directory)
    test=build/"RadioStateAdapterTest.java";test.write_text(harness,encoding="utf-8")
    sources=[APP/"policy/ExclusiveRadio.java",APP/"policy/VoiceServiceOverride.java",
             APP/"policy/PolicyEngine.java",APP/"network/InterfaceSnapshot.java",APP/"network/TunEgress.java",test]
    subprocess.run([str(JAVA/"javac.exe"),"--release","8","-encoding","UTF-8","-d",str(build),*map(str,sources)],check=True)
    subprocess.run([str(JAVA/"java.exe"),"-cp",str(build),"RadioStateAdapterTest"],check=True,timeout=15)
