import com.smartvowificall.ims.engine.JsonCodec;
import com.smartvowificall.ims.telemetry.LogSanitizer;
import com.smartvowificall.ims.telemetry.TelemetryBatch;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public final class TelemetryBatchTest {
  static void check(boolean ok,String message) {if(!ok) throw new AssertionError(message);}
  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    Map<String,Object> base=new LinkedHashMap<>();
    base.put("schema",LogSanitizer.SCHEMA);base.put("logId","SVW-20261126-AB12CD");
    base.put("installId","00000000-0000-4000-8000-000000000000");
    for(String key:Arrays.asList("app","device","service"))base.put(key,new LinkedHashMap<String,Object>());
    StringBuilder journal=new StringBuilder();
    String large=new String(new char[12000]).replace('\0','a');
    for(int i=1;i<=260;i++) {
      Map<String,Object> record=new LinkedHashMap<>();
      record.put("schema","smartvowificall.auth.v1");record.put("captureId","testcapture");record.put("sequence",i);
      record.put("identity","0234100000000001@nai.epc.mnc010.mcc234.3gppnetwork.org");
      record.put("username","234100000000001@ims.mnc010.mcc234.3gppnetwork.org");
      record.put("profile","0@nai.epc/ims");
      record.put("MSK","synthetic-key-never-upload");record.put("algorithm","HMAC-SHA-256");
      record.put("publicPayload",large);journal.append(JsonCodec.encode(record)).append('\n');
    }
    String full=TelemetryBatch.attach(JsonCodec.encode(base),journal.toString());
    check(!full.contains("234100000000001")&&!full.contains("synthetic-key-never-upload"),"private values leaked");
    check(full.contains("0[Hidden]@nai.epc.mnc010.mcc234.3gppnetwork.org"),"NAI prefix/realm lost");
    check(full.contains("[Hidden]@ims.mnc010.mcc234.3gppnetwork.org"),"username realm lost");
    List<String> parts=TelemetryBatch.split(full);check(parts.size()>1,"did not split");
    Set<Integer> sequences=new HashSet<>();Set<String> ids=new HashSet<>();
    for(int i=0;i<parts.size();i++) {
      String part=parts.get(i);check(part.getBytes(StandardCharsets.UTF_8).length<=TelemetryBatch.PART_BYTES,"part oversized");
      Map<String,Object> parsed=JsonCodec.decodeObject(part);check(ids.add((String)parsed.get("logId")),"duplicate part id");
      Map<String,Object> batch=(Map<String,Object>)parsed.get("authBatch");
      check(((Number)batch.get("count")).intValue()==parts.size(),"wrong count");
      List<Map<String,Object>> rows=(List<Map<String,Object>>)parsed.get("authPackets");
      for(Map<String,Object> row:rows) {
        check(large.equals(row.get("publicPayload")),"long payload truncated");
        check("0@nai.epc/ims".equals(row.get("profile")),"profile tag damaged");
        check("HMAC-SHA-256".equals(row.get("algorithm")),"nonprivate field masked");
        check(sequences.add(((Number)row.get("sequence")).intValue()),"duplicate record");
      }
      Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("schema",LogSanitizer.SCHEMA);
      receipt.put("authBatchId",batch.get("id"));receipt.put("authPartIndex",batch.get("index"));receipt.put("authRecordCount",rows.size());
      check(TelemetryBatch.receiptMatches(part,JsonCodec.encode(receipt)),"valid receipt refused");
      receipt.put("authRecordCount",rows.size()+1);
      check(!TelemetryBatch.receiptMatches(part,JsonCodec.encode(receipt)),"wrong receipt accepted");
    }
    check(sequences.size()==260,"records silently omitted");
    try {TelemetryBatch.attach(JsonCodec.encode(base),"{incomplete");throw new AssertionError("malformed journal ignored");}
    catch(IllegalArgumentException expected) {check(expected.getMessage().equals("AUTH_TRACE_INVALID_RECORD"),"wrong malformed error");}
    String first=journal.toString().split("\n")[0];
    Map<String,Object> third=JsonCodec.decodeObject(first);third.put("sequence",3);
    Map<String,Object> gap=JsonCodec.decodeObject(TelemetryBatch.attach(JsonCodec.encode(base),first+"\n"+JsonCodec.encode(third)));
    check(Boolean.TRUE.equals(((Map<?,?>)gap.get("authCapture")).get("incomplete")),"sequence gap not reported");
    Map<String,Object> broken=JsonCodec.decodeObject(first);broken.remove("sequence");
    try {TelemetryBatch.attach(JsonCodec.encode(base),JsonCodec.encode(broken));throw new AssertionError("missing sequence silently accepted");}
    catch(IllegalArgumentException expected) {check(expected.getMessage().equals("AUTH_TRACE_INVALID_SEQUENCE"),"wrong sequence error");}
    if(args.length>0) Files.write(Paths.get(args[0]),JsonCodec.encode(parts).getBytes(StandardCharsets.UTF_8));
    System.out.println("PASS telemetry: 260 records, mixed-field redaction, full long values, multipart bounds and receipts ("+parts.size()+" parts)");
  }
}
