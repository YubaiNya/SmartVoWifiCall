package com.smartvowificall.ims.telemetry;

import com.smartvowificall.ims.engine.JsonCodec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe ring buffer recording HTTP requests, Engine RPC frames, and async protocol events
 * with complete sanitized header/packet structures ({@code [Hidden]} for sensitive values).
 */
public final class NetworkLogBuffer {
  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final int capacity;
  private final Deque<Map<String, Object>> entries;

  public NetworkLogBuffer(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("INVALID_CAPACITY");
    this.capacity = capacity;
    this.entries = new ArrayDeque<>(capacity);
  }

  public void recordHttp(String channel, String method, String target, int statusCode,
      long durationMs, Map<String, Object> requestHeaders, Object requestBody,
      Map<String, Object> responseHeaders, Object responseBody, String error) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ts", ISO_UTC.format(Instant.now()));
    row.put("channel", safeStr(channel, 32));
    row.put("direction", "req_res");
    row.put("method", safeStr(method, 16));
    row.put("target", LogSanitizer.sanitizeUrl(target));
    row.put("status", statusCode);
    row.put("durationMs", Math.max(0L, durationMs));
    row.put("requestHeaders", requestHeaders == null ? new LinkedHashMap<>()
        : LogSanitizer.sanitizeStructure(requestHeaders, "requestHeaders"));
    row.put("requestBody", sanitizeBody(requestBody, "requestBody"));
    row.put("responseHeaders", responseHeaders == null ? new LinkedHashMap<>()
        : LogSanitizer.sanitizeStructure(responseHeaders, "responseHeaders"));
    row.put("responseBody", sanitizeBody(responseBody, "responseBody"));
    if (error != null && !error.isEmpty()) {
      row.put("error", LogSanitizer.sanitizeText(error));
    }
    push(row);
  }

  public void recordRpc(int slot, String method, long requestId, long durationMs,
      Map<String, Object> requestFrame, Map<String, Object> responseFrame, String error) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ts", ISO_UTC.format(Instant.now()));
    row.put("channel", "engine_rpc");
    row.put("direction", "req_res");
    row.put("slot", slot);
    row.put("method", safeStr(method, 64));
    row.put("requestId", requestId);
    row.put("durationMs", Math.max(0L, durationMs));
    row.put("requestPacket", LogSanitizer.sanitizeStructure(requestFrame));
    row.put("responsePacket", LogSanitizer.sanitizeStructure(responseFrame));
    if (error != null && !error.isEmpty()) {
      row.put("error", LogSanitizer.sanitizeText(error));
    }
    push(row);
  }

  public void recordEngineEvent(int slot, String eventName, Map<String, Object> frame) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("ts", ISO_UTC.format(Instant.now()));
    row.put("channel", "engine_event");
    row.put("direction", "inbound_event");
    row.put("slot", slot);
    row.put("event", safeStr(eventName, 64));
    row.put("packet", LogSanitizer.sanitizeStructure(frame));
    push(row);
  }

  public void recordRawEntry(Map<String, Object> entry) {
    if (entry == null) return;
    Object sanitized = LogSanitizer.sanitizeStructure(entry);
    if (sanitized instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) sanitized;
      push(map);
    }
  }

  private synchronized void push(Map<String, Object> row) {
    if (entries.size() >= capacity) entries.removeFirst();
    entries.addLast(row);
  }

  public synchronized List<Map<String, Object>> snapshot(int maxCount) {
    List<Map<String, Object>> all = new ArrayList<>(entries);
    if (maxCount <= 0 || all.size() <= maxCount) return all;
    return new ArrayList<>(all.subList(all.size() - maxCount, all.size()));
  }

  public synchronized int size() {
    return entries.size();
  }

  public synchronized int removeUploaded(List<Map<String, Object>> uploaded) {
    java.util.Set<Map<String, Object>> selected =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    selected.addAll(uploaded);
    int before = entries.size();
    entries.removeIf(selected::contains);
    return before - entries.size();
  }

  public static Object parseJsonOrText(String raw, int maxChars) {
    if (raw == null || raw.isEmpty()) return "";
    String trimmed = raw.trim();
    if (trimmed.length() <= maxChars && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
      try {
        return JsonCodec.decode(trimmed);
      } catch (Exception ignored) {
      }
    }
    if (trimmed.length() > maxChars) {
      trimmed = trimmed.substring(0, maxChars) + "...(truncated)";
    }
    return trimmed;
  }

  private static Object sanitizeBody(Object body, String fieldKey) {
    if (body == null) return null;
    if (body instanceof String) {
      Object parsed = parseJsonOrText((String) body, 4096);
      return LogSanitizer.sanitizeStructure(parsed, fieldKey);
    }
    return LogSanitizer.sanitizeStructure(body, fieldKey);
  }

  private static String safeStr(String s, int max) {
    if (s == null) return "";
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }
}
