package com.newvowifi.ims.root;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads the current subscription through Android's IPhoneSubInfo contract.
 * No APK discovery, saved profile, phone number, or external helper is used. */
final class SimCredentials {
  interface Reader {
    Object read(String method, int identity, boolean attributed) throws Exception;
  }
  static final class ReadFailure extends Exception {
    final String code;
    ReadFailure(String code) { super(code); this.code = code; }
  }
  private enum Kind { EMPTY, VALUE, INVALID, DENIED, UNSUPPORTED }
  private static final class Field {
    final Kind kind;
    final String value;
    Field(Kind kind, String value) { this.kind = kind; this.value = value; }
  }
  final String imsi, imei, impi, sipImpu, homeDomain, status;
  private SimCredentials(String imsi, String imei, String impi, String sipImpu,
      String homeDomain, String status) {
    this.imsi = imsi; this.imei = imei; this.impi = impi;
    this.sipImpu = sipImpu; this.homeDomain = homeDomain; this.status = status;
  }

  static SimCredentials read(Reader reader, int subId, int phoneId) throws ReadFailure {
    if (subId < 0 || phoneId < 0) throw new ReadFailure("SUBSCRIPTION_MAPPING_CHANGED");
    String imsi = required(reader, "getSubscriberIdForSubscriber", subId, "IMSI_UNAVAILABLE");
    // IPhoneSubInfo takes a phone ID, which need not equal the SIM slot number.
    String imei = required(reader, "getDeviceIdForPhone", phoneId, "IMEI_UNAVAILABLE");
    Field privateId = optional(reader, "getImsPrivateUserIdentity", subId, true);
    Field domain = optional(reader, "getIsimDomain", subId, false);
    Field publicId = optional(reader, "getIsimImpu", subId, false);
    if (!imsi.equals(required(reader, "getSubscriberIdForSubscriber", subId, "IMSI_UNAVAILABLE")))
      throw new ReadFailure("SUBSCRIPTION_CHANGED_DURING_READ");
    String status;
    if (privateId.kind == Kind.DENIED || domain.kind == Kind.DENIED || publicId.kind == Kind.DENIED)
      status = "denied";
    else if (privateId.kind == Kind.UNSUPPORTED || domain.kind == Kind.UNSUPPORTED
        || publicId.kind == Kind.UNSUPPORTED)
      status = "unsupported";
    else if (privateId.kind == Kind.EMPTY && domain.kind == Kind.EMPTY && publicId.kind == Kind.EMPTY)
      status = "empty";
    else if (privateId.kind == Kind.VALUE && domain.kind == Kind.VALUE && publicId.kind == Kind.VALUE
        && validImpi(privateId.value) && validHost(domain.value) && validImpu(publicId.value))
      return new SimCredentials(imsi, imei, privateId.value, publicId.value, domain.value, "available");
    else status = "invalid";
    // Never combine a partial ISIM tuple with a stale tuple from a prior SIM.
    return new SimCredentials(imsi, imei, "", "", "", status);
  }

  Map<String, String> fields() {
    Map<String, String> result = new LinkedHashMap<>();
    result.put("imsi", imsi);
    result.put("imei", imei);
    result.put("imsIdentityMode", "auto");
    result.put("impi", impi);
    result.put("sipImpu", sipImpu);
    result.put("imsHomeDomain", homeDomain);
    result.put("imsIdentityReadStatus", status);
    return result;
  }

  private static String required(Reader reader, String method, int id, String failure)
      throws ReadFailure {
    try {
      Object value = reader.read(method, id, true);
      if (value instanceof String && ((String) value).matches("[0-9]{15}")) return (String) value;
    } catch (Exception ignored) { }
    throw new ReadFailure(failure);
  }

  private static Field optional(Reader reader, String method, int subId, boolean attributed) {
    try {
      Object value = reader.read(method, subId, attributed);
      if (method.equals("getIsimImpu")) {
        if (value == null) return field(null);
        if (!(value instanceof String[])) return new Field(Kind.INVALID, "");
        boolean nonempty = false;
        for (String item : (String[]) value) {
          Field candidate = field(item);
          if (candidate.kind == Kind.VALUE && validImpu(candidate.value)) return candidate;
          nonempty |= candidate.kind != Kind.EMPTY;
        }
        return new Field(nonempty ? Kind.INVALID : Kind.EMPTY, "");
      }
      return value == null || value instanceof String ? field((String) value)
                                                     : new Field(Kind.INVALID, "");
    } catch (Exception error) {
      while (error instanceof InvocationTargetException && error.getCause() instanceof Exception)
        error = (Exception) error.getCause();
      Kind kind = error instanceof SecurityException ? Kind.DENIED
          : error instanceof NoSuchMethodException ? Kind.UNSUPPORTED : Kind.INVALID;
      return new Field(kind, "");
    }
  }

  private static Field field(String value) {
    if (value == null || value.trim().isEmpty()) return new Field(Kind.EMPTY, "");
    // Keep credentials opaque. Control characters can never be emitted into a SIP header.
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) < 32 || value.charAt(i) == 127) return new Field(Kind.INVALID, "");
    return new Field(Kind.VALUE, value.trim());
  }
  static boolean validHost(String value) {
    if (value == null || value.isEmpty()) return false;
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) <= 32 || value.charAt(i) == 127 || "@/;?".indexOf(value.charAt(i)) >= 0)
        return false;
    return value.indexOf(':') < 0 || (value.startsWith("[") && value.endsWith("]"));
  }
  static boolean validImpi(String value) {
    if (value == null) return false;
    int at = value.indexOf('@');
    return at > 0 && at == value.lastIndexOf('@') && noWhitespace(value)
        && validHost(value.substring(at + 1));
  }
  static boolean validImpu(String value) {
    if (value == null || !value.startsWith("sip:") || !noWhitespace(value)) return false;
    int at = value.indexOf('@', 4);
    if (at <= 4 || at != value.lastIndexOf('@')) return false;
    String host = value.substring(at + 1);
    int end = host.length();
    for (char separator : new char[] {';', '?'}) {
      int index = host.indexOf(separator);
      if (index >= 0) end = Math.min(end, index);
    }
    host = host.substring(0, end);
    // SIP authorities can carry a port. IPv6 literals keep their brackets.
    if (host.startsWith("[")) {
      int close = host.indexOf(']');
      if (close < 0 || !host.substring(close + 1).matches("(:[0-9]+)?")) return false;
      host = host.substring(0, close + 1);
    } else {
      int colon = host.indexOf(':');
      if (colon >= 0) {
        if (!host.substring(colon + 1).matches("[0-9]+")) return false;
        host = host.substring(0, colon);
      }
    }
    return validHost(host);
  }
  private static boolean noWhitespace(String value) {
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) <= 32 || value.charAt(i) == 127) return false;
    return true;
  }
}
