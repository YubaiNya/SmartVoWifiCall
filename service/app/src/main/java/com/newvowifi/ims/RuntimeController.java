package com.newvowifi.ims;
import android.content.*;
import android.net.*;
import android.telephony.ims.*;
import android.telephony.ims.stub.*;
import com.newvowifi.ims.engine.*;
import com.newvowifi.ims.network.*;
import com.newvowifi.ims.policy.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

final class RuntimeController {
  private static RuntimeController instance;
  static synchronized RuntimeController get(Context c) {
    if (instance == null)
      instance = new RuntimeController(c.getApplicationContext());
    return instance;
  }
  final Context context;
  final RootSupervisor root;
  final SharedPreferences prefs;
  final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
  final ScheduledExecutorService policyWorker = Executors.newSingleThreadScheduledExecutor();
  final ExecutorService io = Executors.newCachedThreadPool();
  final ExecutorService binderExecutor = Executors.newCachedThreadPool();
  final java.util.concurrent.atomic.AtomicBoolean tickQueued =
      new java.util.concurrent.atomic.AtomicBoolean();
  final Map<Integer, Binding> bindings = new ConcurrentHashMap<>();
  final Map<Integer, PolicyEngine.AppliedOverride> applied = new LinkedHashMap<>();
  volatile RootSupervisor.Snapshot snapshot;
  volatile InterfaceSnapshot interfaces;
  volatile String status = "等待启动";
  volatile boolean started;
  final Map<Integer, NewMmTelFeature> features = new ConcurrentHashMap<>();
  final Map<String, ImsRegistrationImplBase> registrations = new ConcurrentHashMap<>();
  RuntimeController(Context c) {
    context = c;
    root = new RootSupervisor(c);
    prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("policy", 0);
    loadJournal();
  }
  boolean enabled() {
    return prefs.getBoolean("enabled", false);
  }
  boolean billable() {
    return prefs.getBoolean("billable", false);
  }
  void setEnabled(boolean v) {
    prefs.edit().putBoolean("enabled", v).commit();
    kick();
  }
  void select(String key, String iface) {
    prefs.edit().putString("selected." + key, iface).commit();
    kick();
  }
  String selection(String key) {
    return prefs.getString("selected." + key, "");
  }
  void setBillable(boolean v) {
    policyWorker.execute(() -> {
      if (snapshot == null || snapshot.callsActive
          || bindings.values().stream().anyMatch(b -> b.callActive))
        return;
      prefs.edit().putBoolean("billable", v).commit();
      for (Binding b : bindings.values()) b.close();
      bindings.clear();
      tick();
    });
  }
  synchronized void start() {
    if (started)
      return;
    started = true;
    policyWorker.scheduleWithFixedDelay(this::tick, 0, 5, TimeUnit.SECONDS);
  }
  void kick() {
    start();
    if (tickQueued.compareAndSet(false, true))
      policyWorker.execute(() -> {
        try {
          tick();
        } finally {
          tickQueued.set(false);
        }
      });
  }
  ImsRegistrationImplBase registration(int slot) {
    RootSupervisor.Snapshot current = snapshot;
    if (current != null) {
      for (RootSupervisor.Sim sim : current.sims)
        if (sim.policy.slotId == slot)
          return registrationFor(sim);
    }
    return registrations.computeIfAbsent(
        "unbound:" + slot, s -> new ImsRegistrationImplBase(binderExecutor));
  }
  ImsRegistrationImplBase registrationFor(RootSupervisor.Sim sim) {
    String key = sim.policy.slotId + ":" + sim.policy.subId + ":" + sim.policy.simKey;
    return registrations.computeIfAbsent(key, s -> new ImsRegistrationImplBase(binderExecutor));
  }
  boolean eligible(int slot) {
    RootSupervisor.Snapshot s = snapshot;
    return s != null
        && s.sims.stream().anyMatch(x
            -> x.policy.slotId == slot
                && PolicyEngine.eligibility(x.policy) == PolicyEngine.Reason.ELIGIBLE);
  }
  boolean matchesSubscription(int slot, int sub) {
    RootSupervisor.Snapshot current = snapshot;
    return current != null
        && current.sims.stream().anyMatch(s
            -> s.policy.slotId == slot && s.policy.subId == sub && s.policy.ready
                && PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE);
  }
  void loadJournal() {
    try {
      JSONArray a = new JSONArray(prefs.getString("journal", "[]"));
      for (int i = 0; i < a.length(); i++) {
        JSONObject j = a.getJSONObject(i);
        int slot = j.getInt("slot");
        applied.put(slot,
            new PolicyEngine.AppliedOverride(
                j.getString("key"), j.getInt("sub"), slot, j.getString("iface")));
      }
    } catch (Exception e) {
      status = "绑定记录读取失败";
    }
  }
  void saveJournal() throws Exception {
    JSONArray a = new JSONArray();
    for (PolicyEngine.AppliedOverride x : applied.values())
      a.put(new JSONObject()
                .put("slot", x.slotId)
                .put("sub", x.subId)
                .put("key", x.simKey)
                .put("iface", x.interfaceName));
    if (!prefs.edit().putString("journal", a.toString()).commit())
      throw new java.io.IOException("JOURNAL_WRITE_FAILED");
  }
  void tick() {
    try {
      snapshot = root.snapshot();
      NewVoWifiImsService.refreshFeatures();
      interfaces =
          new InterfaceInventory(context, () -> root.run(InterfaceParser.ROOT_LISTING_SCRIPT), root.vpnOwners())
              .read();
      Map<String, String> selected = new HashMap<>();
      List<PolicyEngine.Sim> sims = new ArrayList<>();
      for (RootSupervisor.Sim s : snapshot.sims) {
        if (!prefs.contains("selected." + s.policy.simKey)
            && interfaces.preferredVpnInterface != null
            && PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE)
          prefs.edit()
              .putString("selected." + s.policy.simKey, interfaces.preferredVpnInterface)
              .commit();
        selected.put(s.policy.simKey, selection(s.policy.simKey));
        if (enabled())
          sims.add(s.policy);
      }
      pollBindings();
      boolean calls =
          snapshot.callsActive || bindings.values().stream().anyMatch(b -> b.callActive);
      PolicyEngine.Plan plan = PolicyEngine.evaluate(sims, selected, interfaces, applied, calls);
      if (plan.deferredForCall) {
        status = "通话期间暂缓切换";
        return;
      }
      boolean candidateStarted = false;
      for (PolicyEngine.Action action : plan.actions) {
        if (action.kind == PolicyEngine.Kind.CLEAR) {
          clear(action);
          continue;
        }
        RootSupervisor.Sim sim =
            snapshot.sims.stream()
                .filter(s
                    -> s.policy.subId == action.subId && s.policy.slotId == action.slotId
                        && s.policy.simKey.equals(action.simKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("STALE_SIM"));
        String previous = root.currentOverride(action.slotId);
        if (!previous.isEmpty() && !previous.equals("com.newvowifi.ims"))
          throw new java.io.IOException("OTHER_CARRIER_OVERRIDE_PRESENT");
        // Persist intent before changing the remote state so process death remains recoverable.
        if (!prefs.edit()
                 .putString("previous." + action.slotId,
                     previous.equals("com.newvowifi.ims") ? "" : previous)
                 .putString("iccid." + action.slotId, sim.iccid)
                 .commit())
          throw new java.io.IOException("JOURNAL_WRITE_FAILED");
        applied.put(action.slotId, action.asAppliedOverride());
        try {
          saveJournal();
        } catch (Exception e) {
          applied.remove(action.slotId);
          throw e;
        }
        try {
          root.start(sim, action.interfaceName);
          connect(sim);
          candidateStarted = true;
        } catch (Exception failure) {
          clear(action);
          throw failure;
        }
      }
      // Reconnect persisted sessions after Android restarts the application process.
      for (PolicyEngine.AppliedOverride x : new ArrayList<>(applied.values())) {
        RootSupervisor.Sim sim =
            snapshot.sims.stream()
                .filter(s
                    -> s.policy.simKey.equals(x.simKey) && s.policy.subId == x.subId
                        && s.policy.slotId == x.slotId)
                .findFirst()
                .orElse(null);
        if (sim != null && !bindings.containsKey(x.slotId) && !calls) {
          try {
            root.start(sim, x.interfaceName);
            connect(sim);
            candidateStarted = true;
          } catch (Exception failure) {
            clearSlot(x.slotId);
            throw failure;
          }
        }
      }
      if (candidateStarted) pollBindings();
      synchronizeProviders();
      long selectedProviders = bindings.values().stream().filter(b -> b.providerBound).count();
      status = enabled() ? (bindings.isEmpty() ? "等待所选接口或海外 SIM"
          : selectedProviders == 0 ? "使用系统 IMS；等待 VoWiFi 注册或通话结束"
          : "服务运行中；接管 " + selectedProviders + " 张卡")
                         : "已停用，系统 IMS 已恢复";
    } catch (Exception e) {
      status = "待处理：" + safeCode(e);
    }
  }
  private void synchronizeProviders() throws Exception {
    boolean frameworkBusy = snapshot == null || snapshot.callsActive;
    for (Binding b : new ArrayList<>(bindings.values())) {
      if (bindings.get(b.slot()) != b) continue;
      RegistrationRouting.Result route = RegistrationRouting.synchronize(
          b.registered && b.engine.isConnected(), frameworkBusy, b.callActive,
          new RegistrationRouting.Backend() {
            public String current() throws Exception { return root.currentOverride(b.slot()); }
            public void selectIndependent() throws Exception {
              if (!b.registered || !b.engine.isConnected())
                throw new IllegalStateException("IMS_NOT_REGISTERED");
              root.bind(b.sim);
            }
            public void restoreSystem() throws Exception {
              root.restore(b.slot(), prefs.getString("previous." + b.slot(), ""));
            }
          });
      b.providerBound = route.independent;
      b.providerDeferred = route.deferred;
    }
  }
  static String safeCode(Throwable e) {
    String s = e.getMessage();
    return s != null && s.matches("[A-Z0-9_]{3,80}") ? s : e.getClass().getSimpleName();
  }
  void clear(PolicyEngine.Action a) throws Exception {
    clearSlot(a.slotId);
  }
  void clearSlot(int slot) throws Exception {
    root.restore(slot, prefs.getString("previous." + slot, ""));
    Binding old = bindings.remove(slot);
    if (old != null)
      old.close();
    (old == null ? registration(slot) : old.registration)
        .onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN));
    root.stop(prefs.getString("iccid." + slot, ""));
    applied.remove(slot);
    saveJournal();
  }
  void connect(RootSupervisor.Sim sim) throws Exception {
    Binding b = new Binding(sim);
    try {
      b.engine.connect();
      bindings.put(sim.policy.slotId, b);
    } catch (Exception error) {
      b.close();
      throw error;
    }
  }
  void pollBindings() {
    for (Binding b : new ArrayList<>(bindings.values())) {
      try {
        if (!b.engine.isConnected())
          b.engine.connect();
        Map<String, Object> state = b.engine.getStatus();
        b.ims = String.valueOf(state.get("ims"));
        boolean registered = "REGISTERED".equals(b.ims);
        if (registered != b.registered || !b.reported) {
          b.registered = registered;
          b.reported = true;
          if (registered)
            b.registration.onRegistered(ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
          else
            b.registration.onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED));
        }
        NewMmTelFeature f = features.get(b.slot());
        if (f != null)
          f.registrationChanged(registered);
        b.tunnel = EngineState.text(state, "tunnel");
        try {
          b.engine.getCallSnapshot();
          b.callSnapshotError = 0;
          Map<String, Object> call = b.engine.getState().callSnapshot;
          String stage = String.valueOf(call.get("stage"));
          if (f != null) worker.execute(() -> {
            if (bindings.get(b.slot()) == b) f.snapshot(call);
          });
          else b.callActive = !stage.equals("IDLE");
        } catch (EngineRpcClient.RpcException notReady) {
          // An application-level call readiness error does not invalidate the
          // registration status just read on this still-connected socket.
          b.callSnapshotError = notReady.code;
        } catch (EngineRpcClient.RequestTimeoutException unknown) {
          b.callSnapshotError = -1;
        }
      } catch (Exception e) {
        b.registered = false;
        b.reported = false;
        b.ims = "DISCONNECTED";
        b.registration.onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN));
        NewMmTelFeature f = features.get(b.slot());
        if (f != null)
          f.registrationChanged(false);
        try {
          if (!root.engineRunning(b.sim.iccid)) {
            b.callActive = false;
            if (f != null)
              worker.execute(f::engineDied);
            if (snapshot != null && !snapshot.callsActive)
              clearSlot(b.slot());
          }
        } catch (Exception recoveryError) {
          status = "待处理：" + safeCode(recoveryError);
        }
      }
    }
  }
  static ImsReasonInfo reason(int code) {
    return new ImsReasonInfo(code, 0, "");
  }
  JSONObject diagnostics() {
    JSONObject out = new JSONObject();
    try {
      out.put("enabled", enabled()).put("billableAllowed", billable()).put("status", status);
      JSONArray sims = new JSONArray();
      RootSupervisor.Snapshot snap = snapshot;
      if (snap != null) for (RootSupervisor.Sim s : snap.sims) {
        JSONObject row = new JSONObject().put("slot", s.policy.slotId).put("subId", s.policy.subId)
            .put("ready", s.policy.ready).put("homePlmn", s.policy.homePlmn)
            .put("selectedInterface", selection(s.policy.simKey));
        Binding b = bindings.get(s.policy.slotId);
        if (b != null) row.put("ims", b.ims).put("registered", b.registered)
            .put("tunnel", b.tunnel).put("callActive", b.callActive)
            .put("callSnapshotError", b.callSnapshotError)
            .put("independentProvider", b.providerBound).put("providerSwitchDeferred", b.providerDeferred);
        sims.put(row);
      }
      out.put("sims", sims);
      JSONArray nets = new JSONArray(); InterfaceSnapshot inventory = interfaces;
      if (inventory != null) {
        out.put("preferredVpnInterface", inventory.preferredVpnInterface);
        for (InterfaceSnapshot.Entry e : inventory.entries.values()) nets.put(new JSONObject()
            .put("name",e.name).put("up",e.up).put("available",e.available())
            .put("type",e.type()).put("owners",new JSONArray(e.ownerPackages)));
      }
      out.put("interfaces", nets);
    } catch (Exception ignored) { }
    return out;
  }
  final class Binding implements EngineSession.Listener {
    final RootSupervisor.Sim sim;
    final EngineSession engine;
    final ImsRegistrationImplBase registration;
    volatile boolean registered, reported, callActive, providerBound, providerDeferred;
    volatile String ims = "CONNECTING";
    volatile String tunnel = "UNKNOWN";
    volatile long callSnapshotError;
    Binding(RootSupervisor.Sim sim) {
      this.sim = sim;
      registration = registrationFor(sim);
      engine = new EngineSession(sim.identity(), new AndroidTransport(context.getApplicationInfo().sourceDir), this, billable());
    }
    int slot() {
      return sim.policy.slotId;
    }
    public void onEvent(EngineSession.Identity identity, EngineState.Event event) {
      worker.execute(() -> {
        if (bindings.get(slot()) != this)
          return;
        NewMmTelFeature f = features.get(slot());
        if (f != null)
          f.event(event);
      });
    }
    public void onDisconnected(EngineSession.Identity identity, Throwable why) {
      worker.execute(() -> {
        if (bindings.get(slot()) == this) {
          registered = false;
          reported = false;
          registration.onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN));
          NewMmTelFeature f = features.get(slot());
          if (f != null)
            f.registrationChanged(false);
        }
      });
    }
    void close() {
      engine.close();
    }
  }
}
