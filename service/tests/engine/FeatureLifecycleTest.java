package com.newvowifi.ims;

import android.telephony.ims.*;
import android.telephony.ims.stub.ImsCallSessionImplBase.State;
import com.newvowifi.ims.engine.*;
import com.newvowifi.ims.engine.EngineContractTest.MemoryTransport;
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
  public static void main(String[] args)throws Exception{
    String select=args.length==0?"all":args[0];
    if(select.equals("all")||select.equals("caller"))caller();
    if(select.equals("all")||select.equals("terminal"))terminal();
    if(select.equals("all")||select.equals("blocked"))blockedAudio();
    if(select.equals("all")||select.equals("reconcile"))reconciliation();
    System.out.println("PASS "+checks+" feature lifecycle checks; Android/engine/media substitutes only.");
  }
}
