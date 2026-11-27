package com.smartvowificall.ims.telemetry;

import com.smartvowificall.ims.engine.JsonCodec;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared by Root helper writers and upload cleanup, including separate processes. */
public final class TraceJournal {
  private TraceJournal() {}

  public static void append(Path file, Map<String, Object> entry) throws IOException {
    entry.put("traceId", UUID.randomUUID().toString());
    update(file, JsonCodec.encode(entry), null);
  }

  public static int acknowledge(Path file, List<?> ids) throws IOException {
    if (ids.size() > 100) throw new IOException("TRACE_ACK_INVALID");
    Set<String> selected = new HashSet<>();
    for (Object id : ids) {
      if (!(id instanceof String) || !((String) id).matches("[a-f0-9-]{36}"))
        throw new IOException("TRACE_ACK_INVALID");
      selected.add((String) id);
    }
    return selected.isEmpty() ? 0 : update(file, null, selected);
  }

  private static synchronized int update(Path file, String append, Set<String> selected) throws IOException {
    Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
    if (Files.isSymbolicLink(file) || Files.isSymbolicLink(lockPath)) throw new IOException("TRACE_PATH_INVALID");
    try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      FileLock lock = null;
      long until = System.nanoTime() + 2_000_000_000L;
      while (lock == null) {
        lock = channel.tryLock();
        if (lock != null) break;
        if (System.nanoTime() >= until) throw new IOException("TRACE_BUSY");
        try { Thread.sleep(10); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("TRACE_CANCELLED"); }
      }
      try {
        List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>();
        int removed = 0;
        if (selected != null) {
          for (int i = lines.size() - 1; i >= 0; i--) {
            try {
              if (selected.contains(JsonCodec.decodeObject(lines.get(i)).get("traceId"))) {
                lines.remove(i); removed++;
              }
            } catch (IOException ignored) { /* Retain malformed/legacy records. */ }
          }
          if (removed == 0) return 0;
        } else {
          lines.add(append);
          if (lines.size() > 50) lines = new ArrayList<>(lines.subList(lines.size() - 50, lines.size()));
        }
        Path temp = Files.createTempFile(file.getParent(), ".trace-", ".tmp");
        try {
          Files.write(temp, lines, StandardCharsets.UTF_8);
          Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        return removed;
      } finally { lock.release(); }
    }
  }
}
