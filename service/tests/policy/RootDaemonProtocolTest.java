import com.smartvowificall.ims.root.RootDaemonProtocol;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

public final class RootDaemonProtocolTest {
  private static int checks = 0;

  private static void check(boolean cond, String msg) {
    checks++;
    if (!cond) throw new AssertionError("FAIL: " + msg);
  }

  private static void checkThrows(Runnable fn, String expectedCode) {
    checks++;
    try {
      fn.run();
      throw new AssertionError("Expected exception " + expectedCode);
    } catch (IllegalArgumentException e) {
      if (!expectedCode.equals(e.getMessage())) {
        throw new AssertionError("Expected " + expectedCode + " got " + e.getMessage());
      }
    }
  }

  public static void main(String[] args) {
    // Peer UID authorization checks
    check(RootDaemonProtocol.isAuthorizedPeerUid(0, -1), "root always allowed");
    check(RootDaemonProtocol.isAuthorizedPeerUid(0, 10234), "root allowed with app uid");
    check(RootDaemonProtocol.isAuthorizedPeerUid(10234, 10234), "matching app uid allowed");
    check(!RootDaemonProtocol.isAuthorizedPeerUid(10235, 10234), "other app uid rejected");
    check(!RootDaemonProtocol.isAuthorizedPeerUid(2000, 2000), "shell uid below 10000 rejected");
    check(!RootDaemonProtocol.isAuthorizedPeerUid(1000, 1000), "system uid below 10000 rejected");
    check(!RootDaemonProtocol.isAuthorizedPeerUid(-1, -1), "negative uid rejected");

    // Round-trip request encoding & parsing
    byte[] payload = "proxies:\n  - name: hk\n".getBytes(StandardCharsets.UTF_8);
    String wire = RootDaemonProtocol.encodeRequest("clash", Collections.singletonList("save-profile"), payload);
    RootDaemonProtocol.Request req = RootDaemonProtocol.parseRequest(wire);
    check("clash".equals(req.target), "target matches");
    check(req.args.length == 1 && "save-profile".equals(req.args[0]), "args match");
    check(Arrays.equals(payload, req.stdinBytes), "stdin bytes match");

    // Ping without args or stdin
    String pingWire = RootDaemonProtocol.encodeRequest("ping", Collections.emptyList(), null);
    RootDaemonProtocol.Request pingReq = RootDaemonProtocol.parseRequest(pingWire);
    check("ping".equals(pingReq.target), "ping target");
    check(pingReq.args.length == 0, "ping empty args");
    check(pingReq.stdinBytes == null, "ping null stdin");

    // Multi-arg identity request
    String idWire = RootDaemonProtocol.encodeRequest("identity",
        Arrays.asList("ims-bind", "2", "1", "simkey", "45403"), null);
    RootDaemonProtocol.Request idReq = RootDaemonProtocol.parseRequest(idWire);
    check("identity".equals(idReq.target) && idReq.args.length == 5, "identity args");

    // Invalid targets & injection rejection
    checkThrows(() -> RootDaemonProtocol.encodeRequest("shell", Collections.emptyList(), null), "DAEMON_TARGET_INVALID");
    checkThrows(() -> RootDaemonProtocol.parseRequest("{\"target\":\"exec\",\"args\":[\"id\"]}"), "DAEMON_TARGET_INVALID");
    checkThrows(() -> RootDaemonProtocol.parseRequest(""), "DAEMON_REQUEST_EMPTY");
    checkThrows(() -> RootDaemonProtocol.parseRequest("{\"target\":\"identity\",\"args\":[\"a\\u0000b\"]}"), "DAEMON_ARGS_INVALID");
    checkThrows(() -> RootDaemonProtocol.parseRequest("{\"target\":\"clash\",\"stdinBase64\":\"!!!bad\"}"), "DAEMON_STDIN_INVALID");

    // Error sanitization
    check("{\"ok\":false,\"code\":\"UNAUTHORIZED_UID\"}".equals(RootDaemonProtocol.encodeError("UNAUTHORIZED_UID")), "clean error");
    check("{\"ok\":false,\"code\":\"DAEMON_ERROR\"}".equals(RootDaemonProtocol.encodeError("bad message with spaces")), "sanitized error");

    System.out.println("PASS " + checks + " root daemon protocol & UID authorization assertions.");
  }
}
