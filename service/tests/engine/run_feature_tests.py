"""Run actual feature/session/audio code against Android and engine substitutes.

--baseline REF compiles the old production sources without changing the checkout.
No Android device or real engine connection is used.
"""
from pathlib import Path
import ast
import argparse
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
JAVA = Path(r'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin')
parser = argparse.ArgumentParser()
parser.add_argument('--baseline')
parser.add_argument('--case', default='all')
args = parser.parse_args()
tree = ast.parse((HERE/'run_call_tests.py').read_text(encoding='utf8'))
stubs = next(ast.literal_eval(node.value) for node in tree.body
             if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'STUBS' for t in node.targets))
del stubs['com/smartvowificall/ims/NewMmTelFeature.java']
stubs.update({
 'android/telephony/TelephonyManager.java': '''package android.telephony; public class TelephonyManager { public boolean isEmergencyNumber(String n){ if("throw".equals(n)) throw new RuntimeException("classifier unavailable"); return "911".equals(n); } }''',
 'android/os/Bundle.java': 'package android.os; public class Bundle {}',
 'android/telephony/ims/ImsCallProfile.java': '''package android.telephony.ims;
import java.util.*;
public class ImsCallProfile {
 public static final int SERVICE_TYPE_NORMAL=1,CALL_TYPE_VOICE=2;
 public static final int OIR_PRESENTATION_NOT_RESTRICTED=2,OIR_PRESENTATION_UNKNOWN=3;
 public static final String EXTRA_OI="oi",EXTRA_OIR="oir";
 private final Map<String,Object> extras=new HashMap<>(); private final int service,type;
 public ImsCallProfile(int s,int t){service=s;type=t;}
 public int getServiceType(){return service;} public int getCallType(){return type;}
 public ImsStreamMediaProfile getMediaProfile(){return new ImsStreamMediaProfile();}
 public void setCallExtra(String k,String v){extras.put(k,v);} public void setCallExtraInt(String k,int v){extras.put(k,v);}
 public String getCallExtra(String k){return (String)extras.get(k);} public int getCallExtraInt(String k){return (Integer)extras.getOrDefault(k,0);}
}''',
 'android/telephony/ims/stub/ImsRegistrationImplBase.java': 'package android.telephony.ims.stub; public class ImsRegistrationImplBase {public static final int REGISTRATION_TECH_IWLAN=1;}',
 'android/telephony/ims/stub/ImsSmsImplBase.java': 'package android.telephony.ims.stub; public class ImsSmsImplBase {}',
 'android/telephony/ims/feature/CapabilityChangeRequest.java': '''package android.telephony.ims.feature; import java.util.*;
public class CapabilityChangeRequest {public List<CapabilityPair> getCapabilitiesToDisable(){return Collections.emptyList();} public List<CapabilityPair> getCapabilitiesToEnable(){return Collections.emptyList();} public static class CapabilityPair {public int getRadioTech(){return 1;}public int getCapability(){return 1;}}}''',
 'android/telephony/ims/feature/MmTelFeature.java': '''package android.telephony.ims.feature;
import java.util.*;import java.util.concurrent.*;import android.os.Bundle;import android.telephony.ims.*;import android.telephony.ims.stub.*;
public class MmTelFeature {
 public static final int STATE_INITIALIZING=1,STATE_READY=2,STATE_UNAVAILABLE=0,AUDIO_HANDLER_ANDROID=0,PROCESS_CALL_CSFB=1,PROCESS_CALL_IMS=0,CAPABILITY_ERROR_GENERIC=1;
 public ImsCallSessionListener lastIncomingListener; public ImsCallSessionImplBase lastIncoming;public String lastIncomingId;public int notifications;public boolean rejectIncoming;
 public int audioHandlerNotifications,lastAudioHandler=-1;
 public MmTelFeature(Executor executor){} protected void setFeatureState(int s){} public void setCallAudioHandler(int a){audioHandlerNotifications++;lastAudioHandler=a;}
 public int capabilityNotifications,lastCapabilities;
 public void notifyCapabilitiesStatusChanged(MmTelCapabilities c){capabilityNotifications++;lastCapabilities=c.mask;}
 public void notifyIncomingCall(ImsCallSessionImplBase s,Bundle b){
  // AOSP's deprecated compatibility path still attaches a listener; it is not
  // intentionally broken in this fixture to make the new overload look necessary.
  lastIncoming=s;notifications++;lastIncomingListener=new ImsCallSessionListener();s.setListener(lastIncomingListener);
 }
 public ImsCallSessionListener notifyIncomingCall(ImsCallSessionImplBase s,String id,Bundle b){
  lastIncoming=s;lastIncomingId=id;notifications++;lastIncomingListener=rejectIncoming?null:new ImsCallSessionListener();return lastIncomingListener;
 }
 public static class MmTelCapabilities {public static final int CAPABILITY_TYPE_VOICE=1,CAPABILITY_TYPE_SMS=8;public int mask;public void addCapabilities(int c){mask|=c;}}
 public static class CapabilityCallbackProxy {public void onChangeCapabilityConfigurationError(int c,int t,int e){}}
}''',
 'com/smartvowificall/ims/RootSupervisor.java': '''package com.smartvowificall.ims; import java.util.*;
final class RootSupervisor {static final class Policy {final int slotId=0,subId=19;final String simKey="fixture";}
static final class Sim{final Policy policy=new Policy();} static final class Snapshot{final List<Sim> sims=Arrays.asList(new Sim());}}''',
 'com/smartvowificall/ims/RuntimeController.java': '''package com.smartvowificall.ims;
import java.util.*;import java.util.concurrent.*;import android.content.Context;import android.telephony.ims.ImsReasonInfo;import com.smartvowificall.ims.engine.EngineSession;
final class RuntimeController {
 final Context context=new Context(); final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"test-state")),io=Executors.newCachedThreadPool();
 final Executor binderExecutor=Runnable::run; final RootSupervisor.Snapshot snapshot=new RootSupervisor.Snapshot();
 final Map<Integer,Binding> bindings=new ConcurrentHashMap<>(); final Map<Integer,NewMmTelFeature> features=new ConcurrentHashMap<>();
 boolean allowBillable=true,radioAvailable=true,failRadioRead;int radioChecks;boolean billable(){return allowBillable;}boolean radioReady(Binding b){return radioAvailable;}void verifyRadioOff(Binding b)throws Exception{radioChecks++;if(!radioAvailable||failRadioRead)throw new Exception("CELLULAR_RADIO_NOT_OFF");} void kick(){} static ImsReasonInfo reason(int c){return new ImsReasonInfo(c,0,"");}
 static final class Binding{final EngineSession engine;final RootSupervisor.Sim sim;volatile boolean registered=true,callActive,reported;Binding(EngineSession e,RootSupervisor.Sim s){engine=e;sim=s;}}
}''',
 'com/smartvowificall/ims/NewSms.java': '''package com.smartvowificall.ims;import java.util.*;import android.telephony.ims.stub.ImsSmsImplBase;final class NewSms extends ImsSmsImplBase{NewSms(NewMmTelFeature f){}void received(Map<String,Object> e){}}'''
})
stubs['android/telephony/ims/stub/ImsCallSessionImplBase.java'] = stubs['android/telephony/ims/stub/ImsCallSessionImplBase.java'].replace(
    'public class ImsCallSessionImplBase {', 'public class ImsCallSessionImplBase {public void setListener(android.telephony.ims.ImsCallSessionListener l){}')

with tempfile.TemporaryDirectory(prefix='feature-host-build-', dir=HERE) as temporary:
    build = Path(temporary)
    sources = []
    for name, source in stubs.items():
        target=build/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(source,encoding='utf8');sources.append(target)
    app=ROOT/'app/src/main/java/com/smartvowificall/ims'
    for source in [app/'NewMmTelFeature.java',app/'NewCallSession.java',app/'PcmAudio.java']:
        if args.baseline:
            data=subprocess.run(['git','show',args.baseline+':'+source.relative_to(ROOT.parent).as_posix()],cwd=ROOT.parent,capture_output=True,check=True).stdout
            target=build/'baseline'/source.name;target.parent.mkdir(exist_ok=True);target.write_bytes(data);sources.append(target)
        else:sources.append(source)
    sources+=list((app/'engine').glob('*.java'))+[HERE/'EngineContractTest.java',HERE/'FeatureLifecycleTest.java']
    subprocess.run([str(JAVA/'javac.exe'),'--release','11','-encoding','UTF-8','-d',str(build),*map(str,sources)],check=True)
    subprocess.run([str(JAVA/'java.exe'),'-ea','-cp',str(build),'com.smartvowificall.ims.FeatureLifecycleTest',args.case],check=True,timeout=35)
