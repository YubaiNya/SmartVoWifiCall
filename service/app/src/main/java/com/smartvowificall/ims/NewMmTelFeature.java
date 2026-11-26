package com.smartvowificall.ims;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.telephony.ims.*;
import android.telephony.ims.feature.*;
import android.telephony.ims.stub.*;
import com.smartvowificall.ims.engine.*;
import java.util.*;
final class NewMmTelFeature extends MmTelFeature {
  final RuntimeController rt;
  final int slot;
  final int subId;
  final String simKey;
  final NewSms sms;
  final Map<String, NewCallSession> calls = new java.util.concurrent.ConcurrentHashMap<>();
  volatile NewCallSession pendingOutgoing;
  private EngineState.Version appliedCallVersion;
  private volatile boolean ready, registered;
  private int publishedCaps = -1;
  private volatile int enabledCaps =
      MmTelCapabilities.CAPABILITY_TYPE_VOICE | MmTelCapabilities.CAPABILITY_TYPE_SMS;
  NewMmTelFeature(RuntimeController rt, int slot) {
    super(rt.binderExecutor);
    this.rt = rt;
    this.slot = slot;
    RootSupervisor.Sim current = rt.snapshot.sims.stream()
                                     .filter(s -> s.policy.slotId == slot)
                                     .findFirst()
                                     .orElseThrow(IllegalStateException::new);
    this.subId = current.policy.subId;
    this.simKey = current.policy.simKey;
    this.sms = new NewSms(this);
    // The implementation is ready to accept a framework listener. Remaining
    // unregistered is a separate IMS registration state. Waiting until
    // onFeatureReady() to publish READY prevents the framework attaching that
    // listener and therefore prevents onFeatureReady() itself.
    setFeatureState(STATE_READY);
  }
  RuntimeController.Binding binding() {
    RuntimeController.Binding b = rt.bindings.get(slot);
    return b != null && b.sim.policy.subId == subId && b.sim.policy.simKey.equals(simKey) ? b
                                                                                          : null;
  }
  public synchronized void onFeatureReady() {
    publishedCaps = -1;
    ready = true;
    setFeatureState(STATE_READY);
    RuntimeController.Binding b = binding();
    if (b != null) {
      b.reported = false;
      registrationChanged(b.registered);
    }
    rt.kick();
  }
  void callAudioReady() {
    if (!ready) return;
    try {
      // The framework discards this notification before it has a call connection.
      setCallAudioHandler(AUDIO_HANDLER_ANDROID);
    } catch (RuntimeException error) {
      android.util.Log.i("SmartVoWifiCall", "audioHandler=" + error.getClass().getSimpleName());
    }
  }
  public void onFeatureRemoved() {
    ready = false;
    rt.features.remove(slot, this);
    setFeatureState(STATE_UNAVAILABLE);
    for (NewCallSession s : calls.values()) s.close();
    if (pendingOutgoing != null)
      pendingOutgoing.close();
    calls.clear();
  }
  void engineDied() {
    for (NewCallSession s : new ArrayList<>(calls.values())) s.ended(true);
    if (pendingOutgoing != null)
      pendingOutgoing.ended(true);
  }
  synchronized void registrationChanged(boolean value) {
    registered = value;
    int mask = value && rt.billable(slot) ? enabledCaps : 0;
    if (ready && mask != publishedCaps) {
      MmTelCapabilities caps = new MmTelCapabilities();
      caps.addCapabilities(mask);
      notifyCapabilitiesStatusChanged(caps);
      publishedCaps = mask;
    }
  }
  public boolean queryCapabilityConfiguration(int c, int tech) {
    return tech == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
        && (c == MmTelCapabilities.CAPABILITY_TYPE_VOICE
            || c == MmTelCapabilities.CAPABILITY_TYPE_SMS)
        && (enabledCaps & c) != 0;
  }
  public void changeEnabledCapabilities(CapabilityChangeRequest r, CapabilityCallbackProxy cb) {
    for (CapabilityChangeRequest.CapabilityPair p : r.getCapabilitiesToDisable())
      if (p.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN)
        enabledCaps &= ~p.getCapability();
    for (CapabilityChangeRequest.CapabilityPair p : r.getCapabilitiesToEnable()) {
      if (p.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
          && (p.getCapability() == MmTelCapabilities.CAPABILITY_TYPE_VOICE
              || p.getCapability() == MmTelCapabilities.CAPABILITY_TYPE_SMS))
        enabledCaps |= p.getCapability();
      else
        cb.onChangeCapabilityConfigurationError(
            p.getCapability(), p.getRadioTech(), CAPABILITY_ERROR_GENERIC);
    }
    registrationChanged(registered);
  }
  public ImsCallProfile createCallProfile(int service, int type) {
    return new ImsCallProfile(service, type);
  }
  public ImsCallSessionImplBase createCallSession(ImsCallProfile p) {
    return new NewCallSession(this, p);
  }
  public ImsSmsImplBase getSmsImplementation() {
    return sms;
  }
  public int shouldProcessCall(String[] numbers) {
    TelephonyManager tm = rt.context.getSystemService(TelephonyManager.class);
    if (numbers != null)
      for (String n : numbers) try {
          if (tm.isEmergencyNumber(n))
            return PROCESS_CALL_CSFB;
        } catch (RuntimeException e) {
          // Unknown classification must fail inside our session, not reroute a normal call.
          return PROCESS_CALL_IMS;
        }
    // Ordinary calls remain here even when unavailable; NewCallSession reports failure.
    return PROCESS_CALL_IMS;
  }
  void event(EngineState.Event e) {
    if (e.name.equals("smsReceived")) {
      sms.received(e.data);
      return;
    }
    if (e.name.equals("incomingCall") || e.name.equals("callState") || e.name.equals("callEnded")) {
      if (e.name.equals("callEnded") || "ENDED".equals(EngineState.text(e.data, "state")))
        terminalEvent(e.data);
      // An event can be queued before a newer poll result. Resynchronize from the
      // versioned authoritative snapshot. A verified terminal event is consumed
      // immediately: getCallSnapshot can temporarily return 2006 during teardown.
      RuntimeController.Binding b = binding();
      if (b == null)
        return;
      rt.io.execute(() -> {
        try {
          b.engine.getCallSnapshot();
          Map<String, Object> current = b.engine.getState().callSnapshot;
          rt.worker.execute(() -> {
            if (binding() == b)
              snapshot(current);
          });
        } catch (Exception ignored) {
        }
      });
    }
  }
  private void terminalEvent(Map<String, Object> data) {
    RuntimeController.Binding b = binding();
    if (b == null) return;
    String id = EngineState.text(data, "callId");
    NewCallSession session = id == null ? null : calls.get(id);
    if (session == null || session.owner != b) return;
    EngineState.Version version;
    try { version = EngineState.Version.parse(data); } catch (Exception ignored) { return; }
    EngineState.Version latest = b.engine.getState().callVersion;
    if (latest == null || !version.epoch.equals(latest.epoch) || version.isOlderThan(latest)
        || (appliedCallVersion != null && (!version.epoch.equals(appliedCallVersion.epoch)
            || version.isOlderThan(appliedCallVersion)))) return;
    appliedCallVersion = version;
    session.ended(false);
    b.callActive = !calls.isEmpty() || pendingOutgoing != null;
  }
  void snapshot(Map<String, Object> data) {
    RuntimeController.Binding b = binding();
    if (b == null)
      return;
    EngineState.Version version;
    try {
      version = EngineState.Version.parse(data);
    } catch (Exception e) {
      return;
    }
    EngineState.Version current = b.engine.getState().callVersion;
    if (current == null || !version.epoch.equals(current.epoch)
        || version.revision < current.revision)
      return;
    if (appliedCallVersion != null) {
      if (version.isOlderThan(appliedCallVersion))
        return;
      if (!version.epoch.equals(appliedCallVersion.epoch)) {
        for (NewCallSession c : new ArrayList<>(calls.values())) c.ended(true);
        if (pendingOutgoing != null)
          pendingOutgoing.ended(true);
      }
    }
    appliedCallVersion = version;
    String id = EngineState.text(data, "callId"), stage = EngineState.text(data, "stage");
    // The engine exposes one authoritative call. A newer different call or an
    // IDLE snapshot also retires an older session whose terminal event was lost.
    if ("IDLE".equals(stage) || (id != null && !id.isEmpty()))
      for (NewCallSession c : new ArrayList<>(calls.values()))
        if ("IDLE".equals(stage) || !c.id.equals(id)) c.ended(false);
    if (id != null && "INCOMING".equals(data.get("direction")) && "RINGING".equals(stage))
      incoming(id, EngineState.text(data, "peer"));
    if (id != null && "OUTGOING".equals(data.get("direction")) && !calls.containsKey(id)
        && pendingOutgoing != null)
      pendingOutgoing.bindCallId(id);
    if (!"IDLE".equals(stage) && id != null && calls.containsKey(id))
      calls.get(id).stage(stage);
    b.callActive = !"IDLE".equals(stage) || pendingOutgoing != null;
  }
  void incoming(String id, String peer) {
    if (!ready || !rt.billable(slot) || calls.containsKey(id))
      return;
    ImsCallProfile p =
        new ImsCallProfile(ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE);
    // The native peer contract already filters Privacy/anonymous/non-numeric
    // identities. A missing public peer must remain unknown, not be reconstructed.
    boolean publicNumber = peer != null && peer.matches("\\+?[0-9]{1,20}");
    p.setCallExtra(ImsCallProfile.EXTRA_OI, publicNumber ? peer : "");
    p.setCallExtraInt(ImsCallProfile.EXTRA_OIR, publicNumber
        ? ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED : ImsCallProfile.OIR_PRESENTATION_UNKNOWN);
    NewCallSession c = new NewCallSession(this, p);
    c.id = id;
    calls.put(id, c);
    c.stage("RINGING");
    try {
      ImsCallSessionListener listener = notifyIncomingCall(c, id, new Bundle());
      if (listener != null) {
        c.setListener(listener);
        callAudioReady();
      } else c.reject(0);
    } catch (RuntimeException error) {
      c.reject(0);
    }
  }
}
