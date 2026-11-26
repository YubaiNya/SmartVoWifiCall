package com.smartvowificall.ims.telemetry;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe in-memory ring buffer for runtime milestones and errors. Zero background I/O. */
public final class EventRingBuffer {
  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  public static final class Entry {
    public final long epochMs;
    public final String timestamp;
    public final String level;
    public final String tag;
    public final String message;

    Entry(long epochMs, String level, String tag, String message) {
      this.epochMs = epochMs;
      this.timestamp = ISO_UTC.format(Instant.ofEpochMilli(epochMs));
      this.level = level;
      this.tag = tag;
      this.message = message;
    }

    public String formatLine(boolean sanitize) {
      String body = sanitize ? LogSanitizer.sanitizeText(message) : message;
      return timestamp + " [" + level + "] [" + tag + "] " + body;
    }

    public Map<String, Object> toSanitizedMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("ts", timestamp);
      m.put("level", level);
      m.put("tag", tag);
      m.put("msg", LogSanitizer.sanitizeText(message));
      return m;
    }
  }

  private final int capacity;
  private final Deque<Entry> entries;

  public EventRingBuffer(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("INVALID_CAPACITY");
    this.capacity = capacity;
    this.entries = new ArrayDeque<>(capacity);
  }

  public synchronized void record(String level, String tag, String message) {
    recordAt(System.currentTimeMillis(), level, tag, message);
  }

  public synchronized void recordAt(long epochMs, String level, String tag, String message) {
    String lvl = level == null || level.isEmpty() ? "I" : level;
    String tg = tag == null || tag.isEmpty() ? "app" : tag;
    String msg = message == null ? "" : message;
    if (msg.length() > 512) msg = msg.substring(0, 512);
    if (entries.size() >= capacity) entries.removeFirst();
    entries.addLast(new Entry(epochMs, lvl, tg, msg));
  }

  public synchronized List<Entry> snapshot(int maxCount) {
    List<Entry> all = new ArrayList<>(entries);
    if (maxCount <= 0 || all.size() <= maxCount) return all;
    return new ArrayList<>(all.subList(all.size() - maxCount, all.size()));
  }

  public synchronized String formatAll(boolean sanitize) {
    StringBuilder sb = new StringBuilder();
    for (Entry e : entries) sb.append(e.formatLine(sanitize)).append('\n');
    return sb.toString();
  }

  public synchronized int size() {
    return entries.size();
  }

  public synchronized void clear() {
    entries.clear();
  }
}
