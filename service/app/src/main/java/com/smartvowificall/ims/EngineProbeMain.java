package com.smartvowificall.ims;

import android.os.Process;
import com.smartvowificall.ims.engine.*;
import java.util.*;
import org.json.JSONObject;

/** Root-only observation entry point. It cannot issue call, audio or SMS commands. */
public final class EngineProbeMain {
  public static void main(String[] args) {
    int exit = 1;
    String phase = "arguments";
    try {
      if (Process.myUid() != 0 || (args.length != 3 && args.length != 4)) throw new IllegalArgumentException();
      final boolean watch = args.length == 4 && args[3].equals("watch");
      EngineSession.Identity id = new EngineSession.Identity(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2]));
      EngineSession.Listener listener = new EngineSession.Listener() {
        public void onEvent(EngineSession.Identity i, EngineState.Event e) {
          if (!watch) return;
          try {
            JSONObject out = new JSONObject().put("event",e.name).put("fields",e.data.keySet().toString());
            for(String key:new String[]{"state","stage","reason","message","error","tunnel","tunnelDesc","ims"}) {
              Object v=e.data.get(key);
              if(v instanceof String && ((String)v).length()<400 && !((String)v).matches("(?is).*(imsi|iccid|imei|nonce|autn|authorization|password|private.key).*"))
                out.put(key, ((String)v).replaceAll("[0-9A-Fa-f]{16,}|[0-9]{10,}","<redacted>"));
            }
            System.out.println(out);
          } catch(Exception ignored) { }
        }
        public void onDisconnected(EngineSession.Identity i, Throwable t) {}
      };
      try (EngineSession engine = new EngineSession(id, new AndroidTransport(), listener, false)) {
        phase = "hello";
        engine.connect(5000);
        phase = "status";
        Map<String,Object> status = engine.getStatus();
        JSONObject result = new JSONObject().put("ok", true).put("slot", id.slotId)
            .put("subId", id.subId).put("statusFields", status.keySet());
        for (String field : new String[]{"ims", "ike", "state", "transport", "tunnel", "tunnelDesc", "call", "reconnects"}) {
          Object value = status.get(field);
          if (value instanceof String && ((String)value).length()<=500) result.put(field,
              ((String)value).replaceAll("[0-9A-Fa-f]{16,}|[0-9]{10,}","<redacted>"));
          else if (value instanceof Number || value instanceof Boolean) result.put(field,value);
          else if(value instanceof Map) result.put(field+"Fields", ((Map<?,?>)value).keySet().toString());
        }
        phase = "callSnapshot";
        try { result.put("callStage", engine.getCallSnapshot().get("stage")); }
        catch(EngineRpcClient.RpcException e) {result.put("callSnapshotError",e.code);}
        System.out.println(result); exit = 0;
        if(watch) for(int n=0;n<15;n++) {
          Thread.sleep(5000);
          Map<String,Object> current=engine.getStatus();
          System.out.println(new JSONObject().put("ims",current.get("ims")).put("tunnel",current.get("tunnel")));
        }
      }
    } catch (Throwable e) {
      try {
        JSONObject failure = new JSONObject().put("ok",false).put("phase",phase).put("code",e.getClass().getSimpleName());
        if (e instanceof EngineRpcClient.RpcException) {
          EngineRpcClient.RpcException rpc=(EngineRpcClient.RpcException)e;
          failure.put("rpcCode",rpc.code);
          String msg=rpc.remoteMessage();
          if(msg!=null&&msg.matches("[A-Za-z0-9_ .:=,()/+-]{1,160}"))failure.put("rpcMessage",msg);
        }
        System.out.println(failure);
      } catch (Exception ignored) {System.out.println("{\"ok\":false}");}
    }
    System.exit(exit);
  }
}
