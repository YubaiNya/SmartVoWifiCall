package com.smartvowificall.ims;

import android.telephony.ims.*;
import android.telephony.ims.stub.ImsCallSessionImplBase.State;
import com.smartvowificall.ims.engine.*;
import com.smartvowificall.ims.engine.EngineContractTest.MemoryTransport;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Real feature + session + PCM code; fixed protocol fixtures and no live calls. */
public final class FeatureLifecycleTest {
  private static int checks;
  static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
  static void await(BooleanSupplier condition,String label)throws Exception{
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
    while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);
    check(condition.getAsBoolean(),label);
  }
  static final class Audio implements EngineRpcClient.Transport {
    final CountDownLatch closing=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
    volatile boolean block;
    public InputStream input(){return new InputStream(){public int read()throws IOException{try{closed.await();return -1;}catch(InterruptedException e){throw new IOException(e);}}};}
    public OutputStream output(){return new ByteArrayOutputStream();}
    public void close(){closing.countDown();try{if(block)release.await();}catch(InterruptedException ignored){}closed.countDown();}
  }
  static final class Harness implements AutoCloseable {
    final RuntimeController rt=new RuntimeController();
    final AtomicReference<Map<String,Object>> wire=new AtomicReference<>();
    final AtomicBoolean unavailable=new AtomicBoolean();final AtomicInteger starts=new AtomicInteger(),stops=new AtomicInteger(),dials=new AtomicInteger();
    final Audio audio=new Audio();final EngineSession engine;final RuntimeController.Binding binding;final NewMmTelFeature feature;
    Harness()throws Exception{
      wire.set(data(1,"IDLE",null,null,null));
      engine=new EngineSession(new EngineSession.Identity("4444444444444444444",0,19),(path,timeout)->{
        if(path.endsWith(".audio.sock"))return audio;
        return new MemoryTransport((transport,request)->{
          String method=(String)request.get("method");
          if(method.equals("getCallSnapshot")){
            if(unavailable.get())transport.frame(EngineSession.map("id",request.get("id"),"error",EngineSession.map("code",2006,"message","snapshot temporarily unavailable")));
            else transport.reply(request,wire.get());
          }else{
            if(method.equals("audioStart"))starts.incrementAndGet();if(method.equals("audioStop"))stops.incrementAndGet();
            if(method.equals("dial")){dials.incrementAndGet();transport.reply(request,EngineSession.map("callId","next-outgoing","state","DIALING"));}
            else transport.reply(request,EngineSession.map("state",method.equals("answer")?"IN_CALL":"IDLE","accepted",true));
          }
        });
      },new EngineSession.Listener(){public void onEvent(EngineSession.Identity i,EngineState.Event e){}public void onDisconnected(EngineSession.Identity i,Throwable e){}},true);
      engine.connect(1000);binding=new RuntimeController.Binding(engine,rt.snapshot.sims.get(0));rt.bindings.put(0,binding);
      feature=new NewMmTelFeature(rt,0);rt.features.put(0,feature);feature.onFeatureReady();
    }
    void state(Runnable action)throws Exception{rt.worker.submit(action).get(1,TimeUnit.SECONDS);}
    void snapshot(long revision,String stage,String id,String direction,String peer)throws Exception{
      wire.set(data(revision,stage,id,direction,peer));engine.getCallSnapshot(1000);state(()->feature.snapshot(engine.getState().callSnapshot));state(()->{});
    }
    NewCallSession ring(String peer)throws Exception{snapshot(2,"RINGING","incoming","INCOMING",peer);return feature.calls.get("incoming");}
    void event(String name,Map<String,Object> fields)throws Exception{state(()->feature.event(new EngineState.Event(name,fields)));state(()->{});}
    public void close()throws Exception{
      audio.release.countDown();unavailable.set(false);state(()->{for(NewCallSession s:new ArrayList<>(feature.calls.values()))s.ended(true);if(feature.pendingOutgoing!=null)feature.pendingOutgoing.ended(true);});
      engine.close();rt.worker.shutdownNow();rt.io.shutdownNow();rt.io.awaitTermination(2,TimeUnit.SECONDS);
    }
  }
  static Map<String,Object> data(long revision,String stage,String id,String direction,String peer){
    Map<String,Object> value=EngineSession.map("epoch","fixture-epoch","revision",revision,"stage",stage,"confirmedElapsedMs",0,"dtmfSupported",false);
    if(id!=null)value.put("callId",id);if(direction!=null)value.put("direction",direction);if(peer!=null)value.put("peer",peer);return value;
  }
  static Map<String,Object> terminal(String epoch,long revision,String id,boolean incoming){
    Map<String,Object> value=EngineSession.map("epoch",epoch,"revision",revision,"callId",id,"reason","remote_bye");
    if(incoming)value.put("state","ENDED");return value;
  }
  static void caller()throws Exception{
    for(String peer:new String[]{"+441234567890","01234567890",null,"anonymous","sip:hidden@anonymous.invalid"})try(Harness h=new Harness()){
      NewCallSession s=h.ring(peer);boolean visible=peer!=null&&peer.matches("\\+?[0-9]{1,20}");
      check(s!=null,"incoming session created");
      check(s.profile.getCallExtraInt(ImsCallProfile.EXTRA_OIR)==(visible?2:3),"public caller presentation or unknown is explicit");
      check((visible?peer:"").equals(s.profile.getCallExtra(ImsCallProfile.EXTRA_OI)),"only native public peer is presented");
    }
  }
  static void terminal()throws Exception{
    for(boolean incomingEvent:new boolean[]{true,false})try(Harness h=new Harness()){
      NewCallSession s=h.ring("+441234567890");ImsCallSessionListener listener=h.feature.lastIncomingListener;
      h.snapshot(3,"IN_CALL","incoming","INCOMING","+441234567890");await(()->h.starts.get()==1,"answered audio active");
      h.unavailable.set(true);
      h.event(incomingEvent?"incomingCall":"callEnded",terminal("fixture-epoch",4,"incoming",incomingEvent));
      check(listener.terminated.get()==1,"terminal event disconnects framework even when snapshot fails");
      check(s.getState()==State.TERMINATED&&h.feature.calls.isEmpty(),"old session removed");
      check(!h.binding.callActive,"terminal event clears only its completed call ownership");
      await(()->h.stops.get()==1,"engine audio attachment released");
      h.event(incomingEvent?"incomingCall":"callEnded",terminal("fixture-epoch",4,"incoming",incomingEvent));
      check(listener.terminated.get()==1,"duplicate terminal is idempotent");
      NewCallSession next=(NewCallSession)h.feature.createCallSession(new ImsCallProfile(1,2));next.start("000000000",null);
      await(()->h.dials.get()==1,"subsequent simulated outgoing request is admitted");
    }
    try(Harness h=new Harness()){
      NewCallSession s=h.ring("01234567890");h.snapshot(5,"IN_CALL","incoming","INCOMING","01234567890");
      h.unavailable.set(true);
      h.event("incomingCall",terminal("other-epoch",9,"incoming",true));
      h.event("incomingCall",terminal("fixture-epoch",4,"incoming",true));
      h.event("callEnded",terminal("fixture-epoch",9,"other-call",false));
      check(!s.ended,"wrong epoch, stale revision and unrelated call cannot end current call");
    }
  }
  static void blockedAudio()throws Exception{
    try(Harness h=new Harness()){
      NewCallSession s=h.ring("01234567890");h.snapshot(3,"IN_CALL","incoming","INCOMING","01234567890");await(()->h.starts.get()==1,"media attached");
      h.audio.block=true;h.wire.set(data(4,"IDLE",null,null,null));h.engine.getCallSnapshot();
      Future<?> terminal=h.rt.worker.submit(()->h.feature.snapshot(h.engine.getState().callSnapshot));
      check(h.audio.closing.await(1,TimeUnit.SECONDS),"synthetic media close blocked");
      try{terminal.get(400,TimeUnit.MILLISECONDS);}catch(TimeoutException error){throw new AssertionError("shared worker blocked behind PCM close",error);}
      check(h.feature.lastIncomingListener.terminated.get()==1,"framework termination precedes slow media disposal");
      h.state(()->{});check(s.getState()==State.TERMINATED,"shared worker remains available for either SIM");
      await(()->h.stops.get()==1,"audioStop does not wait behind blocked local close");
      h.audio.release.countDown();
    }
  }
  static void reconciliation()throws Exception{
    try(Harness h=new Harness()){
      NewCallSession old=h.ring("01234567890");ImsCallSessionListener first=h.feature.lastIncomingListener;
      h.snapshot(6,"RINGING","next-incoming","INCOMING","+441234567890");
      check(old.ended&&first.terminated.get()==1,"new authoritative call retires lost old termination");
      check(h.feature.calls.size()==1&&h.feature.calls.containsKey("next-incoming"),"only current incoming remains");
      h.snapshot(7,"IDLE",null,null,null);
      check(h.feature.calls.isEmpty()&&!h.binding.callActive,"IDLE clears current session and activity");
    }
  }
  static void exclusiveRouting()throws Exception{
    try(Harness h=new Harness()){
      check(h.feature.shouldProcessCall(new String[]{"+441234567890"})==android.telephony.ims.feature.MmTelFeature.PROCESS_CALL_IMS,"ordinary call stays on IMS when registered");
      h.feature.registrationChanged(false);
      h.binding.registered=false;
      check(h.feature.shouldProcessCall(new String[]{"+441234567890"})==android.telephony.ims.feature.MmTelFeature.PROCESS_CALL_IMS,"ordinary call stays on IMS when unregistered (no CSFB)");
      h.rt.allowBillable=false;
      check(h.feature.shouldProcessCall(new String[]{"+441234567890"})==android.telephony.ims.feature.MmTelFeature.PROCESS_CALL_IMS,"ordinary call stays on IMS when billable disabled (no CSFB)");
      check(h.feature.shouldProcessCall(new String[]{"throw"})==android.telephony.ims.feature.MmTelFeature.PROCESS_CALL_IMS,"unknown emergency classification fails on IMS, never CSFB");
      check(h.feature.shouldProcessCall(new String[]{"911"})==android.telephony.ims.feature.MmTelFeature.PROCESS_CALL_CSFB,"verified emergency number still allows CSFB");

      // Unregistered ordinary call fails locally with zero engine dials.
      NewCallSession unreg=(NewCallSession)h.feature.createCallSession(new ImsCallProfile(1,2));
      ImsCallSessionListener l1=new ImsCallSessionListener();unreg.setListener(l1);
      h.rt.allowBillable=true;h.binding.registered=false;
      unreg.start("+441234567890",null);
      await(()->l1.failed.get()==1,"unregistered outgoing call reports failure");
      check(h.dials.get()==0,"unregistered call never dials engine");

      // Radio not off blocks outgoing call before dialing.
      h.binding.registered=true;h.rt.radioAvailable=false;
      NewCallSession radioOn=(NewCallSession)h.feature.createCallSession(new ImsCallProfile(1,2));
      ImsCallSessionListener l2=new ImsCallSessionListener();radioOn.setListener(l2);
      radioOn.start("+441234567890",null);
      await(()->l2.failed.get()==1,"outgoing call fails when cached radio state is not OFF");
      check(h.dials.get()==0,"radio-not-off call never dials engine");

      // Radio state readback failure on live pre-dial check blocks dial.
      h.rt.radioAvailable=true;h.rt.failRadioRead=true;
      NewCallSession verifyFail=(NewCallSession)h.feature.createCallSession(new ImsCallProfile(1,2));
      ImsCallSessionListener l3=new ImsCallSessionListener();verifyFail.setListener(l3);
      verifyFail.start("+441234567890",null);
      await(()->l3.terminated.get()==1,"post-initiating radio verification failure terminates outgoing call");
      check(l3.failed.get()==0,"post-initiating failure must not use pending-only StartFailed callback");
      check(h.rt.radioChecks==1&&h.dials.get()==0,"failed pre-dial radio check prevents dial");

      // Confirmed radio OFF + registered + billable permits dial.
      h.rt.failRadioRead=false;
      NewCallSession ok=(NewCallSession)h.feature.createCallSession(new ImsCallProfile(1,2));
      ImsCallSessionListener l4=new ImsCallSessionListener();ok.setListener(l4);
      ok.start("+441234567890",null);
      await(()->h.dials.get()==1,"confirmed radio OFF permits VoWiFi dial");
      check(h.rt.radioChecks==2,"live pre-dial radio verification ran before dial");
    }
  }
  static void remoteEndThenRedialFailure() throws Exception {
    try (Harness h = new Harness()) {
      NewCallSession first = (NewCallSession) h.feature.createCallSession(new ImsCallProfile(1,2));
      ImsCallSessionListener firstListener = new ImsCallSessionListener();
      first.setListener(firstListener);
      first.start("000000000", null);
      await(() -> h.dials.get() == 1, "first outgoing dial accepted");
      h.snapshot(2, "IN_CALL", "next-outgoing", "OUTGOING", null);
      await(() -> firstListener.initiated.get() == 1, "first call established");
      h.state(() -> {});
      check(h.feature.audioHandlerNotifications >= 2 && h.feature.lastAudioHandler == 0,
          "Android VoIP audio handler is reapplied after call callbacks, not only feature startup");
      h.event("callEnded", terminal("fixture-epoch", 3, "next-outgoing", false));
      await(() -> firstListener.terminated.get() == 1, "remote hangup terminates established call");
      h.snapshot(4, "IDLE", null, null, null);
      check(h.feature.pendingOutgoing == null && h.feature.calls.isEmpty(), "remote hangup releases session ownership");
      h.rt.failRadioRead = true;
      NewCallSession second = (NewCallSession) h.feature.createCallSession(new ImsCallProfile(1,2));
      ImsCallSessionListener listener = new ImsCallSessionListener();
      second.setListener(listener);
      second.start("000000000", null);
      await(() -> second.ended, "next call fails a live pre-dial check");
      await(() -> listener.terminated.get() == 1, "initiating then failure disconnects Telecom instead of leaving DIALING");
      check(listener.frameworkDisconnected, "Android 17-shaped listener confirms framework disconnected");
      check(listener.failed.get() == 0, "pending-only failure callback not used after initiating");
      check(h.feature.pendingOutgoing == null && h.feature.calls.isEmpty(), "failed redial does not retain local sessions");
      second.terminate(0);
      h.state(() -> {});
      check(listener.terminated.get() == 1, "user hangup of ended session does not duplicate termination");
      h.rt.failRadioRead = false;
      NewCallSession third = (NewCallSession) h.feature.createCallSession(new ImsCallProfile(1,2));
      third.setListener(new ImsCallSessionListener());
      third.start("000000000", null);
      await(() -> h.dials.get() == 2, "subsequent call remains possible after failed redial");
    }
    try (Harness h = new Harness()) {
      NewCallSession call = (NewCallSession) h.feature.createCallSession(new ImsCallProfile(1,2));
      ImsCallSessionListener listener = new ImsCallSessionListener();
      call.setListener(listener); call.start("000000000", null);
      await(() -> h.feature.calls.containsKey("next-outgoing"), "unanswered call has engine identity");
      h.snapshot(2, "DIALING", "next-outgoing", "OUTGOING", null);
      h.unavailable.set(true);
      h.event("callEnded", terminal("fixture-epoch", 3, "next-outgoing", false));
      await(() -> listener.terminated.get() == 1, "remote cancellation before answer uses termination after initiating");
      check(listener.failed.get() == 0, "unanswered remote end never strands Telecom pending state");
    }
  }
  static void capabilityUpdates() throws Exception {
    try (Harness h = new Harness()) {
      check(h.feature.audioHandlerNotifications == 0, "no audio handler notification while no call exists");
      int before = h.feature.capabilityNotifications;
      for (int i = 0; i < 100; i++) h.feature.registrationChanged(true);
      check(h.feature.capabilityNotifications == before, "steady polls do not repeat Binder capability updates");
      h.feature.registrationChanged(false);
      check(h.feature.capabilityNotifications == before + 1 && h.feature.lastCapabilities == 0,
          "registration loss immediately clears capabilities");
      h.feature.registrationChanged(true);
      check(h.feature.capabilityNotifications == before + 2 && h.feature.lastCapabilities == 9,
          "registration recovery restores capabilities");
      h.rt.allowBillable = false;
      h.feature.registrationChanged(true);
      check(h.feature.capabilityNotifications == before + 3 && h.feature.lastCapabilities == 0,
          "billing gate change is never deduplicated away");
      h.feature.onFeatureReady();
      check(h.feature.capabilityNotifications == before + 4,
          "new framework listener always receives initial capabilities");
    }
  }
  public static void main(String[] args)throws Exception{
    String select=args.length==0?"all":args[0];
    if(select.equals("all")||select.equals("capabilities"))capabilityUpdates();
    if(select.equals("all")||select.equals("remote-redial"))remoteEndThenRedialFailure();
    if(select.equals("all")||select.equals("caller"))caller();
    if(select.equals("all")||select.equals("terminal"))terminal();
    if(select.equals("all")||select.equals("blocked"))blockedAudio();
    if(select.equals("all")||select.equals("reconcile"))reconciliation();
    if(select.equals("all")||select.equals("exclusive"))exclusiveRouting();
    System.out.println("PASS "+checks+" feature lifecycle checks; Android/engine/media substitutes only.");
  }
}
