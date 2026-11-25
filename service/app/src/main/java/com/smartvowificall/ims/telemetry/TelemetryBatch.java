package com.smartvowificall.ims.telemetry;

import com.smartvowificall.ims.engine.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lossless transport of retained, sanitized authentication records. No network or Android calls. */
public final class TelemetryBatch {
  public static final int PART_BYTES = 480 * 1024;
  public static final String RETENTION = "current and previous 256 KiB auth journal per engine; not unlimited history";
  private TelemetryBatch() {}

  public static String attach(String baseJson, String journal) {
    Map<String,Object> root=decode(baseJson);
    List<Object> records=new ArrayList<>();
    Map<String,String> seen=new LinkedHashMap<>();
    Map<String,Long> lastSequence=new LinkedHashMap<>();
    long writeFailures=0, gaps=0;
    for (String line:journal.split("\n")) {
      if (line.trim().isEmpty()) continue;
      Map<String,Object> row;
      try { row=JsonCodec.decodeObject(line.trim()); }
      catch (Exception e) { throw new IllegalArgumentException("AUTH_TRACE_INVALID_RECORD"); }
      if (!"smartvowificall.auth.v1".equals(row.get("schema")))
        throw new IllegalArgumentException("AUTH_TRACE_INVALID_SCHEMA");
      Object captureId=row.get("captureId"),sequence=row.get("sequence");
      if (!(captureId instanceof String) || ((String)captureId).isEmpty()
          || !(sequence instanceof Number) || ((Number)sequence).longValue()<1)
        throw new IllegalArgumentException("AUTH_TRACE_INVALID_SEQUENCE");
      String id=captureId+":"+sequence;
      String encoded=JsonCodec.encode(row), previous=seen.put(id,encoded);
      if (previous!=null) {
        if (!previous.equals(encoded)) throw new IllegalArgumentException("AUTH_TRACE_CONFLICTING_RECORD");
        continue;
      }
      long seq=((Number)sequence).longValue();Long last=lastSequence.put((String)captureId,seq);
      if(last!=null && seq>last+1) gaps+=seq-last-1;
      if(row.get("writeFailures") instanceof Number) writeFailures=Math.max(writeFailures,((Number)row.get("writeFailures")).longValue());
      records.add(LogSanitizer.sanitizeStructure(row));
    }
    root.put("authPackets",records);
    Map<String,Object> capture=new LinkedHashMap<>();
    capture.put("status",records.isEmpty()?"no_records":"collected");
    capture.put("recordCount",records.size());
    capture.put("retention",RETENTION);
    capture.put("writeFailures",writeFailures);capture.put("sequenceGaps",gaps);
    capture.put("incomplete",writeFailures>0||gaps>0);
    root.put("authCapture",capture);
    return JsonCodec.encode(root);
  }

  @SuppressWarnings("unchecked")
  public static List<String> split(String payload) {
    Map<String,Object> root=decode(payload);
    Object value=root.remove("authPackets");
    List<Object> records=value instanceof List<?>?(List<Object>)value:Collections.emptyList();
    String batchId=String.valueOf(root.get("logId"));
    List<Map<String,Object>> parts=new ArrayList<>();
    Map<String,Object> current=new LinkedHashMap<>(root);
    List<Object> group=new ArrayList<>();current.put("authPackets",group);parts.add(current);
    if (bytes(current)>PART_BYTES-2048) throw new IllegalArgumentException("TELEMETRY_METADATA_TOO_LARGE");
    for (Object record:records) {
      group.add(record);
      if (bytes(current)>PART_BYTES-2048) {
        group.remove(group.size()-1);
        current=new LinkedHashMap<>();
        for (String k:new String[]{"schema","logId","generatedAt","installId","trigger","app","device","service","authCapture"})
          current.put(k,root.get(k));
        group=new ArrayList<>();group.add(record);current.put("authPackets",group);parts.add(current);
        if (bytes(current)>PART_BYTES-2048) throw new IllegalArgumentException("AUTH_TRACE_RECORD_TOO_LARGE");
      }
    }
    List<String> out=new ArrayList<>();Set<String> ids=new HashSet<>();ids.add(batchId);
    for (int i=0;i<parts.size();i++) {
      Map<String,Object> part=parts.get(i);
      if (i>0) {
        String id;
        do {id=LogSanitizer.generateLogId(System.currentTimeMillis());} while(!ids.add(id));
        part.put("logId",id);
      }
      Map<String,Object> batch=new LinkedHashMap<>();
      batch.put("id",batchId);batch.put("index",i+1);batch.put("count",parts.size());
      batch.put("recordCount",((List<?>)part.get("authPackets")).size());batch.put("totalRecordCount",records.size());
      part.put("authBatch",batch);
      String encoded=JsonCodec.encode(part);
      if (encoded.getBytes(StandardCharsets.UTF_8).length>PART_BYTES) throw new IllegalArgumentException("TELEMETRY_PART_TOO_LARGE");
      out.add(encoded);
    }
    return out;
  }
  private static Map<String,Object> decode(String text) {
    try {return JsonCodec.decodeObject(text);}
    catch(Exception e) {throw new IllegalArgumentException("TELEMETRY_INVALID_JSON",e);}
  }
  private static int bytes(Object v) {return JsonCodec.encode(v).getBytes(StandardCharsets.UTF_8).length;}

  public static boolean receiptMatches(String request,String response) {
    try {
      Map<String,Object> sent=JsonCodec.decodeObject(request),got=JsonCodec.decodeObject(response);
      Map<?,?> batch=(Map<?,?>)sent.get("authBatch");
      return "smartvowificall.telemetry.v3".equals(got.get("schema"))
          && batch.get("id").equals(got.get("authBatchId"))
          && integer(batch.get("index"))==integer(got.get("authPartIndex"))
          && integer(batch.get("recordCount"))==integer(got.get("authRecordCount"));
    } catch (Exception e) {return false;}
  }
  private static long integer(Object x) {return ((Number)x).longValue();}
}
