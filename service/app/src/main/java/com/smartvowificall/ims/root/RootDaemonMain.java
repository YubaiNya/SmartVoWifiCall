package com.smartvowificall.ims.root;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/**
 * Single-instance resident Root Daemon consolidating RootIdentityMain, RootClashMain, and
 * RootNetworkMain over a protected filesystem Unix Domain Socket.
 *
 * Eliminates repeated `su -c app_process` ART VM startups during status polling, SIM switching,
 * and Clash UI refreshes while enforcing strict SO_PEERCRED UID verification (root or app UID).
 */
public final class RootDaemonMain {
  private static final String APP_PACKAGE = "com.smartvowificall.ims";
  private static final String LOCK_PATH = "/data/adb/smartvowificall_ims/run/root-daemon.lock";
  private static final AtomicLong REQUEST_COUNT = new AtomicLong();
  private static volatile long startedAtMs;
  private static volatile int cachedAppUid = -1;
  private static volatile long lastUidRefreshMs = 0L;
  private static RandomAccessFile instanceLockRaf;
  private static FileLock instanceLock;

  private RootDaemonMain() {}

  public static void main(String[] args) {
    try {
      if (Process.myUid() != 0) throw new SecurityException("ROOT_REQUIRED");
      if (!acquireSingleInstanceLock()) {
        // Another RootDaemonMain instance already holds the lock; exit cleanly.
        System.exit(0);
      }
      if (Looper.myLooper() == null) Looper.prepareMainLooper();
      // Pre-initialize ActivityThread.systemMain() on the main Looper thread so worker threads
      // serving RootIdentityMain requests never fail with "Can't create handler inside thread...".
      RootIdentityMain.warmupRootContext();
      startedAtMs = SystemClock.elapsedRealtime();

      String socketPath = args.length > 0 && !args[0].isEmpty()
          ? args[0] : RootDaemonProtocol.DEFAULT_SOCKET_PATH;
      int initialUid = -1;
      if (args.length > 1 && args[1].matches("[0-9]{1,10}")) {
        initialUid = Integer.parseInt(args[1]);
      }
      if (initialUid >= 10000) {
        cachedAppUid = initialUid;
        lastUidRefreshMs = SystemClock.elapsedRealtime();
      } else {
        refreshAppUid(true);
      }

      serve(socketPath);
    } catch (Throwable t) {
      System.err.println("RootDaemonMain fatal: " + t.getClass().getSimpleName());
      System.exit(1);
    }
  }

  private static boolean acquireSingleInstanceLock() {
    try {
      File lockFile = new File(LOCK_PATH);
      File parent = lockFile.getParentFile();
      if (parent != null && !parent.isDirectory()) {
        parent.mkdirs();
        Os.chmod(parent.getAbsolutePath(), 0700);
      }
      RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
      Os.chmod(lockFile.getAbsolutePath(), 0600);
      FileLock lock = raf.getChannel().tryLock();
      if (lock == null) {
        raf.close();
        return false;
      }
      instanceLockRaf = raf;
      instanceLock = lock;
      return true;
    } catch (java.nio.channels.OverlappingFileLockException e) {
      return false;
    } catch (Throwable t) {
      System.err.println("RootDaemonMain lock error: " + t.getClass().getSimpleName());
      return false;
    }
  }

  private static void serve(String socketPath) throws Exception {
    File sockFile = new File(socketPath);
    File parent = sockFile.getParentFile();
    if (parent != null) {
      if (!parent.isDirectory()) parent.mkdirs();
      Os.chmod(parent.getAbsolutePath(), 0711);
    }
    if (sockFile.exists()) sockFile.delete();

    LocalSocket bindSocket = new LocalSocket();
    bindSocket.bind(new LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM));
    int appUid = refreshAppUid(false);
    if (appUid >= 10000) {
      Os.chown(socketPath, 0, appUid);
      Os.chmod(socketPath, 0660);
    } else {
      Os.chmod(socketPath, 0600);
    }
    Os.listen(bindSocket.getFileDescriptor(), 16);

