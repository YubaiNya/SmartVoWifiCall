import com.smartvowificall.ims.lpa.LpaProtocol;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs inside Android ART with the actual shipped JNI library and an in-memory SGP.22 card. */
public final class LpaNativeProbe {
  private static int checks, closed, opens, httpRequests;
  private static boolean empty, failList, rejectMutation, unknownState, failOpen, failTrust, failNotifications, failDisconnect;
  private static final ClassLoader loader = LpaNativeProbe.class.getClassLoader();
  private static byte[] join(byte[]... parts) {
    int size = 0; for (byte[] p : parts) size += p.length;
    byte[] out = new byte[size]; int offset = 0;
    for (byte[] p : parts) { System.arraycopy(p, 0, out, offset, p.length); offset += p.length; }
    return out;
  }
  private static byte[] tlv(int tag, byte[] value) {
    byte[] t = tag > 255 ? new byte[]{(byte)(tag >> 8), (byte)tag} : new byte[]{(byte)tag};
    byte[] length = value.length < 128 ? new byte[]{(byte)value.length} : new byte[]{(byte)0x81, (byte)value.length};
    return join(t, length, value);
  }
  private static byte[] profile(boolean enabled) {
    return tlv(0xE3, join(tlv(0x5A, LpaProtocol.unhex(enabled ? "00000000000000000000" : "00000000000000000010")),
        tlv(0x9F70, new byte[]{(byte)(unknownState ? 9 : enabled ? 1 : 0)}),
        tlv(0x91, (enabled ? "Mock active" : "Mock inactive").getBytes(StandardCharsets.UTF_8)),
        tlv(0x92, "Offline fixture".getBytes(StandardCharsets.UTF_8)),
        tlv(0x95, new byte[]{2})));
  }
  private static byte[] response(byte[] tx) {
    LpaProtocol.validateApdu(tx);
    if ((tx[0] & 15) != 1) throw new AssertionError("Channel CLA was lost");
    if ((tx[1] & 255) != 0xE2) throw new AssertionError("Unexpected APDU");
    int tag = ((tx[5] & 255) << 8) | (tx[6] & 255);
    byte[] content;
    switch (tag) {
      case 0xBF3E: content = tlv(tag, tlv(0x5A, new byte[16])); break;
      case 0xBF22: return LpaProtocol.unhex("6A82"); // Optional extended info unavailable.
      case 0xBF2D:
        if (failList) return LpaProtocol.unhex("6F00");
        content = tlv(tag, tlv(0xA0, empty ? new byte[0] : join(profile(true), profile(false)))); break;
      case 0xBF28:
        if (failNotifications) return LpaProtocol.unhex("6F00");
        content = tlv(tag, tlv(0xA0, new byte[0])); break;
      case 0xBF29: case 0xBF31: case 0xBF32: case 0xBF33:
        content = tlv(tag, tlv(0x80, new byte[]{(byte)(rejectMutation ? 1 : 0)})); break;
      case 0xBF41: content = tlv(tag, tlv(0x80, new byte[]{0})); break;
      default: throw new AssertionError("Unexpected card command: " + Integer.toHexString(tag));
    }
    return join(content, LpaProtocol.unhex("9000"));
  }
  private static Object assistant() throws Exception {
    Class<?> apdu = Class.forName("net.typeblog.lpac_jni.ApduInterface");
    Class<?> http = Class.forName("net.typeblog.lpac_jni.HttpInterface");
    Object a = Proxy.newProxyInstance(loader, new Class<?>[]{apdu}, (p, m, args) -> {
      switch (m.getName()) {
        case "connect": return null;
        case "disconnect": if (failDisconnect) throw new IllegalStateException("LPA_CANCELLED"); return null;
        case "getValid": return true;
        case "logicalChannelOpen":
          if (failOpen) throw new IllegalStateException("LPA_PAUSE_VOWIFI_REQUIRED");
          opens++; return 1;
        case "logicalChannelClose": closed++; return null;
        case "transmit": return response((byte[])args[1]);
        case "toString": return "MockApdu";
        default: throw new AssertionError("Unexpected APDU callback");
      }
    });
    Object h = Proxy.newProxyInstance(loader, new Class<?>[]{http}, (p, m, args) -> {
      if (m.getName().equals("usePublicKeyIds")) {
        if (failTrust) throw new IllegalStateException("TLS fixture rejection");
        return null;
      }
      httpRequests++; throw new AssertionError("Network must not be called");
    });
    return Class.forName("net.typeblog.lpac_jni.impl.LocalProfileAssistantImpl")
        .getConstructor(byte[].class, apdu, http).newInstance(LpaProtocol.unhex(LpaProtocol.ISDR_AID), a, h);
  }
  private static Object call(Object a, String name) throws Exception { return a.getClass().getMethod(name).invoke(a); }
  private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
  public static void main(String[] ignored) throws Exception {
    Object a = assistant();
    check(call(a, "getEID").equals("00000000000000000000000000000000"));
    List<?> profiles = (List<?>)call(a, "getProfiles"); check(profiles.size() == 2);
    Object profile = profiles.get(1);
    String iccid = (String)call(profile, "getIccid");
    check(call(profile, "getState").toString().equals("Disabled"));
    check(((List<?>)call(a, "getNotifications")).isEmpty());
    failNotifications = true;
    try { call(a, "getNotifications"); throw new AssertionError("Notification read failure hidden"); }
    catch (InvocationTargetException expected) { check(expected.getCause() instanceof IllegalStateException); }
    failNotifications = false;
    unknownState = true; check(call(((List<?>)call(a, "getProfiles")).get(0), "getState").toString().equals("Unknown")); unknownState = false;
    check((Boolean)a.getClass().getMethod("enableProfile", String.class, boolean.class).invoke(a, iccid, true));
    check((Boolean)a.getClass().getMethod("disableProfile", String.class, boolean.class).invoke(a, iccid, true));
    check((Boolean)a.getClass().getMethod("deleteProfile", String.class).invoke(a, iccid));
    a.getClass().getMethod("setNickname", String.class, String.class).invoke(a, iccid, "测试昵称"); checks++;
    rejectMutation = true;
    check(!(Boolean)a.getClass().getMethod("enableProfile", String.class, boolean.class).invoke(a, iccid, true));
    rejectMutation = false;
    empty = true; check(((List<?>)call(a, "getProfiles")).isEmpty()); empty = false;
    failList = true;
    try { call(a, "getProfiles"); throw new AssertionError("Read failure reported as empty card"); }
    catch (InvocationTargetException e) { check(e.getCause() instanceof IllegalStateException); }
    failList = false;
    for (int i = 0; i < 100; i++) check(((List<?>)call(a, "getProfiles")).size() == 2);
    Class<?> input = Class.forName("net.typeblog.lpac_jni.ProfileDownloadInput");
    Class<?> callback = Class.forName("net.typeblog.lpac_jni.ProfileDownloadCallback");
    Object c = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (p,m,args) -> false);
    try {
      a.getClass().getMethod("downloadProfile", input, callback).invoke(a,
          input.getConstructor(String.class, String.class, String.class, String.class)
              .newInstance("smdp.example.com", "offline-fixture", null, null), c);
      throw new AssertionError("Cancelled download completed");
    } catch (InvocationTargetException expected) { checks++; }
    check(httpRequests == 0);
    call(a, "close"); call(a, "close"); check(closed == 1);
    failTrust = true;
    try { assistant(); throw new AssertionError("Trust setup failure accepted"); }
    catch (InvocationTargetException expected) { check(closed == 2); }
    failTrust = false; failOpen = true;
    try { assistant(); throw new AssertionError("Denied card access accepted"); }
    catch (InvocationTargetException expected) { checks++; }
    check(opens == closed);
    failOpen = false;
    Object teardown = assistant();
    failDisconnect = true;
    try { call(teardown, "close"); throw new AssertionError("Teardown error not exercised"); }
    catch (InvocationTargetException expected) { checks++; }
    call(teardown, "close"); check(opens == closed);
    failDisconnect = false;
    Class<?> httpType = Class.forName("com.smartvowificall.ims.LpaHttp");
    java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
    Object realHttp = httpType.getConstructor(java.util.concurrent.atomic.AtomicBoolean.class).newInstance(cancel);
    httpType.getMethod("usePublicKeyIds", String[].class).invoke(realHttp,
        (Object)new String[]{"81370f5125d0b1d408d4c3b232e6d25e795bebfb"}); checks++;
    Method transmit = httpType.getMethod("transmit", String.class, byte[].class, String[].class);
    for (String invalid : new String[]{"http://smdp.example.com/", "https://user:pass@smdp.example.com/",
        "https://smdp.example.com:8080/", "https://127.0.0.1/", "https://smdp.example.com/#fragment"}) {
      try { transmit.invoke(realHttp, invalid, new byte[0], new String[0]); throw new AssertionError("Unsafe URL accepted"); }
      catch (InvocationTargetException e) { check(e.getCause() instanceof IllegalArgumentException); }
    }
    cancel.set(true);
    try { transmit.invoke(realHttp, "https://smdp.example.com/", new byte[0], new String[0]); throw new AssertionError(); }
    catch (InvocationTargetException e) { check(e.getCause() instanceof IllegalStateException); }
    System.out.println("{\"native_fixture_checks\":" + checks + ",\"network_requests\":" + httpRequests + ",\"open_channels_remaining\":" + (opens-closed) + "}");
  }
}
