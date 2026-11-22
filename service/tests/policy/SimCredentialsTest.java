package com.newvowifi.ims.root;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fixed, fictitious identities. No Android, external APK, modem or network is used. */
public final class SimCredentialsTest {
  private static int checks;
  private static final String IMSI = "234330000000019", IMEI = "000000000000019";
  private static void check(boolean condition, String label) {
    checks++;
    if (!condition) throw new AssertionError(label);
  }
  private static final class Device implements SimCredentials.Reader {
    final Map<String, Object> values = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    int subscriberReads;
    boolean changed;
    Device() {
      values.put("getSubscriberIdForSubscriber", IMSI);
      values.put("getDeviceIdForPhone", IMEI);
    }
    public Object read(String method, int id, boolean attributed) throws Exception {
      calls.add(method);
      check(id == (method.equals("getDeviceIdForPhone") ? 0 : 19), "phone ID and sub ID kept separate");
      check(attributed == !(method.equals("getIsimDomain") || method.equals("getIsimImpu")), "Binder attribution shape");
      if (method.equals("getSubscriberIdForSubscriber") && ++subscriberReads > 1 && changed)
        return "234330000000020";
      Object value = values.get(method);
      if (value instanceof Exception) throw (Exception) value;
      return value;
    }
    void completeIsim() {
      values.put("getImsPrivateUserIdentity", IMSI + "@ims.example.test");
      values.put("getIsimDomain", "ims.example.test");
      values.put("getIsimImpu", new String[] {"tel:+440000000000", "sip:user@ims.example.test"});
    }
  }
  private static SimCredentials read(Device d) throws Exception {
    return SimCredentials.read(d, 19, 0);
  }
  private static void fails(Device d, String code) throws Exception {
    try { read(d); throw new AssertionError("expected " + code); }
    catch (SimCredentials.ReadFailure error) { check(code.equals(error.code), "failure classification"); }
  }
  public static void main(String[] args) throws Exception {
    Device empty = new Device();
    SimCredentials result = read(empty);
    check(result.status.equals("empty"), "USIM without ISIM remains auto mode");
    check(result.imsi.equals(IMSI) && result.imei.equals(IMEI), "fresh mandatory identity");
    check(result.fields().size() == 7 && result.fields().get("imsIdentityMode").equals("auto"), "engine identity schema");
    check(!result.fields().containsKey("subId") && !result.fields().containsKey("phoneNumber"), "no transient or invented identity");
    check(empty.subscriberReads == 2, "live IMSI rechecked");
    Device complete = new Device(); complete.completeIsim();
    result = read(complete);
    check(result.status.equals("available") && result.sipImpu.equals("sip:user@ims.example.test"), "select SIP identity from mixed list");
    check(result.homeDomain.equals("ims.example.test"), "ISIM home domain preserved");
    for (String missing : new String[] {"getImsPrivateUserIdentity", "getIsimDomain", "getIsimImpu"}) {
      Device partial = new Device(); partial.completeIsim(); partial.values.remove(missing);
      result = read(partial);
      check(result.status.equals("invalid") && result.impi.isEmpty() && result.sipImpu.isEmpty()
          && result.homeDomain.isEmpty(), "partial tuple is never mixed with other data");
    }
    for (Exception error : new Exception[] {new SecurityException(), new NoSuchMethodException(), new IllegalStateException()}) {
      Device d = new Device(); d.completeIsim();
      d.values.put("getImsPrivateUserIdentity", new InvocationTargetException(error));
      String expected = error instanceof SecurityException ? "denied" : error instanceof NoSuchMethodException ? "unsupported" : "invalid";
      result = read(d); check(result.status.equals(expected) && result.impi.isEmpty(), "optional read failure retains its meaning");
    }
    Device denied = new Device();
    denied.values.put("getImsPrivateUserIdentity", new NoSuchMethodException());
    denied.values.put("getIsimDomain", new SecurityException());
    check(read(denied).status.equals("denied"), "denied takes precedence over unsupported");
    for (Object bad : new Object[] {"", "234330000000019 ", "23433000000001", "２34330000000019", new SecurityException(), 123}) {
      Device d = new Device(); d.values.put("getSubscriberIdForSubscriber", bad); fails(d, "IMSI_UNAVAILABLE");
    }
    Device noImei = new Device(); noImei.values.remove("getDeviceIdForPhone"); fails(noImei, "IMEI_UNAVAILABLE");
    Device changed = new Device(); changed.changed = true; fails(changed, "SUBSCRIPTION_CHANGED_DURING_READ");
    for (String bad : new String[] {"sip:user@ims.test\r\nRoute: bad", "tel:+123", "sips:user@ims.test", "sip:@ims.test", "sip:user@", "sip:user@@ims.test", "sip:user@ims.test:bad"})
      check(!SimCredentials.validImpu(bad), "invalid SIP identity rejected");
    for (String good : new String[] {"sip:user@ims.test", "sip:user@ims.test:5060;user=phone", "sip:user@[2001:db8::1]:5060"})
      check(SimCredentials.validImpu(good), "valid SIP authority");
    Device injected = new Device(); injected.completeIsim();
    injected.values.put("getIsimDomain", "ims.test\r\nAuthorization: example");
    check(read(injected).status.equals("invalid"), "ISIM controls cannot become headers");
    System.out.println("PASS " + checks + " standalone SIM credential assertions; fictitious Binder responses only.");
  }
}