    ExecutorService pool = Executors.newFixedThreadPool(4);
    LocalServerSocket server = new LocalServerSocket(bindSocket.getFileDescriptor());
    try {
      while (true) {
        LocalSocket client = server.accept();
        pool.execute(() -> handleClient(client, socketPath));
      }
    } finally {
      pool.shutdownNow();
      server.close();
      bindSocket.close();
      sockFile.delete();
    }
  }

  private static void handleClient(LocalSocket client, String socketPath) {
    try (LocalSocket c = client) {
      c.setSoTimeout(30_000);
      int peerUid = c.getPeerCredentials().getUid();
      int appUid = refreshAppUid(false);
      if (!RootDaemonProtocol.isAuthorizedPeerUid(peerUid, appUid)) {
        // Re-check once in case the app was reinstalled with a new UID.
        appUid = refreshAppUid(true);
        if (!RootDaemonProtocol.isAuthorizedPeerUid(peerUid, appUid)) {
          writeLine(c.getOutputStream(), RootDaemonProtocol.encodeError("UNAUTHORIZED_UID"));
          return;
        }
        if (appUid >= 10000) {
          try {
            Os.chown(socketPath, 0, appUid);
            Os.chmod(socketPath, 0660);
          } catch (Throwable ignored) {}
        }
      }

      BufferedReader reader = new BufferedReader(
          new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
      String line = reader.readLine();
      if (line == null || line.trim().isEmpty()) return;

      long t0 = SystemClock.elapsedRealtime();
      RootDaemonProtocol.Request req = RootDaemonProtocol.parseRequest(line);
      long reqNum = REQUEST_COUNT.incrementAndGet();
      JSONObject payload = dispatch(req, reqNum);
      long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - t0);

      JSONObject envelope = new JSONObject();
      envelope.put("ok", true);
      envelope.put("durationMs", elapsed);
      envelope.put("result", payload);
      writeLine(c.getOutputStream(), envelope.toString());
    } catch (IllegalArgumentException e) {
      safeWriteError(client, e.getMessage());
    } catch (Throwable t) {
      safeWriteError(client, "DAEMON_EXEC_FAILED");
    }
  }

  private static JSONObject dispatch(RootDaemonProtocol.Request req, long reqNum) throws Exception {
    switch (req.target) {
      case "ping":
        return new JSONObject()
            .put("ok", true)
            .put("code", "PONG")
            .put("pid", Process.myPid())
            .put("uptimeMs", Math.max(0L, SystemClock.elapsedRealtime() - startedAtMs))
            .put("requests", reqNum);
      case "identity":
        return RootIdentityMain.execute(req.args);
      case "clash":
        return RootClashMain.execute(req.args, req.stdinBytes);
      case "network":
        return RootNetworkMain.execute(req.args);
      default:
        return new JSONObject().put("ok", false).put("code", "DAEMON_TARGET_INVALID");
    }
  }

  private static int refreshAppUid(boolean force) {
    long now = SystemClock.elapsedRealtime();
    if (!force && cachedAppUid >= 10000 && (now - lastUidRefreshMs) < 30_000L) {
      return cachedAppUid;
    }
    int resolved = resolveAppUid();
    if (resolved >= 10000) {
      cachedAppUid = resolved;
      lastUidRefreshMs = now;
    }
    return cachedAppUid;
  }

  private static int resolveAppUid() {
    try {
      IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
          .getMethod("getService", String.class).invoke(null, "package");
      if (binder != null && binder.isBinderAlive()) {
        Class<?> type = Class.forName("android.content.pm.IPackageManager");
        Object proxy = Class.forName("android.content.pm.IPackageManager$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, binder);
        try {
          Object val = type.getMethod("getPackageUid", String.class, long.class, int.class)
              .invoke(proxy, APP_PACKAGE, 0L, 0);
          if (val instanceof Integer && (Integer) val >= 10000) return (Integer) val;
        } catch (NoSuchMethodException ignored) {
          Object val = type.getMethod("getPackageUid", String.class, int.class, int.class)
              .invoke(proxy, APP_PACKAGE, 0, 0);
          if (val instanceof Integer && (Integer) val >= 10000) return (Integer) val;
        }
      }
    } catch (Throwable ignored) {}
    try {
      android.system.StructStat st = Os.stat("/data/data/" + APP_PACKAGE);
      if (st != null && st.st_uid >= 10000) return st.st_uid;
    } catch (Throwable ignored) {}
    return -1;
  }

  private static void writeLine(OutputStream out, String json) throws Exception {
    out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void safeWriteError(LocalSocket client, String code) {
    try {
      writeLine(client.getOutputStream(), RootDaemonProtocol.encodeError(code));
    } catch (Throwable ignored) {}
  }
}
