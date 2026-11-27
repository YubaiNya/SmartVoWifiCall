import com.smartvowificall.ims.engine.JsonCodec;
import com.smartvowificall.ims.telemetry.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class TelemetryCleanupTest {
  static int checks;
  static void check(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }
  static Map<String,Object> map(Object... pairs) {
    Map<String,Object> m = new LinkedHashMap<>();
    for (int i=0;i<pairs.length;i+=2) m.put((String)pairs[i],pairs[i+1]);
    return m;
  }
  static String payload(TelemetryCleanup plan, int authCount, boolean trimNetwork) {
    List<Object> events = new ArrayList<>(), auth = new ArrayList<>();
    for (EventRingBuffer.Entry e : plan.events) events.add(e.toSanitizedMap());
    for (int i=1;i<=authCount;i++) auth.add(map("captureId","capabcd","sequence",i,"data",String.join("",Collections.nCopies(12000,"x"))));
    List<?> network = trimNetwork ? plan.network.subList(plan.network.size()-1,plan.network.size()) : plan.network;
    return JsonCodec.encode(map("schema",LogSanitizer.SCHEMA,"logId","SVW-20261128-AB12CD",
        "events",events,"networkLogs",LogSanitizer.sanitizeStructure(network),"authPackets",auth,
        "rawLogs","header\n{\"captureId\":\"capabcd\",\"sequence\":3}\npartial"));
  }
  static TelemetryClient.UploadResult receipt(String part, boolean ok, boolean mismatch) throws Exception {
    Map<?,?> b = (Map<?,?>)JsonCodec.decodeObject(part).get("authBatch");
    String body = JsonCodec.encode(map("schema",LogSanitizer.SCHEMA,"authBatchId",b.get("id"),
        "authPartIndex",b.get("index"),"authRecordCount",mismatch ? -1 : b.get("recordCount")));
    return new TelemetryClient.UploadResult(ok,ok?201:500,"", "", "",0,0,null,body);
  }
  static void testReceiptGates() throws Exception {
    for (String mode : Arrays.asList("success","failed","partial","mismatch","out_of_order")) {
      EventRingBuffer events = new EventRingBuffer(200);
      NetworkLogBuffer network = new NetworkLogBuffer(80);
      events.recordAt(1000,"I","test","same-second");
      network.recordRawEntry(map("ts","same","msg","old-unuploaded"));
      network.recordRawEntry(map("ts","same","msg","uploaded"));
      TelemetryCleanup plan = new TelemetryCleanup(events,network);
      String full = payload(plan,80,true);
      List<String> parts = TelemetryBatch.split(full);
      check(parts.size()>1,"fixture must split");
      plan.prepare(full,parts);
      // New records can have the exact same timestamp and content.
      events.recordAt(1000,"I","test","same-second");
      network.recordRawEntry(map("ts","same","msg","uploaded"));
      check(plan.clearMemory(events,network)==0,"cleaned before any receipt");
      if (mode.equals("out_of_order")) plan.acknowledge(parts.get(1),receipt(parts.get(1),true,false));
      else for (int i=0;i<parts.size();i++) {
        if (mode.equals("partial") && i==1) break;
        plan.acknowledge(parts.get(i),receipt(parts.get(i),!mode.equals("failed"),mode.equals("mismatch")));
        if (i<parts.size()-1) check(plan.clearMemory(events,network)==0,"cleaned partial batch");
      }
      int removed=plan.clearMemory(events,network);
      if (mode.equals("success")) {
        check(plan.complete() && removed==2,"complete batch not cleaned");
        check(events.size()==1 && network.size()==2,"new or trimmed records lost");
        check(plan.engineRecords().size()==81,"record keys missing");
        check(plan.clearMemory(events,network)==0,"cleanup not idempotent");
      } else {
        check(!plan.complete() && removed==0,"failed batch cleaned");
        check(events.size()==2 && network.size()==3,"failed batch lost logs");
        check(plan.engineRecords().isEmpty(),"failed batch exposed disk cleanup");
      }
    }
  }
  static void testConcurrentMemory() throws Exception {
    EventRingBuffer events = new EventRingBuffer(1000);
    NetworkLogBuffer network = new NetworkLogBuffer(1000);
    events.record("I","test","old"); network.recordRawEntry(map("msg","old"));
    TelemetryCleanup plan = new TelemetryCleanup(events,network);
    String full=payload(plan,0,false); List<String> parts=TelemetryBatch.split(full);
    plan.prepare(full,parts); for(String p:parts) plan.acknowledge(p,receipt(p,true,false));
    ExecutorService worker=Executors.newSingleThreadExecutor();
    try {
      Future<?> f=worker.submit(()->{for(int i=0;i<500;i++) {events.record("I","test","new");network.recordRawEntry(map("msg","new"));}});
      check(plan.clearMemory(events,network)==2,"old records not removed");
      f.get(); check(events.size()==500 && network.size()==500,"concurrent writes lost");
    } finally {worker.shutdownNow();}
  }
  static void testDiskJournal(Path dir) throws Exception {
    Path file=dir.resolve("http-trace.jsonl");
    Map<String,Object> old=map("msg","uploaded"), fresh=map("msg","new");
    TraceJournal.append(file,old); TraceJournal.append(file,fresh);
    List<String> ack=Collections.singletonList((String)old.get("traceId"));
    check(TraceJournal.acknowledge(file,ack)==1,"disk record not removed");
    check(Files.readAllLines(file).size()==1 && Files.readString(file).contains("new"),"new disk record lost");
    check(TraceJournal.acknowledge(file,ack)==0,"disk cleanup not idempotent");
    byte[] before=Files.readAllBytes(file);
    try {TraceJournal.acknowledge(file,Arrays.asList(fresh.get("traceId"),"bad"));throw new AssertionError("invalid ack accepted");}
    catch(java.io.IOException expected) {check(Arrays.equals(before,Files.readAllBytes(file)),"invalid ack changed file");}
    // The bounded writer must keep working after cleanup and rotation.
    for(int i=0;i<60;i++) TraceJournal.append(file,map("n",i));
    check(Files.readAllLines(file).size()==50,"bounded retention broken");
    check(TraceJournal.acknowledge(file,ack)==0,"stale receipt deleted new generation");
    Process child = new ProcessBuilder(System.getProperty("java.home")+"/bin/java", "-cp",System.getProperty("java.class.path"),
        "TelemetryCleanupTest","writer",file.toString()).inheritIO().start();
    for(int i=0;i<10;i++) TraceJournal.acknowledge(file,ack);
    check(child.waitFor()==0,"separate writer failed");
    check(Files.readString(file).contains("child-new"),"cross-process append lost");
  }
  public static void main(String[] args) throws Exception {
    if(args.length>0 && args[0].equals("writer")) {for(int i=0;i<10;i++) TraceJournal.append(Paths.get(args[1]),map("msg","child-new"));return;}
    testReceiptGates(); testConcurrentMemory();
    testDiskJournal(Paths.get(args[0]));
    System.out.println("Telemetry cleanup: "+checks+" assertions passed");
  }
}
