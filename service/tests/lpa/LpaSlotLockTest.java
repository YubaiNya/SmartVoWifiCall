package com.smartvowificall.ims.root;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

public final class LpaSlotLockTest {
  public static void main(String[] args) throws Exception {
    Path temporary = Files.createTempDirectory("smartvowifi-lpa-lock-");
    ExecutorService other = Executors.newSingleThreadExecutor();
    try {
      try (LpaSlotLock first = new LpaSlotLock(temporary.toFile(), 0)) {
        boolean blocked = other.submit(() -> {
          try (LpaSlotLock second = new LpaSlotLock(temporary.toFile(), 0)) { return false; }
          catch (java.io.IOException expected) { return "LPA_BUSY".equals(expected.getMessage()); }
        }).get(5, TimeUnit.SECONDS);
        if (!blocked) throw new AssertionError("Concurrent access to target SIM was allowed");
        try (LpaSlotLock independent = new LpaSlotLock(temporary.toFile(), 1)) { }
      }
      try (LpaSlotLock released = new LpaSlotLock(temporary.toFile(), 0)) { }
      for (int invalid : new int[]{-1, 8}) {
        try (LpaSlotLock ignored = new LpaSlotLock(temporary.toFile(), invalid)) { throw new AssertionError(); }
        catch (java.io.IOException expected) {
          if (!"LPA_SLOT_INVALID".equals(expected.getMessage())) throw expected;
        }
      }
      System.out.println("LPA slot ownership checks passed: concurrency, slot isolation, release and bounds");
    } finally {
      other.shutdownNow(); other.awaitTermination(5, TimeUnit.SECONDS);
      Files.deleteIfExists(temporary.resolve("slot-0.lock"));
      Files.deleteIfExists(temporary.resolve("slot-1.lock"));
      Files.delete(temporary);
    }
  }
}
