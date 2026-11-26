package com.smartvowificall.ims.policy;

/** A successful write is not ownership: Telecom can clear the override after a call. */
public final class VoiceServiceOverride {
  public interface Backend {
    void apply(boolean hasService) throws Exception;
  }
  private volatile Boolean lastApplied;

  public Boolean lastApplied() {
    return lastApplied;
  }

  public synchronized void synchronize(
      boolean hasService, boolean frameworkInService, Backend backend) throws Exception {
    if (hasService && frameworkInService && Boolean.TRUE.equals(lastApplied)) return;
    if (!hasService && Boolean.FALSE.equals(lastApplied)) return;
    try {
      backend.apply(hasService);
      lastApplied = hasService;
    } catch (Exception error) {
      lastApplied = null;
      throw error;
    }
  }
}
