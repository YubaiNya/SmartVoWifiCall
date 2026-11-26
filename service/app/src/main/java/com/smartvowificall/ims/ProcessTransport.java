package com.smartvowificall.ims;

import com.smartvowificall.ims.engine.EngineRpcClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one root relay process; cancellation must precede buffered pipe closure. */
final class ProcessTransport implements EngineRpcClient.Transport {
  private final Process child;
  private final AtomicBoolean closed = new AtomicBoolean();
  ProcessTransport(Process child) { this.child = child; }
  public InputStream input() { return child.getInputStream(); }
  public OutputStream output() { return child.getOutputStream(); }
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) return;
    // A PCM writer may hold BufferedOutputStream's monitor while blocked on a full
    // child pipe. Closing/flushing first would wait before we can stop its reader.
    child.destroy();
    IOException failure = null;
    try { child.getOutputStream().close(); } catch (IOException error) { failure = error; }
    try { child.getInputStream().close(); } catch (IOException error) { if (failure == null) failure = error; }
    try { child.getErrorStream().close(); } catch (IOException error) { if (failure == null) failure = error; }
    if (failure != null) throw failure;
  }
}
