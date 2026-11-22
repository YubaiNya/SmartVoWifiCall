package com.smartvowificall.ims.engine;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable wire state. Unknown fields are retained; no payload is printed by toString(). */
public final class EngineState {
  public final Map<String, Object> status;
  public final Map<String, Object> callSnapshot;
  public final Version callVersion;

  public EngineState() {
    this(Collections.emptyMap(), Collections.emptyMap(), null);
  }
  private EngineState(Map<String, Object> status, Map<String, Object> calls, Version version) {
    this.status = immutableMap(status);
    this.callSnapshot = immutableMap(calls);
    this.callVersion = version;
  }
  public EngineState withStatus(Map<String, Object> value) {
    return new EngineState(value, callSnapshot, callVersion);
  }
  public EngineState withCallSnapshot(Map<String, Object> value) throws IOException {
    Version version = Version.parse(value);
    if (callVersion != null && version.isOlderThan(callVersion))
      return this;
    return new EngineState(status, value, version);
  }
  public String ims() {
    return text(status, "ims");
  }

  public static final class Version {
    public final String epoch;
    public final long revision;
    public Version(String epoch, long revision) {
      if (epoch == null || epoch.isEmpty() || revision < 0)
        throw new IllegalArgumentException("Invalid call version");
      this.epoch = epoch;
      this.revision = revision;
    }
    public static Version parse(Map<String, Object> value) throws IOException {
      String epoch = text(value, "epoch");
      Long revision = integer(value.get("revision"));
      if (epoch == null || epoch.isEmpty() || revision == null || revision < 0) {
        throw new IOException("Call snapshot has no valid epoch/revision");
      }
      return new Version(epoch, revision);
    }
    /** Different epochs cannot be ordered numerically; a new epoch requires resynchronization. */
    public boolean isOlderThan(Version other) {
      return epoch.equals(other.epoch) && revision < other.revision;
    }
  }

  public static final class Event {
    public final String name;
    public final String event;
    public final Map<String, Object> data;
    public Event(String name, Map<String, Object> data) {
      if (name == null || name.isEmpty())
        throw new IllegalArgumentException("Event name required");
      this.name = name;
      this.event = name;
      this.data = immutableMap(data);
    }
    public Version versionOrNull() {
      try {
        return Version.parse(data);
      } catch (IOException ignored) {
        return null;
      }
    }
  }

  public static String text(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof String ? (String) value : null;
  }
  public static Long integer(Object value) {
    if (value instanceof Byte || value instanceof Short || value instanceof Integer
        || value instanceof Long) {
      return ((Number) value).longValue();
    }
    if (value instanceof BigDecimal) {
      try {
        return ((BigDecimal) value).longValueExact();
      } catch (ArithmeticException ignored) {
        return null;
      }
    }
    return null;
  }
  public static Map<String, Object> immutableMap(Map<String, Object> value) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : value.entrySet())
      result.put(e.getKey(), freeze(e.getValue()));
    return Collections.unmodifiableMap(result);
  }
  @SuppressWarnings("unchecked")
  private static Object freeze(Object value) {
    if (value instanceof Map)
      return immutableMap((Map<String, Object>) value);
    if (value instanceof List) {
      List<Object> copy = new ArrayList<>();
      for (Object item : (List<?>) value) copy.add(freeze(item));
      return Collections.unmodifiableList(copy);
    }
    return value;
  }
}
