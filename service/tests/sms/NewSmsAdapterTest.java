package com.newvowifi.ims;

import java.util.*;
import android.telephony.ims.stub.ImsSmsImplBase;

public final class NewSmsAdapterTest {
    private static int checks;
    static final class Delivery { int token;byte[] pdu;Delivery(int t,byte[] p){token=t;pdu=p;} }
    static final class Env {
        final RuntimeController rt=new RuntimeController();
        final NewSms sms=new NewSms(new NewMmTelFeature(rt));
        final List<String> results=new ArrayList<>();
        final List<Delivery> deliveries=new ArrayList<>();
        int throwSent=-1;boolean throwReceived;
        Env(boolean ready){
            sms.sent=(token,ref,success)->{results.add(token+":"+success);if(token==throwSent)throw new RuntimeException("listener dead");};
            sms.receivedListener=(token,format,pdu)->{deliveries.add(new Delivery(token,pdu));if(throwReceived)throw new RuntimeException("listener dead");};
            if(ready){sms.onReady();rt.worker.runReady();}
        }
        void submit(int token,byte[] pdu){sms.sendSms(token,token,"3gpp",null,false,pdu);rt.worker.runReady();}
        void rpc(){rt.io.runReady();rt.worker.runReady();}
        void receive(String id,String text){sms.received(event(id,text));rt.worker.runReady();}
        void ack(int index,int result){sms.acknowledgeSms(deliveries.get(index).token,0,result);rt.worker.runReady();}
    }
    public static void main(String[] args) {
        assemblyAndCallbacks(); unsupportedAndGate(); incomingAcknowledgements(); incomingHistory();
        System.out.println("PASS NewSmsAdapterTest: "+checks+" assertions");
    }
    private static void assemblyAndCallbacks(){
        Env e=new Env(true);
        e.submit(1,part(2,1,'A'));
        eq(0,e.rt.binding.engine.calls);eq(0,e.results.size());eq(0,e.rt.io.queue.size());
        e.submit(2,part(2,2,'B'));
        eq(1,e.rt.io.queue.size());eq(0,e.rt.binding.engine.calls);
        // The state worker remains runnable while IO is pending.
        boolean[] responsive={false};e.rt.worker.execute(()->responsive[0]=true);e.rt.worker.runReady();check(responsive[0]);
        e.throwSent=1;e.rpc();
        eq(1,e.rt.binding.engine.calls);eq("AB",e.rt.binding.engine.text);
        eq(Arrays.asList("1:true","2:true"),e.results);
        e.rt.worker.expireTimers();eq(2,e.results.size());
        // Reused token is not a second callback or a second engine command.
        e.submit(1,part(2,1,'A'));e.rpc();eq(1,e.rt.binding.engine.calls);eq(2,e.results.size());
        // Late duplicate fragment cannot start an incomplete second send.
        e.submit(3,part(2,1,'A'));eq("3:false",e.results.get(2));

        Env duplicate=new Env(true);
        duplicate.submit(10,part(2,1,'A'));
        duplicate.submit(10,part(2,1,'A'));eq(0,duplicate.results.size());
        duplicate.submit(11,part(2,1,'X'));
        eq(Arrays.asList("10:false","11:false"),duplicate.results);
        duplicate.submit(12,part(2,2,'B'));duplicate.rpc();eq(0,duplicate.rt.binding.engine.calls);

        Env timeout=new Env(true);timeout.throwSent=20;
        timeout.submit(20,part(3,1,'A'));timeout.submit(21,part(3,2,'B'));
        timeout.rt.worker.expireTimers();eq(Arrays.asList("20:false","21:false"),timeout.results);
        timeout.rt.worker.expireTimers();eq(2,timeout.results.size());
    }
    private static void unsupportedAndGate(){
        Env e=new Env(true);e.submit(1,part(2,1,'A'));
        byte[] reports=part(2,2,'B');reports[0]|=0x20;e.submit(2,reports);
        eq(Arrays.asList("1:false","2:false"),e.results);eq(0,e.rt.io.queue.size());
        e.rt.worker.expireTimers();eq(2,e.results.size());

        Env binary=new Env(true);byte[] pdu=single('A');pdu[7]=4;binary.submit(5,pdu);
        eq(Collections.singletonList("5:false"),binary.results);eq(0,binary.rt.io.queue.size());

        Env before=new Env(true);before.rt.allowed=false;
        before.sms.sendSms(6,6,"3gpp",null,false,single('A'));before.rt.allowed=true;before.rt.worker.runReady();before.rpc();
        eq(0,before.rt.binding.engine.calls);eq(Collections.singletonList("6:false"),before.results);
        Env after=new Env(true);after.submit(7,single('A'));after.rt.allowed=false;after.rpc();
        eq(0,after.rt.binding.engine.calls);eq(Collections.singletonList("7:false"),after.results);
        Env changed=new Env(true);RuntimeController.Binding old=changed.rt.binding;
        changed.submit(8,single('A'));changed.rt.binding=new RuntimeController.Binding("SIM-B");changed.rpc();
        eq(0,old.engine.calls);eq(0,changed.rt.binding.engine.calls);eq(Collections.singletonList("8:false"),changed.results);
        Env uncertain=new Env(true);uncertain.rt.binding.engine.failure=new RuntimeException("timeout");
        uncertain.submit(9,single('A'));uncertain.rpc();uncertain.rpc();eq(1,uncertain.rt.binding.engine.calls);eq(Collections.singletonList("9:false"),uncertain.results);
    }
    private static void incomingAcknowledgements(){
        Env queued=new Env(false);queued.receive("id1","A");eq(0,queued.deliveries.size());
        queued.sms.onReady();queued.rt.worker.runReady();eq(1,queued.deliveries.size());
        queued.receive("id1","A");eq(1,queued.deliveries.size()); // In flight.
        queued.ack(0,ImsSmsImplBase.DELIVER_STATUS_ERROR_GENERIC);
        queued.receive("id1","A");eq(2,queued.deliveries.size()); // Negative ACK is not delivered.
        check(queued.deliveries.get(0).token!=queued.deliveries.get(1).token);
        queued.ack(1,ImsSmsImplBase.DELIVER_STATUS_OK);
        queued.receive("id1","A");eq(2,queued.deliveries.size());
        queued.ack(0,ImsSmsImplBase.DELIVER_STATUS_OK);queued.receive("id1","A");eq(2,queued.deliveries.size());

        Env multipart=new Env(true);String body=repeat("A",161);
        multipart.receive("multi",body);eq(2,multipart.deliveries.size());
        int ref=concatRef(multipart.deliveries.get(1).pdu);
        multipart.ack(0,ImsSmsImplBase.DELIVER_STATUS_OK);
        multipart.ack(1,ImsSmsImplBase.DELIVER_STATUS_ERROR_GENERIC);
        multipart.receive("multi",body);eq(3,multipart.deliveries.size());
        eq(ref,concatRef(multipart.deliveries.get(2).pdu));
        multipart.sms.acknowledgeSms(multipart.deliveries.get(2).token,0,ImsSmsImplBase.DELIVER_STATUS_OK,new byte[0]);multipart.rt.worker.runReady();
        multipart.receive("multi",body);eq(3,multipart.deliveries.size());

        Env failed=new Env(true);failed.throwReceived=true;failed.receive("dead-listener","A");
        failed.throwReceived=false;failed.receive("dead-listener","A");eq(2,failed.deliveries.size());
        failed.ack(1,ImsSmsImplBase.DELIVER_STATUS_OK);failed.receive("dead-listener","A");eq(2,failed.deliveries.size());

        Env collision=new Env(true);eq("Aa".hashCode(),"BB".hashCode());
        collision.receive("Aa",body);collision.receive("BB",body);eq(4,collision.deliveries.size());
        check(concatRef(collision.deliveries.get(0).pdu)!=concatRef(collision.deliveries.get(2).pdu));
    }
    private static void incomingHistory(){
        Env e=new Env(true);
        for(int i=0;i<513;i++){e.receive("id"+i,"A");e.ack(i,ImsSmsImplBase.DELIVER_STATUS_OK);}
        eq(513,e.deliveries.size());e.receive("id512","A");eq(513,e.deliveries.size());
        e.rt.binding=new RuntimeController.Binding("SIM-B");e.receive("id512","A");eq(514,e.deliveries.size());
        Env wrongSim=new Env(false);wrongSim.receive("old","A");wrongSim.rt.binding=new RuntimeController.Binding("SIM-B");
        wrongSim.sms.onReady();wrongSim.rt.worker.runReady();eq(0,wrongSim.deliveries.size());
    }
    private static Map<String,Object> event(String id,String text){Map<String,Object> m=new HashMap<>();m.put("smsId",id);m.put("number","123");m.put("text",text);m.put("timestamp",1767323045L);return m;}
    private static byte[] single(char ch){return new byte[]{1,0,4,(byte)0x91,0x21,0x43,0,0,1,(byte)ch};}
    private static byte[] part(int count,int sequence,char ch){return new byte[]{0x41,0,4,(byte)0x91,0x21,0x43,0,0,9,6,8,4,0x12,0x34,(byte)count,(byte)sequence,(byte)ch};}
    private static int concatRef(byte[] pdu){int start=1+1+2+((pdu[2]&255)+1)/2+2+7+1;return ((pdu[start+3]&255)<<8)|(pdu[start+4]&255);}
    private static String repeat(String s,int n){StringBuilder b=new StringBuilder();while(n-->0)b.append(s);return b.toString();}
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("condition failed");}
    private static void eq(Object a,Object b){checks++;if(!a.equals(b))throw new AssertionError("expected "+a+" got "+b);}
}
