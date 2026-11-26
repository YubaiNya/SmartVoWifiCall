import com.smartvowificall.ims.policy.VoiceServiceOverride;

/** No phone access: emulate the external reset observed after hanging up on Android 17. */
public final class VoiceServiceOverrideTest {
  private static int checks;
  private static void check(boolean value, String label) {
    checks++;
    if (!value) throw new AssertionError(label);
  }
  private static final class Phone implements VoiceServiceOverride.Backend {
    boolean inService, fail;
    int writes;
    public void apply(boolean hasService) throws Exception {
      writes++;
      inService = hasService;
      if (fail) throw new Exception("BINDER_REPLY_LOST");
    }
  }
  private static void sync(VoiceServiceOverride sync, boolean desired, Phone phone) throws Exception {
    sync.synchronize(desired, phone.inService, phone);
  }
  public static void main(String[] args) throws Exception {
    VoiceServiceOverride sync = new VoiceServiceOverride();
    Phone phone = new Phone();
    check(sync.lastApplied() == null, "initial state unknown");
    sync(sync, true, phone);
    check(phone.inService && Boolean.TRUE.equals(sync.lastApplied()), "registered voice enabled");
    for (int i = 0; i < 100; i++) sync(sync, true, phone);
    check(phone.writes == 1, "confirmed framework state avoids repeated root processes");
    phone.inService = false;
    sync(sync, true, phone);
    check(phone.inService && phone.writes == 2, "hangup reset repaired despite unchanged registration");
    phone.inService = false;
    sync(sync, true, phone);
    check(phone.inService && phone.writes == 3, "successive hangups and phone restarts repaired");
    sync.synchronize(true, false, phone);
    check(phone.writes == 4, "unknown framework readback never suppresses repair");
    sync(sync, false, phone);
    check(!phone.inService && Boolean.FALSE.equals(sync.lastApplied()), "registration or permission loss clears override");
    int writes = phone.writes;
    sync(sync, false, phone);
    check(phone.writes == writes, "disabled state does not issue redundant writes");
    phone.fail = true;
    try {
      sync(sync, true, phone);
      throw new AssertionError("failure swallowed");
    } catch (Exception expected) {
      check(sync.lastApplied() == null, "failed write invalidates stale success");
    }
    phone.fail = false;
    sync(sync, false, phone);
    check(!phone.inService && phone.writes == writes + 2, "uncertain enable is cleared, not skipped");
    sync(sync, true, phone);
    phone.fail = true;
    try {
      sync(sync, false, phone);
      throw new AssertionError("failed clear swallowed");
    } catch (Exception expected) {
      check(sync.lastApplied() == null, "failed clear remains unknown");
    }
    phone.fail = false;
    sync(sync, false, phone);
    check(Boolean.FALSE.equals(sync.lastApplied()), "failed clear retried");
    System.out.println("PASS " + checks + " voice override assertions; no device or actual calls.");
  }
}
