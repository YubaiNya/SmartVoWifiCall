import com.smartvowificall.ims.lpa.LpaProtocol;

public final class LpaProtocolTest {
  private static int checks;
  private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError(); }
  private static void rejects(Runnable action) {
    checks++;
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Invalid input accepted");
  }
  public static void main(String[] args) {
    LpaProtocol.Activation code = LpaProtocol.activation(" LPA:1$smdp.example.com$example-token ");
    check(code.server.equals("smdp.example.com"));
    check(code.matchingId.equals("example-token"));
    check(!code.toString().contains("example-token"));
    check(LpaProtocol.activation("1$SMDP.EXAMPLE.COM$A_b-9").server.equals("smdp.example.com"));
    for (String invalid : new String[]{"", "LPA:2$smdp.example.com$x", "LPA:1$smdp.example.com$",
        "LPA:1$smdp.example.com$x$ignored", "LPA:1$https://smdp.example.com$x",
        "LPA:1$smdp.example.com:80$x", "LPA:1$smdp.example.com/path$x", "LPA:1$127.0.0.1$x",
        "LPA:1$user@smdp.example.com$x", "LPA:1$smdp.example.com$x\ny", "LPA:1$-bad.example$x"})
      rejects(() -> LpaProtocol.activation(invalid));
    check(LpaProtocol.nickname("中文").length == 6);
    check(LpaProtocol.nickname("a".repeat(63)).length == 63);
    rejects(() -> LpaProtocol.nickname("a".repeat(64)));
    rejects(() -> LpaProtocol.nickname("\uD800"));
    rejects(() -> LpaProtocol.nickname("a\0b"));
    // libeuicc inserts the allocated logical-channel number in CLA, including for GET RESPONSE.
    LpaProtocol.validateApdu(LpaProtocol.unhex("81E2910003BF2D00")); checks++;
    LpaProtocol.validateApdu(LpaProtocol.unhex("81C0000000")); checks++;
    LpaProtocol.validateApdu(LpaProtocol.unhex("8FE2910003BF2D0000")); checks++;
    for (String invalid : new String[]{"81E2910004BF2D00", "81E2910000", "0088000000",
        "81C0010000", "81C000000000", "81E29100", "81A4000002FFFF"})
      rejects(() -> LpaProtocol.validateApdu(LpaProtocol.unhex(invalid)));
    rejects(() -> LpaProtocol.unhex("FFF"));
    rejects(() -> LpaProtocol.unhex("ZZ"));
    check(LpaProtocol.mask("8901234567890123456").equals("•••• 3456"));
    check(!LpaProtocol.mask("8901234567890123456").contains("890123"));
    System.out.println("LPA input, APDU and redaction checks passed: " + checks);
  }
}
