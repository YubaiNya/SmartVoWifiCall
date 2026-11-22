package com.smartvowificall.ims;
import android.telephony.ims.*;
import android.telephony.ims.feature.*;
import android.telephony.ims.stub.*;
import java.util.concurrent.Executor;
public final class SmartVoWifiImsService extends ImsService {
  private static volatile SmartVoWifiImsService running;
  private String lastFeatures = "";
  private RuntimeController rt() {
    return RuntimeController.get(this);
  }
  public void onCreate() {
    super.onCreate();
    running = this;
    rt().start();
  }
  public void onDestroy() {
    if (running == this)
      running = null;
    super.onDestroy();
  }
  public Executor getExecutor() {
    return rt().binderExecutor;
  }
  public ImsFeatureConfiguration querySupportedImsFeatures() {
    ImsFeatureConfiguration.Builder b = new ImsFeatureConfiguration.Builder();
    for (int slot = 0; slot < 4; slot++)
      if (rt().eligible(slot))
        b.addFeature(slot, ImsFeature.FEATURE_MMTEL);
    return b.build();
  }
  static void refreshFeatures() {
    SmartVoWifiImsService s = running;
    if (s == null)
      return;
    ImsFeatureConfiguration config = s.querySupportedImsFeatures();
    String key = config.getServiceFeatures().toString();
    if (!key.equals(s.lastFeatures)) {
      try {
        s.onUpdateSupportedImsFeatures(config);
        s.lastFeatures = key;
      } catch (Exception ignored) {
      }
    }
  }
  public MmTelFeature createMmTelFeature(int slot) {
    return create(slot);
  }
  public MmTelFeature createMmTelFeatureForSubscription(int slot, int sub) {
    return rt().matchesSubscription(slot, sub) ? create(slot) : null;
  }
  private NewMmTelFeature create(int slot) {
    if (!rt().eligible(slot))
      return null;
    NewMmTelFeature f = new NewMmTelFeature(rt(), slot);
    rt().features.put(slot, f);
    return f;
  }
  public ImsRegistrationImplBase getRegistration(int slot) {
    return rt().registration(slot);
  }
  public ImsRegistrationImplBase getRegistrationForSubscription(int slot, int sub) {
    return rt().registration(slot);
  }
  public ImsConfigImplBase getConfig(int slot) {
    return new ImsConfigImplBase(rt().binderExecutor);
  }
  public ImsConfigImplBase getConfigForSubscription(int slot, int sub) {
    return getConfig(slot);
  }
  public void enableIms(int slot) {
    rt().kick();
  }
  public void enableImsForSubscription(int slot, int sub) {
    if (rt().matchesSubscription(slot, sub))
      enableIms(slot);
  }
}
