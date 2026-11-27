package com.smartvowificall.ims.telemetry;

import com.smartvowificall.ims.engine.JsonCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One upload's retained references and receipt gate. No clock-based deletion. */
public final class TelemetryCleanup {
  public final List<EventRingBuffer.Entry> events;
  public final List<Map<String, Object>> network;
  private Map<String, Object> sent;
  private List<String> parts;
  private int received;
  private boolean failed, cleaned;

  public TelemetryCleanup(EventRingBuffer events, NetworkLogBuffer network) {
    this.events = events.snapshot(120);
    this.network = network.snapshot(80);
  }

  public void prepare(String payload, List<String> parts) throws java.io.IOException {
    if (sent != null || parts.isEmpty()) throw new IllegalStateException("LOG_CLEANUP_BATCH_INVALID");
    this.sent = JsonCodec.decodeObject(payload);
    this.parts = new ArrayList<>(parts);
  }

  public boolean acknowledge(String request, TelemetryClient.UploadResult response) {
    if (failed || parts == null || received >= parts.size() || !parts.get(received).equals(request)
        || !response.ok || !TelemetryBatch.receiptMatches(request, response.responseBody)) {
      failed = true;
      return false;
    }
    received++;
    return true;
  }

  public boolean complete() {
    return !failed && parts != null && received == parts.size();
  }

  public int clearMemory(EventRingBuffer eventBuffer, NetworkLogBuffer networkBuffer) {
    if (!complete() || cleaned) return 0;
    cleaned = true;
    Map<String, Integer> eventCounts = counts(sent.get("events"));
    List<EventRingBuffer.Entry> selectedEvents = new ArrayList<>();
    for (int i = events.size() - 1; i >= 0; i--) {
      EventRingBuffer.Entry entry = events.get(i);
      if (take(eventCounts, entry.toSanitizedMap())) selectedEvents.add(entry);
    }
    Map<String, Integer> networkCounts = counts(sent.get("networkLogs"));
    List<Map<String, Object>> selectedNetwork = new ArrayList<>();
    for (int i = network.size() - 1; i >= 0; i--) {
      Map<String, Object> entry = network.get(i);
      if (take(networkCounts, LogSanitizer.sanitizeStructure(entry))) selectedNetwork.add(entry);
    }
    return eventBuffer.removeUploaded(selectedEvents) + networkBuffer.removeUploaded(selectedNetwork);
  }

  public List<String> engineRecords() {
    if (!complete()) return Collections.emptyList();
    Set<String> keys = new LinkedHashSet<>();
    Object auth = sent.get("authPackets");
    if (auth instanceof List) for (Object row : (List<?>) auth) addKey(keys, "auth", row);
    Object raw = sent.get("rawLogs");
    if (raw instanceof String) for (String line : ((String) raw).split("\n")) {
      try { addKey(keys, "diagnostic", JsonCodec.decodeObject(line)); }
      catch (Exception ignored) { /* Headers, partial lines and system logs are not journals. */ }
    }
    return new ArrayList<>(keys);
  }

  public List<String> proxyRecords() {
    if (!complete()) return Collections.emptyList();
    Set<String> ids = new LinkedHashSet<>();
    Object network = sent.get("networkLogs");
    if (network instanceof List) for (Object value : (List<?>) network) {
      if (!(value instanceof Map)) continue;
      Object id = ((Map<?, ?>) value).get("traceId");
      if (id instanceof String && ((String) id).matches("[a-f0-9-]{36}")) ids.add((String) id);
    }
    return new ArrayList<>(ids);
  }

  private static void addKey(Set<String> keys, String kind, Object value) {
    if (!(value instanceof Map)) return;
    Map<?, ?> row = (Map<?, ?>) value;
    Object capture = row.get("captureId"), sequence = row.get("sequence");
    if (!(capture instanceof String) || !(sequence instanceof Number)) return;
    String key = kind + ":" + capture + ":" + sequence;
    if (key.matches("(auth|diagnostic):cap[0-9a-f]+:[1-9][0-9]*")) keys.add(key);
  }

  private static Map<String, Integer> counts(Object value) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    if (value instanceof List) for (Object row : (List<?>) value) {
      String key = JsonCodec.encode(row);
      counts.put(key, counts.getOrDefault(key, 0) + 1);
    }
    return counts;
  }

  private static boolean take(Map<String, Integer> counts, Object row) {
    String key = JsonCodec.encode(row);
    int remaining = counts.getOrDefault(key, 0);
    if (remaining == 0) return false;
    counts.put(key, remaining - 1);
    return true;
  }
}
