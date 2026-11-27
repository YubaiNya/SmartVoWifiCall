package com.smartvowificall.ims.root;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Serializes LPA access and VoWiFi radio acquisition for the same logical SIM slot. */
final class LpaSlotLock implements AutoCloseable {
  private final RandomAccessFile file;
  private final FileLock lock;
  LpaSlotLock(int slot) throws IOException {
    this(new File("/data/adb/smartvowificall_ims/lpa"), slot);
  }
  LpaSlotLock(File dir, int slot) throws IOException {
    if (slot < 0 || slot > 7) throw new IOException("LPA_SLOT_INVALID");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("LPA_LOCK_UNAVAILABLE");
    file = new RandomAccessFile(new File(dir, "slot-" + slot + ".lock"), "rw");
    try {
      lock = file.getChannel().tryLock();
      if (lock == null) throw new IOException("LPA_BUSY");
    } catch (Exception e) {
      file.close();
      throw new IOException("LPA_BUSY");
    }
  }
  @Override public void close() throws IOException {
    try { lock.release(); } finally { file.close(); }
  }
}
