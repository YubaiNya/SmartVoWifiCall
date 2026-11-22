package com.smartvowificall.ims;
import android.content.*;
import android.net.*;
import android.telephony.ims.*;
import android.telephony.ims.stub.*;
import com.smartvowificall.ims.clash.*;
import com.smartvowificall.ims.engine.*;
import com.smartvowificall.ims.network.*;
import com.smartvowificall.ims.policy.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
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
  volatile Map<Integer, Boolean> radioOff = Collections.emptyMap();
  final Map<Integer, Long> lastRebindNanos = new HashMap<>();
  final FallbackPolicy fallback = new FallbackPolicy();
  final Set<Integer> fallbackReleasedSlots = new HashSet<>();
  volatile Map<Integer, String> fallbackReasons = Collections.emptyMap();
  final Map<Integer, PolicyEngine.AppliedOverride> applied = new LinkedHashMap<>();
  volatile RootSupervisor.Snapshot snapshot;
  volatile InterfaceSnapshot interfaces;
  volatile ClashState clash = ClashState.EMPTY;
  volatile String status = "等待启动";
  volatile boolean started;
  final Map<Integer, NewMmTelFeature> features = new ConcurrentHashMap<>();
  final Map<Integer, ImsRegistrationImplBase> registrations = new ConcurrentHashMap<>();
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
  boolean clashEnabled() {
    return prefs.getBoolean("clash_enabled", false);
  }
  void setClashEnabled(boolean v) {
    prefs.edit().putBoolean("clash_enabled", v).commit();
    kick();
  }
  void useBuiltinTunForForeignSims() {
    RootSupervisor.Snapshot snap = snapshot;
    if (snap == null) return;
    SharedPreferences.Editor ed = prefs.edit();
    for (RootSupervisor.Sim s : snap.sims) {
      if (PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE)
        ed.putString("selected." + s.policy.simKey, ClashConfig.TUN_DEVICE);
    }
    if (clash.hasProfile) ed.putBoolean("clash_enabled", true);
    ed.commit();
    fallback.reset();
    kick();
  }
  void clashFetchSubscription(String url, Consumer<String> done) {
    io.execute(() -> {
      try {
        JSONObject r = root.clashFetchSubscription(url);
        prefs.edit().putBoolean("clash_enabled", true).commit();
        try { root.clashRestart(); } catch (Exception ignored) {}
        refreshClashState();
        kick();
        done.accept("订阅已更新（节点 " + r.optInt("proxyCount") + "，provider " + r.optInt("providerCount") + "）");
      } catch (Exception e) {
        done.accept("拉取失败：" + safeCode(e));
      }
    });
  }
  void clashSaveProfile(String yaml, Consumer<String> done) {
    io.execute(() -> {
      try {
        JSONObject r = root.clashSaveProfile(yaml);
        prefs.edit().putBoolean("clash_enabled", true).commit();
        try { root.clashRestart(); } catch (Exception ignored) {}
        refreshClashState();
        kick();
        done.accept("配置已导入（节点 " + r.optInt("proxyCount") + "，provider " + r.optInt("providerCount") + "）");
      } catch (Exception e) {
        done.accept("导入失败：" + safeCode(e));
      }
    });
  }
  void clashReloadLocal(Consumer<String> done) {
    io.execute(() -> {
      try {
        JSONObject r = root.clashRebuild();
        if (clashEnabled()) root.clashRestart();
        refreshClashState();
        kick();
        done.accept("已重载本地配置（节点 " + r.optInt("proxyCount") + "，provider " + r.optInt("providerCount") + "）");
      } catch (Exception e) {
        done.accept("重载失败：" + safeCode(e));
      }
    });
  }
  void clashSelectNode(String node, Consumer<String> done) {
    io.execute(() -> {
      try {
        root.clashSelectNode(node);
        refreshClashState();
        done.accept("已切换出口节点：" + node);
      } catch (Exception e) {
        done.accept("切换失败：" + safeCode(e));
      }
    });
  }
  void clashTestDelay(Consumer<String> done) {
    io.execute(() -> {
      try {
        JSONObject r = root.clashTestDelay();
        JSONObject delays = r.optJSONObject("delays");
        int okCount = delays == null ? 0 : delays.length();
        refreshClashState();
        done.accept("测速完成（可用节点 " + okCount + " 个）");
      } catch (Exception e) {
        done.accept("测速失败：" + safeCode(e));
      }
    });
  }
  void clashRestartCore(Consumer<String> done) {
    io.execute(() -> {
      try {
        root.clashRestart();
        refreshClashState();
        kick();
        done.accept("内置 Clash 已重启");
      } catch (Exception e) {
        done.accept("重启失败：" + safeCode(e));
      }
    });
  }
  void clashSetController(boolean externalLan, int port, Consumer<String> done) {
    io.execute(() -> {
      try {
        root.clashSetController(externalLan, port);
        refreshClashState();
        kick();
        done.accept(externalLan
            ? "外部控制已开启（0.0.0.0:" + port + "）"
            : "外部控制已限制为仅本机（127.0.0.1:" + port + "）");
      } catch (Exception e) {
        done.accept("保存外部控制失败：" + safeCode(e));
      }
    });
  }
  void clashRotateSecret(Consumer<String> done) {
    io.execute(() -> {
      try {
        root.clashRotateSecret();
        refreshClashState();
        kick();
        done.accept("控制器密钥已重置并生效");
      } catch (Exception e) {
        done.accept("重置密钥失败：" + safeCode(e));
      }
    });
  }
  void clashFetchConnections(Consumer<ConnectionsSnapshot> done) {
    io.execute(() -> {
      try {
        done.accept(ConnectionsSnapshot.fromJson(root.clashConnections(), ""));
      } catch (Exception e) {
        done.accept(ConnectionsSnapshot.error(safeCode(e)));
      }
    });
  }
  void clashCloseConnection(String id, Consumer<String> done) {
    io.execute(() -> {
      try {
        root.clashCloseConnection(id);
        done.accept("已断开该连接");
      } catch (Exception e) {
        done.accept("断开失败：" + safeCode(e));
      }
    });
  }
  void clashCloseAllConnections(Consumer<String> done) {
    io.execute(() -> {
      try {
        root.clashCloseAllConnections();
        done.accept("已断开全部活跃连接");
      } catch (Exception e) {
        done.accept("断开失败：" + safeCode(e));
      }
    });
  }
  void refreshClashState() {
    try {
      clash = ClashState.fromJson(root.clashStatus(), "");
    } catch (Exception e) {
      clash = clash.withError(safeCode(e));
    }
  }
  private void syncClash(boolean callsActive) {
    try {
      ClashState current = ClashState.fromJson(root.clashStatus(), "");
      if (clashEnabled()) {
        if (current.hasProfile && (!current.running || !current.tunUp)) {
          root.clashStart();
          current = ClashState.fromJson(root.clashStatus(), "");
        }
      } else if (current.running && !callsActive) {
        root.clashStop();
        current = ClashState.fromJson(root.clashStatus(), "");
      }
      clash = current;
    } catch (Exception e) {
      clash = clash.withError(safeCode(e));
    }
  }
  boolean radioReady(Binding binding) {
    return binding != null && Boolean.TRUE.equals(radioOff.get(binding.slot()));
  }
  void verifyRadioOff(Binding binding) throws Exception {
    if (!enabled() || !radioReady(binding)) throw new IllegalStateException("CELLULAR_RADIO_NOT_OFF");
    root.verifyRadioOff(binding.sim);
  }
  void setEnabled(boolean v) {
    prefs.edit().putBoolean("enabled", v).commit();
    fallback.reset();
    kick();
  }
  void select(String key, String iface) {
    SharedPreferences.Editor ed = prefs.edit().putString("selected." + key, iface);
    if (ClashConfig.TUN_DEVICE.equals(iface) && clash.hasProfile)
      ed.putBoolean("clash_enabled", true);
    ed.commit();
    fallback.reset();
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
    return registrations.computeIfAbsent(
        slot, s -> new ImsRegistrationImplBase(binderExecutor));
  }
  ImsRegistrationImplBase registrationFor(RootSupervisor.Sim sim) {
    return registration(sim.policy.slotId);
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
      if (!enabled() && (snapshot.callsActive || bindings.values().stream().anyMatch(b -> b.callActive))) {
        pollBindings();
        status = "通话期间暂缓切换至蜂窝模式";
        return;
      }
      boolean busyPre = snapshot.callsActive || bindings.values().stream().anyMatch(b -> b.callActive);
      syncClash(busyPre);
      interfaces =
          new InterfaceInventory(context, () -> root.run(InterfaceParser.ROOT_LISTING_SCRIPT), root.vpnOwners())
              .read();
      for (RootSupervisor.Sim s : snapshot.sims) {
        if (!prefs.contains("selected." + s.policy.simKey)
            && interfaces.preferredVpnInterface != null
            && PolicyEngine.eligibility(s.policy) == PolicyEngine.Reason.ELIGIBLE)
          prefs.edit()
              .putString("selected." + s.policy.simKey, interfaces.preferredVpnInterface)
              .commit();
      }
      pollBindings();
      boolean calls =
          snapshot.callsActive || bindings.values().stream().anyMatch(b -> b.callActive);
      if (!calls) {
        for (Binding b : new ArrayList<>(bindings.values())) {
          if (ClashConfig.TUN_DEVICE.equals(b.iface)
              && (!clash.tunUp || clash.netnsId.isEmpty() || !clash.netnsId.equals(b.clashNetnsId))) {
            clearSlot(b.slot());
          }
        }
      }
      Map<Integer, Boolean> nextRadioOff = new HashMap<>();
      if (enabled()) {
        // Fallback first: a SIM whose VoWiFi path is unusable must not keep its cellular radio off.
        long fallbackNow = System.nanoTime();
        Map<Integer, String> blocked = new HashMap<>();
        boolean releaseNeeded = false;
        for (RootSupervisor.Sim sim : snapshot.sims) {
          if (PolicyEngine.eligibility(sim.policy) != PolicyEngine.Reason.ELIGIBLE) continue;
          int slotId = sim.policy.slotId;
          boolean held = Boolean.TRUE.equals(radioOff.get(slotId));
          Binding current = bindings.get(slotId);
          String sel = selection(sim.policy.simKey);
          boolean clashExplicitlyOff = ClashConfig.TUN_DEVICE.equals(sel) && !clashEnabled();
          String block = fallback.evaluate(slotId, calls, held && !clashExplicitlyOff,
              !sel.isEmpty(), egressOf(sim), current != null,
              current != null && current.registered, fallbackNow);
          if (block != null) {
            blocked.put(slotId, block);
            if (held || !fallbackReleasedSlots.contains(slotId)) releaseNeeded = true;
          }
        }
        fallbackReasons = Collections.unmodifiableMap(blocked);
        for (RootSupervisor.Sim sim : snapshot.sims) {
          if (PolicyEngine.eligibility(sim.policy) != PolicyEngine.Reason.ELIGIBLE) continue;
          int slotId = sim.policy.slotId;
          if (blocked.containsKey(slotId)) {
            if (!calls) {
              if (bindings.containsKey(slotId) || applied.containsKey(slotId)) {
                clearSlot(slotId);
              }
              deregisterAndRestoreSlot(sim);
            }
            continue;
          }
          fallbackReleasedSlots.remove(slotId);
          boolean off = root.holdRadio(sim);
          nextRadioOff.put(slotId, off);
          // Reserve this card's ordinary IMS provider even while TUN/registration is absent.
          // Also re-nudge ImsResolver if it queried empty features before our first snapshot
          // (e.g., immediately after boot or APK upgrade) so MmTelFeature attaches.
          long now = System.nanoTime();
          Long previousRebind = lastRebindNanos.get(slotId);
          boolean needsFeature = !features.containsKey(slotId)
              && (previousRebind == null || now - previousRebind >= TimeUnit.SECONDS.toNanos(15));
          boolean alreadyBound = RegistrationRouting.OWN_PACKAGE.equals(root.currentOverride(slotId));
          if (off && !calls && (!alreadyBound || needsFeature)) {
            lastRebindNanos.put(slotId, now);
            if (alreadyBound && needsFeature) {
              // Xiaomi's ImsServiceController ignores updates when mImsFeatures is unchanged
              // after process death/force-stop; cycle through restore before rebinding.
              try {
                root.restore(slotId, prefs.getString("previous." + slotId, ""));
              } catch (Exception ignored) {}
            }
            root.bind(sim);
          }
        }
        if (releaseNeeded && !calls) {
          root.releaseRadios();
          fallbackReleasedSlots.addAll(blocked.keySet());
        }
      } else {
        fallbackReasons = Collections.emptyMap();
        fallbackReleasedSlots.clear();
      }
      radioOff = Collections.unmodifiableMap(nextRadioOff);
      SmartVoWifiImsService.refreshFeatures();
      Map<String, String> selected = new HashMap<>();
      List<PolicyEngine.Sim> sims = new ArrayList<>();
      for (RootSupervisor.Sim s : snapshot.sims) {
        selected.put(s.policy.simKey, selection(s.policy.simKey));
        if (enabled() && Boolean.TRUE.equals(radioOff.get(s.policy.slotId)))
          sims.add(s.policy);
      }
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
        if (!previous.isEmpty() && !previous.equals("com.smartvowificall.ims"))
          throw new java.io.IOException("OTHER_CARRIER_OVERRIDE_PRESENT");
        // Persist intent before changing the remote state so process death remains recoverable.
        if (!prefs.edit()
                 .putString("previous." + action.slotId,
                     previous.equals("com.smartvowificall.ims") ? "" : previous)
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
          connect(sim, action.interfaceName,
              ClashConfig.TUN_DEVICE.equals(action.interfaceName) ? clash.netnsId : "");
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
            connect(sim, x.interfaceName,
                ClashConfig.TUN_DEVICE.equals(x.interfaceName) ? clash.netnsId : "");
            candidateStarted = true;
          } catch (Exception failure) {
            clearSlot(x.slotId);
            throw failure;
          }
        }
      }
      if (candidateStarted) pollBindings();
      synchronizeProviders();
      if (!enabled()) {
        for (RootSupervisor.Sim sim : snapshot.sims) {
          deregisterAndRestoreSlot(sim);
        }
        root.restoreAll();
        root.releaseRadios();
      }
      long selectedProviders = bindings.values().stream().filter(b -> b.providerBound).count();
      boolean tunRequired = plan.decisions.stream().anyMatch(d -> d.reason == PolicyEngine.Reason.TUN_REQUIRED);
      boolean radioPending = radioOff.values().stream().anyMatch(value -> !value);
      status = enabled() ? (!fallbackReasons.isEmpty()
              ? "VoWiFi 暂不可用（" + FallbackPolicy.describe(fallbackReasons.values().iterator().next())
                  + "）；已回退蜂窝"
          : radioPending ? "切换中：等待目标卡蜂窝射频关闭"
          : radioOff.isEmpty() ? "等待可识别的海外 SIM"
          : tunRequired ? "蜂窝已关闭；请选择 TUN 作为 VoWiFi 出口"
          : selectedProviders > 0 ? "Wi-Fi Calling 模式；蜂窝已关闭"
          : "蜂窝已关闭；等待所选 TUN 或 VoWiFi 注册")
                         : "蜂窝模式；已恢复进入 Wi-Fi Calling 前的射频状态";
    } catch (Exception e) {
      radioOff = Collections.emptyMap();
      for (Binding b : bindings.values()) {
        b.registered = false;
        b.reported = false;
        syncVoiceServiceOverride(b, false);
        b.registration.onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE));
        NewMmTelFeature feature = features.get(b.slot());
        if (feature != null) feature.registrationChanged(false);
      }
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
  FallbackPolicy.Egress egressOf(RootSupervisor.Sim sim) {
    InterfaceSnapshot inventory = interfaces;
    String selected = selection(sim.policy.simKey);
    if (inventory == null || selected.isEmpty()) return FallbackPolicy.Egress.UNKNOWN;
    InterfaceSnapshot.Selection s = inventory.selection(selected);
    if (!s.available()) return FallbackPolicy.Egress.UNAVAILABLE;
    return s.entry != null && s.entry.voWifiTun() ? FallbackPolicy.Egress.AVAILABLE_TUN
                                                   : FallbackPolicy.Egress.AVAILABLE_NOT_TUN;
  }
  private void deregisterAndRestoreSlot(RootSupervisor.Sim sim) {
    int slot = sim.policy.slotId;
    try {
      root.setVoiceServiceOverride(sim, false);
    } catch (Exception ignored) {}
    NewMmTelFeature f = features.get(slot);
    if (f != null) f.registrationChanged(false);
    registration(slot).onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED));
    try {
      if (RegistrationRouting.OWN_PACKAGE.equals(root.currentOverride(slot))) {
        root.restore(slot, prefs.getString("previous." + slot, ""));
      }
    } catch (Exception ignored) {}
  }
  void clearSlot(int slot) throws Exception {
    Binding old = bindings.remove(slot);
    if (old != null) {
      syncVoiceServiceOverride(old, false);
      old.close();
    }
    // Always clear MMTEL capabilities and notify onDeregistered BEFORE unbinding our ImsService,
    // otherwise Android's ImsPhone disconnects callbacks early and leaves the VoWiFi icon stuck.
    NewMmTelFeature clearedFeature = features.get(slot);
    if (clearedFeature != null) clearedFeature.registrationChanged(false);
    (old == null ? registration(slot) : old.registration)
        .onDeregistered(reason(ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN));
    boolean keepProvider = enabled() && Boolean.TRUE.equals(radioOff.get(slot))
        && !fallbackReasons.containsKey(slot);
    if (!keepProvider) root.restore(slot, prefs.getString("previous." + slot, ""));
    root.stop(prefs.getString("iccid." + slot, ""));
    applied.remove(slot);
    saveJournal();
  }
  void connect(RootSupervisor.Sim sim, String iface, String clashNetnsId) throws Exception {
    Binding b = new Binding(sim, iface, clashNetnsId);
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
        boolean registered = "REGISTERED".equals(b.ims) && radioReady(b);
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
        syncVoiceServiceOverride(b, registered && billable());
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
        syncVoiceServiceOverride(b, false);
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
  private void syncVoiceServiceOverride(Binding b, boolean hasService) {
    if (b == null) return;
    if (Boolean.valueOf(hasService).equals(b.voiceServiceOverride)) return;
    try {
      root.setVoiceServiceOverride(b.sim, hasService);
      b.voiceServiceOverride = hasService;
    } catch (Exception ignored) {
      // Retried automatically on the next policy tick.
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
            .put("selectedInterface", selection(s.policy.simKey))
            .put("radioOff", Boolean.TRUE.equals(radioOff.get(s.policy.slotId)))
            .put("featureAttached", features.containsKey(s.policy.slotId));
        Binding b = bindings.get(s.policy.slotId);
        if (b != null) row.put("ims", b.ims).put("registered", b.registered)
            .put("tunnel", b.tunnel).put("callActive", b.callActive)
            .put("callSnapshotError", b.callSnapshotError)
            .put("voiceServiceOverride", Boolean.TRUE.equals(b.voiceServiceOverride))
            .put("independentProvider", b.providerBound).put("providerSwitchDeferred", b.providerDeferred);
        sims.put(row);
      }
      out.put("sims", sims);
      JSONArray nets = new JSONArray(); InterfaceSnapshot inventory = interfaces;
      if (inventory != null) {
        out.put("preferredVpnInterface", inventory.preferredVpnInterface);
        for (InterfaceSnapshot.Entry e : inventory.entries.values()) nets.put(new JSONObject()
            .put("name",e.name).put("up",e.up).put("available",e.available())
            .put("type",e.type()).put("voWifiTun",e.voWifiTun()).put("owners",new JSONArray(e.ownerPackages)));
      }
      out.put("interfaces", nets);
      ClashState cs = clash;
      out.put("clash", new JSONObject()
          .put("enabled", clashEnabled())
          .put("hasProfile", cs.hasProfile)
          .put("running", cs.running)
          .put("tunUp", cs.tunUp)
          .put("device", ClashConfig.TUN_DEVICE)
          .put("netnsId", cs.netnsId)
          .put("externalLan", cs.externalLan)
          .put("controllerPort", cs.controllerPort)
          .put("currentNode", cs.currentNode)
          .put("nodeCount", cs.nodes.size())
          .put("lastError", cs.lastError));
    } catch (Exception ignored) { }
    return out;
  }
  final class Binding implements EngineSession.Listener {
    final RootSupervisor.Sim sim;
    final String iface, clashNetnsId;
    final EngineSession engine;
    final ImsRegistrationImplBase registration;
    volatile boolean registered, reported, callActive, providerBound, providerDeferred;
    volatile Boolean voiceServiceOverride;
    volatile String ims = "CONNECTING";
    volatile String tunnel = "UNKNOWN";
    volatile long callSnapshotError;
    Binding(RootSupervisor.Sim sim, String iface, String clashNetnsId) {
      this.sim = sim;
      this.iface = iface == null ? "" : iface;
      this.clashNetnsId = clashNetnsId == null ? "" : clashNetnsId;
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
          io.execute(() -> syncVoiceServiceOverride(this, false));
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
  static final class ClashNode {
    final String name, type;
    final int delay;
    ClashNode(String name, String type, int delay) {
      this.name = name; this.type = type; this.delay = delay;
    }
    String label() {
      StringBuilder sb = new StringBuilder(name);
      if (!type.isEmpty()) sb.append(" [").append(type).append("]");
      if (delay > 0) sb.append(" · ").append(delay).append("ms");
      return sb.toString();
    }
  }
  static final class ClashState {
    static final ClashState EMPTY = new ClashState(false, false, false, 0, "", "", "", "",
        false, ClashConfig.CONTROLLER_PORT, "", "", Collections.emptyList(), "");
    final boolean hasProfile, running, tunUp, externalLan;
    final int controllerPort;
    final long profileBytes;
    final String subscriptionUrl, savedNode, currentNode, netnsId, controllerSecret, lanIpv4, lastError;
    final List<ClashNode> nodes;
    ClashState(boolean hasProfile, boolean running, boolean tunUp, long profileBytes,
        String subscriptionUrl, String savedNode, String currentNode, String netnsId,
        boolean externalLan, int controllerPort, String controllerSecret, String lanIpv4,
        List<ClashNode> nodes, String lastError) {
      this.hasProfile = hasProfile; this.running = running; this.tunUp = tunUp;
      this.profileBytes = profileBytes; this.subscriptionUrl = subscriptionUrl;
      this.savedNode = savedNode; this.currentNode = currentNode;
      this.netnsId = netnsId == null ? "" : netnsId;
      this.externalLan = externalLan;
      this.controllerPort = controllerPort > 0 ? controllerPort : ClashConfig.CONTROLLER_PORT;
      this.controllerSecret = controllerSecret == null ? "" : controllerSecret;
      this.lanIpv4 = lanIpv4 == null ? "" : lanIpv4;
      this.nodes = Collections.unmodifiableList(nodes); this.lastError = lastError;
    }
    ClashState withError(String err) {
      return new ClashState(hasProfile, running, tunUp, profileBytes, subscriptionUrl, savedNode,
          currentNode, netnsId, externalLan, controllerPort, controllerSecret, lanIpv4, nodes, err);
    }
    static ClashState fromJson(JSONObject j, String err) {
      List<ClashNode> list = new ArrayList<>();
      JSONArray arr = j.optJSONArray("nodes");
      if (arr != null) {
        for (int i = 0; i < arr.length(); i++) {
          JSONObject o = arr.optJSONObject(i);
          if (o != null && !o.optString("name").isEmpty())
            list.add(new ClashNode(o.optString("name"), o.optString("type", ""), o.optInt("delay", 0)));
        }
      }
      return new ClashState(
          j.optBoolean("hasProfile"), j.optBoolean("running"), j.optBoolean("tunUp"),
          j.optLong("profileBytes"), j.optString("subscriptionUrl", ""),
          j.optString("savedNode", ""), j.optString("currentNode", ""),
          j.optString("netnsId", ""),
          j.optBoolean("externalLan", false),
          j.optInt("controllerPort", ClashConfig.CONTROLLER_PORT),
          j.optString("controllerSecret", ""),
          j.optString("lanIpv4", ""),
          list, err);
    }
  }
  static final class ClashConnection {
    final String id, network, type, source, destination, rule, rulePayload, start;
    final List<String> chains;
    final long upload, download;
    ClashConnection(String id, String network, String type, String source, String destination,
        String rule, String rulePayload, List<String> chains, long upload, long download, String start) {
      this.id = id; this.network = network; this.type = type;
      this.source = source; this.destination = destination;
      this.rule = rule; this.rulePayload = rulePayload;
      this.chains = Collections.unmodifiableList(chains);
      this.upload = upload; this.download = download; this.start = start;
    }
  }
  static final class ConnectionsSnapshot {
    static final ConnectionsSnapshot EMPTY = new ConnectionsSnapshot(false, 0, 0, 0, Collections.emptyList(), "");
    final boolean running;
    final long downloadTotal, uploadTotal, memory;
    final List<ClashConnection> connections;
    final String error;
    ConnectionsSnapshot(boolean running, long downloadTotal, long uploadTotal, long memory,
        List<ClashConnection> connections, String error) {
      this.running = running; this.downloadTotal = downloadTotal;
      this.uploadTotal = uploadTotal; this.memory = memory;
      this.connections = Collections.unmodifiableList(connections);
      this.error = error == null ? "" : error;
    }
    static ConnectionsSnapshot error(String err) {
      return new ConnectionsSnapshot(false, 0, 0, 0, Collections.emptyList(), err);
    }
    static ConnectionsSnapshot fromJson(JSONObject j, String err) {
      List<ClashConnection> list = new ArrayList<>();
      JSONArray arr = j.optJSONArray("connections");
      if (arr != null) {
        for (int i = 0; i < arr.length(); i++) {
          JSONObject o = arr.optJSONObject(i);
          if (o == null) continue;
          List<String> chains = new ArrayList<>();
          JSONArray ch = o.optJSONArray("chains");
          if (ch != null) for (int k = 0; k < ch.length(); k++) chains.add(ch.optString(k, ""));
          list.add(new ClashConnection(
              o.optString("id", ""),
              o.optString("network", ""),
              o.optString("type", ""),
              o.optString("source", ""),
              o.optString("destination", ""),
              o.optString("rule", ""),
              o.optString("rulePayload", ""),
              chains,
              o.optLong("upload", 0),
              o.optLong("download", 0),
              o.optString("start", "")));
        }
      }
      return new ConnectionsSnapshot(
          j.optBoolean("running", false),
          j.optLong("downloadTotal", 0),
          j.optLong("uploadTotal", 0),
          j.optLong("memory", 0),
          list, err);
    }
  }
}
