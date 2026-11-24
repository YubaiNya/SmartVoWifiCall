package com.smartvowificall.ims;

import android.os.Message;
import android.telephony.TelephonyManager;
import android.telephony.ims.*;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import com.smartvowificall.ims.engine.*;
import java.util.Map;

/** State and framework callbacks run on rt.worker; blocking engine requests run on rt.io. */
final class NewCallSession extends ImsCallSessionImplBase {
  final NewMmTelFeature feature;
  final ImsCallProfile profile;
  final RuntimeController.Binding owner;
  volatile String id = "";
  volatile int state = State.IDLE;
  ImsCallSessionListener listener;
  PcmAudio audio;
  boolean initiated, ended, dialStarted, dialPending, cancelRequested, endRequestInFlight, muted;
  boolean initiatingSent, progressingSent, initiatedSent, terminalSent, progressing;
  String lastEndMethod = "";
  int endRequestSerial;
  ImsReasonInfo terminalReason;
  NewCallSession(NewMmTelFeature f, ImsCallProfile p) {
    feature = f;
    profile = p;
    owner = f.binding();
  }
  private static String failureCode(Throwable error) {
    if (error == null) return "none";
    if (error instanceof EngineRpcClient.RpcException)
      return "RPC_" + ((EngineRpcClient.RpcException) error).code;
    String message = error.getMessage();
    return message != null && message.matches("[A-Z0-9_]{3,80}")
        ? message : error.getClass().getSimpleName();
  }
  private void trace(String event) {
    android.util.Log.i("SmartVoWifiCall", "session=" + Integer.toHexString(System.identityHashCode(this))
        + " " + event + " state=" + state + " ended=" + ended + " initiated=" + initiated);
  }
  public void setListener(ImsCallSessionListener l) {
    feature.rt.worker.execute(() -> {
      trace("listener=" + (l != null));
      listener = l;
      flushCallbacks();
    });
  }
  public String getCallId() {
    return id;
  }
  public ImsCallProfile getCallProfile() {
    return profile;
  }
  public ImsCallProfile getLocalCallProfile() {
    return profile;
  }
  public ImsCallProfile getRemoteCallProfile() {
    return profile;
  }
  public int getState() {
    return state;
  }
  public boolean isInCall() {
    return state == State.ESTABLISHED;
  }
  public boolean isMultiparty() {
    return false;
  }
  boolean ownsBinding() {
    return owner != null && feature.binding() == owner;
  }
  boolean canControl() {
    return ownsBinding() && feature.rt.billable(feature.slot) && feature.rt.radioReady(owner) && owner.engine.isConnected();
  }

