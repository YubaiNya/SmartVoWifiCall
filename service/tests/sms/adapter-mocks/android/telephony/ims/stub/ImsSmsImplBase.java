package android.telephony.ims.stub;
import java.util.concurrent.Executor;

/** Host-only listener surface; never loaded into the Android app. */
public class ImsSmsImplBase {
    public static final int SEND_STATUS_ERROR = 2, RESULT_NO_NETWORK_ERROR = -1;
    public static final int DELIVER_STATUS_OK = 1, DELIVER_STATUS_ERROR_GENERIC = 2;
    public interface Sent { void result(int token, int reference, boolean success); }
    public interface Received { void result(int token, String format, byte[] pdu); }
    public Sent sent;
    public Received receivedListener;
    public ImsSmsImplBase(Executor ignored) {}
    public String getSmsFormat() { return "3gpp"; }
    public void onReady() {}
    public void sendSms(int token, int reference, String format, String smsc, boolean retry, byte[] pdu) {}
    public void acknowledgeSms(int token, int reference, int result) {}
    public void acknowledgeSms(int token, int reference, int result, byte[] pdu) { acknowledgeSms(token, reference, result); }
    public final void onSendSmsResultSuccess(int token, int reference) { sent.result(token, reference, true); }
    public final void onSendSmsResultError(int token, int reference, int status, int reason, int error) { sent.result(token, reference, false); }
    public final void onSmsReceived(int token, String format, byte[] pdu) { receivedListener.result(token, format, pdu); }
}
