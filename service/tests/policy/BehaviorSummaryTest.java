import com.smartvowificall.ims.policy.BehaviorSummary;
import java.util.HashMap;
import java.util.Map;

public final class BehaviorSummaryTest {
  private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
  public static void main(String[] args) {
    Map<String,Object> value = new HashMap<>();
    check(BehaviorSummary.describe(value, true).contains("未知"));
    value.put("state", "IMS_REGISTERED_IWLAN");
    check(BehaviorSummary.describe(value, false).contains("未知"));
    check(BehaviorSummary.describe(value, true).contains("注册"));
    value.put("state", "RETRY_BACKOFF"); value.put("failureState", "IKE_AUTH_FAILURE"); value.put("manualOnly", true);
    check(BehaviorSummary.describe(value, true).contains("手动重试"));
    value.put("cause", "IKE_FINAL_AUTH_REJECTED");
    check(BehaviorSummary.describe(value, true).contains("原因未确定"));
    check(!BehaviorSummary.describe(value, true).contains("检查 SIM"));
    value.remove("cause"); value.put("failureState", "IKE_CHALLENGE_FAILED"); value.put("manualOnly", false);
    check(BehaviorSummary.describe(value, true).contains("挑战未完成"));
    value.put("state", "future-private-payload");
    check(!BehaviorSummary.describe(value, true).contains("future-private-payload"));
    System.out.println("PASS behavior summary: unknown/disconnected/auth/manual/future state");
  }
}