  public void start(String callee, ImsCallProfile ignored) {
    feature.rt.worker.execute(() -> {
      trace("start");
      if (ended || dialStarted)
        return;
      if (!canControl() || !owner.registered
          || profile.getServiceType() != ImsCallProfile.SERVICE_TYPE_NORMAL
          || profile.getCallType() != ImsCallProfile.CALL_TYPE_VOICE
          || feature.pendingOutgoing != null) {
        dialStarted = true;
        ended(true);
        return;
      }
      try {
        if (feature.rt.context.getSystemService(TelephonyManager.class).isEmergencyNumber(callee)) {
          dialStarted = true;
          ended(true);
          return;
        }
      } catch (RuntimeException e) {
        dialStarted = true;
        ended(true);
        return;
      }
      dialStarted = true;
      dialPending = true;
      owner.callActive = true;
      feature.pendingOutgoing = this;
      state = State.INITIATED;
      flushCallbacks();
      feature.rt.io.execute(() -> {
        Map<String, Object> value = null;
        Throwable error = null;
        try {
          if (!ownsBinding())
            throw new IllegalStateException("STALE_BINDING");
          feature.rt.verifyRadioOff(owner);
          if (!ownsBinding() || !owner.registered) throw new IllegalStateException("IMS_NOT_REGISTERED");
          value = owner.engine.dial(callee);
        } catch (Exception e) {
          error = e;
        }
        Map<String, Object> result = value;
        Throwable failure = error;
        feature.rt.worker.execute(() -> dialCompleted(result, failure));
      });
    });
  }
  private void dialCompleted(Map<String, Object> result, Throwable error) {
    trace("dialCompleted error=" + failureCode(error));
    dialPending = false;
    if (error != null) {
      if (!ended) {
        if (!id.isEmpty())
          bestEffortEnd(initiated ? "hangup" : "cancelCall");
        endWith(cancelRequested ? ImsReasonInfo.CODE_USER_TERMINATED
                                : ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
      }
      return;
    }
    String token = EngineState.text(result, "callId"), phase = EngineState.text(result, "state");
    if (token == null || token.isEmpty()) {
      if (!ended)
        ended(true);
      return;
    }
    if (ended || !ownsBinding()) {
      if (ownsBinding()) {
        id = token;
        bestEffortEnd("IN_CALL".equals(phase) ? "hangup" : "cancelCall");
      }
      return;
    }
    if (cancelRequested && "IN_CALL".equals(phase)) {
      initiated = true;
      if (!lastEndMethod.equals("hangup"))
        endRequestInFlight = false;
    }
    bindCallId(token);
    if (cancelRequested)
      requestEnd();
    else if (phase != null)
      stage(phase);
  }
  /** Called on rt.worker by an OUTGOING event/snapshot when the dial reply is still pending. */
  void bindCallId(String token) {
    if (ended || token == null || token.isEmpty() || !ownsBinding())
      return;
    if (!id.isEmpty() && !id.equals(token))
      return;
    NewCallSession existing = feature.calls.get(token);
    if (existing != null && existing != this)
      return;
    id = token;
    feature.calls.put(token, this);
    if (cancelRequested)
      requestEnd();
  }
  public void accept(int type, ImsStreamMediaProfile ignored) {
    feature.rt.worker.execute(() -> {
      if (ended || cancelRequested || initiated)
        return;
      if (type != ImsCallProfile.CALL_TYPE_VOICE || !canControl() || !owner.registered
          || id.isEmpty()) {
        ended(true);
        return;
      }
      runControl("answer", true);
    });
  }
  public void reject(int reason) {
    feature.rt.worker.execute(() -> {
      if (!ended) {
        cancelRequested = true;
        requestEnd();
      }
    });
  }
  public void terminate(int reason) {
    trace("terminateRequested");
    feature.rt.worker.execute(() -> {
      trace("terminate");
      if (!ended) {
        cancelRequested = true;
        requestEnd();
      }
    });
  }
  private void requestEnd() {
    if (ended || endRequestInFlight)
      return;
    state = State.TERMINATING;
    if (id.isEmpty()) {
      if (!dialPending)
        endWith(ImsReasonInfo.CODE_USER_TERMINATED);
      return;
    }
    if (!canControl()) {
      ended(true);
      return;
    }
    endRequestInFlight = true;
    endRequestSerial++;
    lastEndMethod = initiated ? "hangup" : dialStarted ? "cancelCall" : "reject";
    runControl(lastEndMethod, false);
  }
  private void runControl(String method, boolean answer) {
    String token = id;
    int serial = endRequestSerial;
    feature.rt.io.execute(() -> {
      Map<String, Object> value = null;
      Throwable error = null;
      try {
        if (!canControl())
          throw new IllegalStateException("STALE_BINDING");
        switch (method) {
          case "answer":
            value = owner.engine.answer(token);
            break;
          case "reject":
            value = owner.engine.reject(token);
            break;
          case "hangup":
            value = owner.engine.hangup(token);
            break;
          default:
            value = owner.engine.cancelCall(token);
        }
      } catch (Exception e) {
        error = e;
      }
      Map<String, Object> result = value;
      Throwable failure = error;
      feature.rt.worker.execute(() -> {
        if (ended || !ownsBinding() || (!answer && serial != endRequestSerial))
          return;
        if (failure != null) {
          if (answer && cancelRequested)
            return;
          // A cancel/reject can lose the race to a remote answer. Clean up that
          // possible established dialog before reporting local control failure.
          if (!answer && !method.equals("hangup"))
            bestEffortEnd("hangup");
          ended(true);
          return;
        }
        String phase = EngineState.text(result, "state");
        if ("IDLE".equals(phase) || "ENDED".equals(phase))
          endWith(ImsReasonInfo.CODE_USER_TERMINATED);
        else if ((answer || cancelRequested) && phase != null)
          stage(phase);
        // Asynchronous accepted cancellation waits for the event/snapshot before ending.
      });
    });
  }
  private void bestEffortEnd(String method) {
    String token = id;
    if (token.isEmpty() || !ownsBinding())
      return;
    feature.rt.io.execute(() -> {
      try {
        if (!canControl())
          return;
        if (method.equals("hangup"))
          owner.engine.hangup(token);
        else
          owner.engine.cancelCall(token);
      } catch (Exception ignored) {
      }
    });
  }
  /** Called only on rt.worker, after feature-level binding and epoch/revision validation. */
  void stage(String phase) {
    if (ended || !ownsBinding())
      return;
    if ("ENDED".equals(phase) || "IDLE".equals(phase)) {
      ended(false);
      return;
    }
    owner.callActive = true;
    if ("IN_CALL".equals(phase)) {
      initiated = true;
      if (feature.pendingOutgoing == this)
        feature.pendingOutgoing = null;
      if (cancelRequested) {
        if (!lastEndMethod.equals("hangup"))
          endRequestInFlight = false;
        requestEnd();
        return;
      }
      state = State.ESTABLISHED;
      flushCallbacks();
      if (audio == null && canControl()) {
        PcmAudio attachment = new PcmAudio(
            feature.rt.context, owner.engine, id, feature.rt.io,
            e -> feature.rt.worker.execute(() -> audioFailed(e)));
        attachment.muted = muted;
        audio = attachment;
        feature.rt.io.execute(() -> {
          try {
            attachment.start();
          } catch (Exception ignored) {
          }
        });
      }
    } else if ("DIALING".equals(phase) || "CONNECTING".equals(phase)) {
      if (!cancelRequested && !initiated) {
        state = State.ESTABLISHING;
        progressing = true;
        flushCallbacks();
      }
    } else if ("RINGING".equals(phase)) {
      if (!cancelRequested)
        state = State.NEGOTIATING;
    } else if ("ENDING".equals(phase) || "CANCELING".equals(phase))
      state = State.TERMINATING;
  }
  private void audioFailed(Throwable error) {
    if (ended)
      return;
    bestEffortEnd(initiated ? "hangup" : "cancelCall");
    endWith(ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
  }
  void ended(boolean failed) {
    endWith(failed            ? ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE
            : cancelRequested ? ImsReasonInfo.CODE_USER_TERMINATED
                              : ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE);
  }
  private void endWith(int reason) {
    trace("end reason=" + reason);
    if (ended)
      return;
    ended = true;
    state = State.TERMINATED;
    terminalReason = RuntimeController.reason(reason);
    PcmAudio closingAudio = audio;
    audio = null;
    if (!id.isEmpty())
      feature.calls.remove(id, this);
    if (feature.pendingOutgoing == this)
      feature.pendingOutgoing = null;
    feature.rt.onCallEndedRefreshOverride(owner);
    // A local failure is not proof that the remote dialog ended. The feature clears
    // callActive from a versioned engine snapshot/terminal event, not this local close.
    // Pipe close / AudioRecord.stop can wait for an I/O thread. Never hold the
    // shared call-state worker (or the framework's terminal callback) behind it.
    if (closingAudio != null)
      feature.rt.io.execute(closingAudio::close);
    flushCallbacks();
  }
  private void flushCallbacks() {
    if (listener == null)
      return;
    try {
      if (!ended && dialStarted && !initiatingSent) {
        initiatingSent = true;
        listener.callSessionInitiating(profile);
        feature.callAudioReady();
      }
      if (!ended && progressing && !progressingSent) {
        progressingSent = true;
        listener.callSessionProgressing(profile.getMediaProfile());
        feature.callAudioReady();
      }
      if (!ended && initiated && !initiatedSent) {
        initiatedSent = true;
        listener.callSessionInitiated(profile);
        feature.callAudioReady();
      }
      if (ended && !terminalSent) {
        trace("terminalCallback");
        terminalSent = true;
        // Android clears mPendingMO on initiating/progressing. StartFailed can then be
        // ignored, leaving Telecom permanently DIALING even though our engine is idle.
        if (dialStarted && !initiatingSent && !progressingSent && !initiatedSent)
          listener.callSessionInitiatedFailed(terminalReason);
        else
          listener.callSessionTerminated(terminalReason);
        trace("terminalCallbackComplete");
      }
    } catch (RuntimeException error) {
      trace("callbackFailed=" + error.getClass().getSimpleName());
    }
  }
  public void setMute(boolean value) {
    feature.rt.worker.execute(() -> {
      muted = value;
      if (audio != null)
        audio.muted = value;
    });
  }
  public void sendDtmf(char digit, Message result) {
    feature.rt.worker.execute(() -> {
      if (ended || !initiated || !canControl())
        return;
      String token = id;
      feature.rt.io.execute(() -> {
        try {
          if (!canControl())
            return;
          owner.engine.sendDtmf(token, digit, 160);
          feature.rt.worker.execute(() -> {
            if (result != null)
              result.sendToTarget();
          });
        } catch (Exception ignored) { /* A DTMF failure must not terminate a healthy call. */
        }
      });
    });
  }
  public void startDtmf(char digit) {
    sendDtmf(digit, null);
  }
  public void stopDtmf() { /* Only bounded positive-duration DTMF is established by protocol v1. */
  }
  public void hold(ImsStreamMediaProfile p) {
    feature.rt.worker.execute(() -> {
      if (listener != null)
        listener.callSessionHoldFailed(
            RuntimeController.reason(ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE));
    });
  }
  public void resume(ImsStreamMediaProfile p) {
    feature.rt.worker.execute(() -> {
      if (listener != null)
        listener.callSessionResumeFailed(
            RuntimeController.reason(ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE));
    });
  }
  public void merge() {
    feature.rt.worker.execute(() -> {
      if (listener != null)
        listener.callSessionMergeFailed(
            RuntimeController.reason(ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE));
    });
  }
  public void close() {
    feature.rt.worker.execute(() -> {
      listener = null;
      endWith(ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
    });
  }
}
