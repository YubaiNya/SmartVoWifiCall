package com.smartvowificall.ims;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.telephony.ims.*;
import android.telephony.ims.stub.ImsCallSessionImplBase.State;
import com.smartvowificall.ims.engine.*;
import com.smartvowificall.ims.engine.EngineContractTest.MemoryTransport;
import java.io.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Actual NewCallSession/PcmAudio sources with in-memory Android and engine substitutes. */
public final class CallIntegrationTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void await(BooleanSupplier condition,String message)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        check(condition.getAsBoolean(),message);
    }
    interface Handler{boolean handle(MemoryTransport t,Map<String,Object> r)throws IOException;}
    static final class AudioTransport implements EngineRpcClient.Transport {
        final BlockingQueue<Integer> received = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final AtomicInteger closes = new AtomicInteger(), flushes = new AtomicInteger();
        final CountDownLatch closeEntered = new CountDownLatch(1);
        volatile CountDownLatch closeGate;
        volatile boolean closed;
        void receive(byte[] bytes) { for(byte value:bytes)received.add(value&255); }
        synchronized byte[] transmitted(){return sent.toByteArray();}
        public InputStream input(){return new InputStream(){
            public int read()throws IOException{
                if(closed&&received.isEmpty())return -1;
                try{return received.take();}catch(InterruptedException e){throw new IOException(e);}
            }
            public int read(byte[] b,int off,int len)throws IOException{
                if(len==0)return 0;int value=read();if(value<0)return -1;b[off]=(byte)value;int count=1;
                while(count<Math.min(len,3)){Integer next=received.poll();if(next==null)break;if(next<0){received.add(next);break;}b[off+count++]=(byte)(int)next;}
                return count;
            }
        };}
        public OutputStream output(){return new OutputStream(){
            public void write(int value)throws IOException{synchronized(AudioTransport.this){if(closed)throw new EOFException();sent.write(value);}}
            public void write(byte[] b,int off,int len)throws IOException{synchronized(AudioTransport.this){if(closed)throw new EOFException();sent.write(b,off,len);}}
            public void flush(){flushes.incrementAndGet();}
        };}
        public void close()throws IOException{
            closes.incrementAndGet();closeEntered.countDown();
            try{if(closeGate!=null&&!closeGate.await(2,TimeUnit.SECONDS))throw new IOException("synthetic close timeout");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            closed=true;received.add(-1);
        }
    }
    static final class Harness implements AutoCloseable{
        final RuntimeController rt=new RuntimeController();
        final EngineSession engine;
        final NewMmTelFeature feature;
        final AtomicInteger hangups=new AtomicInteger(),stops=new AtomicInteger(),starts=new AtomicInteger(),commands=new AtomicInteger();
        final AtomicBoolean failAudioOpen=new AtomicBoolean();
        final AtomicInteger audioOpens=new AtomicInteger();
        volatile AudioTransport audio;
        volatile String audioPath;
        volatile int audioTimeout;
        Harness(Handler handler)throws Exception{
            rt.context.startService(new Intent(rt.context,PolicyService.class));
            engine=new EngineSession(new EngineSession.Identity("4444444444444444444",0,19),(p,timeout)->{
                if(p.endsWith(".audio.sock")){
                    audioPath=p;audioTimeout=timeout;audioOpens.incrementAndGet();
                    if(failAudioOpen.getAndSet(false))throw new IOException("synthetic audio transport failure");
                    audio=new AudioTransport();return audio;
                }
                return new MemoryTransport((t,r)->{
                commands.incrementAndGet();String method=(String)r.get("method");
                if(method.equals("audioStart"))starts.incrementAndGet();
                if(method.equals("audioStop"))stops.incrementAndGet();
                if(method.equals("hangup"))hangups.incrementAndGet();
                if(handler!=null&&handler.handle(t,r))return;
                t.reply(r,method.equals("hangup")||method.equals("reject")||method.equals("cancelCall")
                        ?EngineSession.map("state","IDLE","accepted",true):Collections.emptyMap());
            });},new EngineSession.Listener(){public void onEvent(EngineSession.Identity i,EngineState.Event e){}public void onDisconnected(EngineSession.Identity i,Throwable e){}},true);
            engine.connect(1000);feature=new NewMmTelFeature(rt,new RuntimeController.Binding(engine));
        }
        NewCallSession call(){return new NewCallSession(feature,new ImsCallProfile(ImsCallProfile.SERVICE_TYPE_NORMAL,ImsCallProfile.CALL_TYPE_VOICE));}
        void state(Runnable action)throws Exception{rt.worker.submit(action).get(1,TimeUnit.SECONDS);}
        public void close()throws Exception{
            state(()->{for(NewCallSession s:feature.calls.values())s.ended(true);if(feature.pendingOutgoing!=null)feature.pendingOutgoing.ended(true);});
            engine.close();rt.worker.shutdownNow();rt.io.shutdownNow();
        }
    }
    static void pendingCancellation()throws Exception{
        CountDownLatch dial=new CountDownLatch(1),cancel=new CountDownLatch(1);
        try(Harness h=new Harness((t,r)->{
            if(r.get("method").equals("dial")){
                dial.countDown();try{if(!cancel.await(2,TimeUnit.SECONDS))throw new IOException("cancel blocked");}catch(InterruptedException e){throw new IOException(e);}
                t.frame(EngineSession.map("id",r.get("id"),"error",EngineSession.map("code",2005,"message","synthetic canceled")));return true;
            }
            if(r.get("method").equals("cancelCall"))cancel.countDown();return false;
        })){
            NewCallSession s=h.call();ImsCallSessionListener l=new ImsCallSessionListener();s.setListener(l);s.start("123",s.profile);
            check(dial.await(1,TimeUnit.SECONDS),"Dial pending on I/O thread");
            s.terminate(0);h.state(()->{});
            check(s.getState()==State.TERMINATING,"Cancel before callId remains pending");
            h.state(()->s.bindCallId("fake-call"));
            check(cancel.await(1,TimeUnit.SECONDS),"Deferred cancellation dispatched separately");
            await(()->l.terminated.get()==1,"Canceled initiating call reports termination once");
            check(l.failed.get()==0&&l.frameworkDisconnected,"Initiating cancellation disconnects framework");
            check(l.initiated.get()==0&&s.getState()==State.TERMINATED,"Canceled call never reports established");
            check(l.callbackThreads.stream().allMatch(x->x.equals("test-state")),"Callbacks serialized on state worker");
            check(h.feature.pendingOutgoing==null,"Pending outgoing ownership released");
        }
    }
    static void listenerAndStates()throws Exception{
        try(Harness h=new Harness(null)){
            NewCallSession s=h.call();h.state(()->{s.bindCallId("incoming-fake");s.stage("IN_CALL");});
            await(()->h.starts.get()==1,"Audio starts once after establishment");
            ImsCallSessionListener l=new ImsCallSessionListener();s.setListener(l);h.state(()->{});
            check(l.initiated.get()==1,"Late listener receives current establishment");
            h.state(()->{s.stage("IN_CALL");s.stage("ENDING");});
            check(s.getState()==State.TERMINATING,"ENDING maps to Android state 7");
            h.state(()->{s.ended(false);s.ended(false);});
            await(()->h.stops.get()==1,"Terminal cleanup sends one audioStop");
            check(l.terminated.get()==1&&l.initiated.get()==1,"Terminal and establishment callbacks are idempotent");
            check(h.feature.calls.isEmpty(),"Terminated call removed");
        }
        try(Harness h=new Harness(null)){
            NewCallSession old=h.call();h.state(()->{old.bindCallId("old-call");h.feature.current=new RuntimeController.Binding(h.engine);});
            int before=h.commands.get();old.terminate(0);h.state(()->{});
            check(h.commands.get()==before,"Old session cannot control replacement binding");
        }
    }
    static void audioFailureAndRace()throws Exception{
        try(Harness h=new Harness(null)){
            NewCallSession s=h.call();ImsCallSessionListener l=new ImsCallSessionListener();s.setListener(l);
            h.failAudioOpen.set(true);
            h.state(()->{s.bindCallId("audio-failure");s.stage("IN_CALL");});
            await(()->l.terminated.get()==1,"Audio startup failure reaches framework");
            await(()->h.hangups.get()==1,"Audio failure attempts remote dialog cleanup");
            check(s.getState()==State.TERMINATED,"Audio failure cannot leave established zombie session");
        }
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try(Harness h=new Harness((t,r)->{
            if(!r.get("method").equals("audioStart"))return false;
            started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}
            t.reply(r,Collections.emptyMap());return true;
        })){
            int records=AudioRecord.releases.get(),tracks=AudioTrack.releases.get();AtomicInteger failures=new AtomicInteger();
            PcmAudio a=new PcmAudio(h.rt.context,h.engine,"race-fake",h.rt.io,e->failures.incrementAndGet());
            Future<?> future=h.rt.io.submit(()->{try{a.start();}catch(Exception e){throw new RuntimeException(e);}});
            check(started.await(1,TimeUnit.SECONDS),"Audio start request pending");
            a.close();a.close();check(h.stops.get()==0,"Stop cannot overtake pending audioStart");
            release.countDown();future.get(1,TimeUnit.SECONDS);
            await(()->h.stops.get()==1,"Deferred audioStop runs once");
            check(AudioRecord.releases.get()==records+1&&AudioTrack.releases.get()==tracks+1,"Audio resources released once");
            check(h.audio.closes.get()==1,"Audio transport released once while audioStart is pending");
            check(failures.get()==0,"Explicit audio close is not an audio failure");
        }
    }
    static void dtmfDoesNotTerminate()throws Exception{
        AtomicInteger dtmf=new AtomicInteger();
        try(Harness h=new Harness((t,r)->{
            if(!r.get("method").equals("sendDtmf"))return false;
            dtmf.incrementAndGet();t.frame(EngineSession.map("id",r.get("id"),"error",EngineSession.map("code",2004,"message","unsupported test DTMF")));return true;
        })){
            NewCallSession s=h.call();ImsCallSessionListener l=new ImsCallSessionListener();s.setListener(l);
            h.state(()->{s.bindCallId("tone-fake");s.stage("IN_CALL");});
            s.sendDtmf('1',null);await(()->dtmf.get()==1,"Synthetic DTMF attempted");Thread.sleep(80);h.state(()->{});
            check(s.getState()==State.ESTABLISHED&&l.terminated.get()==0,"DTMF failure preserves established call");
        }
    }
    static void cancelAnswerRace()throws Exception{
        CountDownLatch canceled=new CountDownLatch(1),releaseCancel=new CountDownLatch(1),
                hangup=new CountDownLatch(1),releaseHangup=new CountDownLatch(1);
        try(Harness h=new Harness((t,r)->{
            String method=(String)r.get("method");
            try{
                if(method.equals("cancelCall")){
                    canceled.countDown();releaseCancel.await(2,TimeUnit.SECONDS);
                    t.frame(EngineSession.map("id",r.get("id"),"error",EngineSession.map("code",2004,"message","already answered")));return true;
                }
                if(method.equals("hangup")){hangup.countDown();releaseHangup.await(2,TimeUnit.SECONDS);}
            }catch(InterruptedException e){throw new IOException(e);}
            return false;
        })){
            NewCallSession s=h.call();ImsCallSessionListener l=new ImsCallSessionListener();s.setListener(l);
            h.state(()->{s.dialStarted=true;s.bindCallId("answer-race");s.stage("DIALING");});
            s.terminate(0);check(canceled.await(1,TimeUnit.SECONDS),"Cancel request starts before remote answer");
            h.state(()->s.stage("IN_CALL"));
            check(hangup.await(1,TimeUnit.SECONDS),"Late remote answer upgrades cancel to hangup");
            releaseCancel.countDown();Thread.sleep(80);h.state(()->{});
            check(s.getState()==State.TERMINATING&&l.terminated.get()==0,"Obsolete cancel error cannot terminate newer hangup");
            releaseHangup.countDown();await(()->l.terminated.get()==1,"Current hangup completion ends session once");
        }
    }
    static void binaryAudioTransport()throws Exception{
        try(Harness h=new Harness(null)){
            int records=AudioRecord.releases.get(),tracks=AudioTrack.releases.get();
            AtomicInteger failures=new AtomicInteger();byte[] pcm=new byte[640];
            for(int i=0;i<pcm.length;i++)pcm[i]=(byte)i;
            AudioTrack.resetPlayed();AudioRecord.captures.add(pcm.clone());
            PcmAudio a=new PcmAudio(h.rt.context,h.engine,"binary-fake",h.rt.io,e->failures.incrementAndGet());
            a.start();
            check(h.audioPath.equals(h.engine.identity.audioSocketPath())&&h.audioTimeout==5000,
                    "Audio uses the same platform factory with exact ICCID path and deadline");
            await(()->h.audio.transmitted().length==644,"One captured PCM frame reaches raw transport");
            check(Arrays.equals(h.audio.transmitted(),PcmFrameCodec.encode(pcm)),"No hello, JSON, text conversion or frame change on audio transport");
            check(h.audio.flushes.get()==1,"Each frame flushes relay buffering");
            h.audio.receive(PcmFrameCodec.encode(pcm));
            await(()->AudioTrack.played().length==640,"Fragmented transport frame reaches playback");
            check(Arrays.equals(AudioTrack.played(),pcm),"PCM payload survives fragmented input and partial playback writes");
            a.close();a.close();await(()->h.stops.get()==1,"Binary transport cleanup sends audioStop once");
            h.engine.close();
            check(h.audio.closes.get()==1,"PcmAudio and EngineSession share idempotent transport ownership");
            check(AudioRecord.releases.get()==records+1&&AudioTrack.releases.get()==tracks+1,"Binary media resources released exactly once");
            check(failures.get()==0,"Normal binary transport shutdown does not report an audio failure");
        }
    }
    static void foregroundLifecycle()throws Exception{
        try(Harness h=new Harness(null)){
            check(Service.foregroundType==0,"Idle registration does not retain microphone foreground");
            AtomicInteger lost=new AtomicInteger();
            PolicyService.AudioLease first=PolicyService.acquireAudio(h.rt.context,e->lost.incrementAndGet());
            first.awaitReady();
            PolicyService.AudioLease second=PolicyService.acquireAudio(h.rt.context,e->lost.incrementAndGet());
            second.awaitReady();
            check(Service.foregroundType==ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,"Call lease promotes microphone foreground");
            int promotions=Service.foregroundStarts.get(),stops=Service.foregroundStops.get();
            Context.service.onStartCommand(new Intent(),0,2);
            check(Service.foregroundStarts.get()==promotions&&Service.foregroundStops.get()==stops,"Registration restart never drops active capture foreground");
            first.close();first.close();
            check(Service.foregroundType==ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,"One closed lease cannot revoke another call");
            second.close();
            check(Service.foregroundType==0&&lost.get()==0,"Last lease removes foreground without reporting service loss");
            Thread.sleep(40);
            check(Service.foregroundStarts.get()==promotions,"Idle audio lifecycle has no repeating promotion/polling");
        }
    }
    static void foregroundCancellationAndDenials()throws Exception{
        try(Harness h=new Harness(null)){
            AtomicInteger failures=new AtomicInteger();
            int recordStarts=AudioRecord.starts.get();
            Context.deferForegroundStart=true;
            PcmAudio a=new PcmAudio(h.rt.context,h.engine,"foreground-pending",h.rt.io,e->failures.incrementAndGet());
            Future<?> starting=h.rt.io.submit(()->{try{a.start();}catch(Exception ignored){}});
            try{
                await(()->!Context.pending.isEmpty(),"Foreground promotion is pending");
                check(h.audioOpens.get()==0&&AudioRecord.starts.get()==recordStarts,"Capture and engine transport wait for foreground readiness");
                a.close();starting.get(1,TimeUnit.SECONDS);
                Context.dispatchPending();
                check(Service.foregroundType==0&&h.audioOpens.get()==0,"Late service start cannot resurrect cancelled capture");
                check(failures.get()==0,"Closing pending foreground is not a call failure");
            }finally{Context.deferForegroundStart=false;a.close();Context.dispatchPending();}
            int requests=Context.foregroundRequests.get();
            Context.recordAllowed=false;
            PcmAudio denied=new PcmAudio(h.rt.context,h.engine,"permission-denied",h.rt.io,e->failures.incrementAndGet());
            try{denied.start();throw new AssertionError("missing RECORD_AUDIO accepted");}
            catch(IOException expected){check(expected.getMessage().equals("AUDIO_RECORD_PERMISSION_DENIED"),"Missing microphone grant has explicit failure");}
            finally{Context.recordAllowed=true;denied.close();}
            check(Context.foregroundRequests.get()==requests&&failures.get()==1,"Denied recording never starts microphone FGS");
            Context.denyForegroundStart=true;
            PcmAudio background=new PcmAudio(h.rt.context,h.engine,"background-denied",h.rt.io,e->failures.incrementAndGet());
            try{background.start();throw new AssertionError("background denial ignored");}
            catch(IOException expected){check(expected.getMessage().equals("AUDIO_FOREGROUND_UNAVAILABLE"),"FGS start denial reaches media failure");}
            finally{Context.denyForegroundStart=false;background.close();}
            Service.denyMicrophone=true;
            PcmAudio microphone=new PcmAudio(h.rt.context,h.engine,"promotion-denied",h.rt.io,e->failures.incrementAndGet());
            try{microphone.start();throw new AssertionError("microphone type denial ignored");}
            catch(IOException expected){check(expected.getMessage().equals("AUDIO_FOREGROUND_UNAVAILABLE"),"FGS microphone permission denial reaches media failure");}
            finally{Service.denyMicrophone=false;microphone.close();}
            check(Service.foregroundType==0&&h.starts.get()==0&&h.audioOpens.get()==0&&failures.get()==3,"Every permission failure releases leases before opening media");
        }
    }
    static void captureDiagnosticsAndMute()throws Exception{
        PcmAudio.CaptureLevels levels=new PcmAudio.CaptureLevels();
        byte[] fullScale=new byte[640];
        for(int i=1;i<fullScale.length;i+=2)fullScale[i]=(byte)0x80;
        levels.capture(fullScale,false);
        check(levels.summary().contains("peak=32768 rms=32768"),"Signed PCM full scale levels do not overflow");
        levels.capture(new byte[640],false);
        check(levels.summary().contains("zero_frames=1")&&levels.summary().contains("policy_silenced=unknown"),"Zero PCM alone never implies Android policy silence");
        try(Harness h=new Harness(null)){
            AtomicInteger failures=new AtomicInteger();
            AudioRecord.silenced=true;
            PcmAudio a=new PcmAudio(h.rt.context,h.engine,"muted-synthetic",h.rt.io,e->failures.incrementAndGet());
            a.muted=true;AudioRecord.captures.add(fullScale);
            try{
                a.start();
                await(()->h.audio.transmitted().length==644,"Muted capture still sends paced PCM frames");
                check(Arrays.equals(h.audio.transmitted(),PcmFrameCodec.encode(new byte[640])),"Application mute zeros transmitted synthetic PCM");
                check(a.levels.summary().contains("muted_frames=1")&&a.levels.summary().contains("zero_frames=0"),"Capture levels distinguish mute from zero microphone input");
                check(a.levels.summary().contains("policy_silenced=true"),"Record configuration exposes confirmed policy silence");
                AudioRecord.latest.emitConfiguration(false);
                check(a.levels.summary().contains("policy_silenced=false"),"Policy unsilence updates event-driven diagnostics");
                check(AudioRecord.source==7&&android.media.AudioManager.mode==2,"Keep VOICE_COMMUNICATION source and Telecom-owned mode unchanged");
                check(failures.get()==0,"Transient policy silence is observable, not a speculative forced hangup");
            }finally{AudioRecord.silenced=false;a.close();}
            check(AudioRecord.callbacks.get()==0&&Service.foregroundType==0,"Close unregisters recording observer and releases foreground");
        }
    }
    static void captureFailureAndServiceLoss()throws Exception{
        try(Harness h=new Harness(null)){
            int records=AudioRecord.releases.get(),tracks=AudioTrack.releases.get();
            AtomicInteger failures=new AtomicInteger();
            AudioRecord.failStart=true;
            PcmAudio a=new PcmAudio(h.rt.context,h.engine,"capture-fails",h.rt.io,e->failures.incrementAndGet());
            try{a.start();throw new AssertionError("capture startup failure ignored");}
            catch(IllegalStateException expected){}
            finally{AudioRecord.failStart=false;a.close();}
            await(()->h.stops.get()==1,"Capture start failure stops engine attachment");
            check(AudioRecord.releases.get()==records+1&&AudioTrack.releases.get()==tracks+1&&AudioRecord.callbacks.get()==0,"Failed capture releases both devices and observer exactly once");
            check(Service.foregroundType==0&&failures.get()==1,"Failed capture releases foreground and reports once");
            PcmAudio live=new PcmAudio(h.rt.context,h.engine,"service-lost",h.rt.io,e->failures.incrementAndGet());
            live.start();
            Context.service.onDestroy();
            await(()->failures.get()==2,"Service loss cannot leave silently backgrounded microphone");
            await(()->h.stops.get()==2,"Service loss detaches engine exactly once");
            live.close();
            check(AudioRecord.callbacks.get()==0&&Service.foregroundType==0,"Destroyed service closes all capture resources");
        }
    }
    static void failureBeforeBlockingTeardown()throws Exception{
        try(Harness h=new Harness(null)){
            AtomicInteger failures=new AtomicInteger();
            PcmAudio a=new PcmAudio(h.rt.context,h.engine,"blocked-close",h.rt.io,e->failures.incrementAndGet());
            a.start();
            CountDownLatch release=new CountDownLatch(1);
            h.audio.closeGate=release;
            try{
                h.audio.receive(new byte[]{0,0,0,0});
                check(h.audio.closeEntered.await(1,TimeUnit.SECONDS),"Malformed frame enters blocking relay teardown");
                check(failures.get()==1,"Media failure is notified before potentially blocking relay close");
                await(()->h.stops.get()==1,"Relay close cannot block engine audioStop");
                check(Service.foregroundType==0&&AudioRecord.callbacks.get()==0,"Microphone and foreground release before blocked relay close");
            }finally{release.countDown();a.close();}
            await(()->h.audio.closed,"Relay teardown completes after unblocking");
            check(failures.get()==1,"Blocking cleanup never duplicates media failure callback");
        }
    }
    public static void main(String[] args)throws Exception{
        Context.enforceMicrophoneForeground=true;
        pendingCancellation();listenerAndStates();audioFailureAndRace();dtmfDoesNotTerminate();cancelAnswerRace();binaryAudioTransport();
        foregroundLifecycle();foregroundCancellationAndDenials();captureDiagnosticsAndMute();captureFailureAndServiceLoss();failureBeforeBlockingTeardown();
        System.out.println("PASS "+checks+" call/audio integration checks; Android/socket/media substitutes only.");
    }
}
