package com.smartvowificall.ims.root;

import com.smartvowificall.ims.engine.JsonCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java wire protocol & authorization policy for the resident Root Daemon.
 * Framing: single-line UTF-8 JSON per request/response over a protected Unix Domain Socket.
 */
public final class RootDaemonProtocol {
  public static final String DEFAULT_SOCKET_PATH = "/data/local/tmp/smartvowificall/root-daemon.sock";
  public static final int MAX_REQUEST_LINE_BYTES = 6 * 1024 * 1024;
  public static final int MAX_STDIN_BYTES = 4 * 1024 * 1024;
  public static final int MAX_ARGS = 16;

  private static final Set<String> ALLOWED_TARGETS = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("ping", "identity", "clash", "network")));

  private RootDaemonProtocol() {}

  public static final class Request {
    public final String target;
    public final String[] args;
    public final byte[] stdinBytes;

    public Request(String target, String[] args, byte[] stdinBytes) {
      this.target = target;
      this.args = args;
      this.stdinBytes = stdinBytes;
    }
  }

  /** Only root (uid 0) or the verified SmartVoWifiCall app UID (>= 10000) may connect. */
  public static boolean isAuthorizedPeerUid(int peerUid, int allowedAppUid) {
    if (peerUid == 0) return true;
    return allowedAppUid >= 10000 && peerUid == allowedAppUid;
  }

  public static boolean isValidTarget(String target) {
    return target != null && ALLOWED_TARGETS.contains(target);
  }

  public static String sanitizeCode(String code, String fallback) {
    if (code != null && code.matches("[A-Z0-9_]{3,80}")) return code;
    return fallback;
  }

  public static String encodeRequest(String target, List<String> args, byte[] stdinBytes) {
    if (!isValidTarget(target)) throw new IllegalArgumentException("DAEMON_TARGET_INVALID");
    List<String> safeArgs = args == null ? Collections.emptyList() : args;
    if (safeArgs.size() > MAX_ARGS) throw new IllegalArgumentException("DAEMON_TOO_MANY_ARGS");
    if (stdinBytes != null && stdinBytes.length > MAX_STDIN_BYTES)
      throw new IllegalArgumentException("DAEMON_STDIN_TOO_LARGE");
    Map<String, Object> obj = new LinkedHashMap<>();
    obj.put("target", target);
    obj.put("args", safeArgs);
    if (stdinBytes != null && stdinBytes.length > 0) {
      obj.put("stdinBase64", Base64.getEncoder().encodeToString(stdinBytes));
    }
    return JsonCodec.encode(obj);
  }

  public static Request parseRequest(String line) {
    if (line == null || line.trim().isEmpty())
      throw new IllegalArgumentException("DAEMON_REQUEST_EMPTY");
    if (line.length() > MAX_REQUEST_LINE_BYTES)
      throw new IllegalArgumentException("DAEMON_REQUEST_TOO_LARGE");
    Object parsed;
    try {
      parsed = JsonCodec.decode(line);
    } catch (Exception e) {
      throw new IllegalArgumentException("DAEMON_REQUEST_INVALID");
    }
    if (!(parsed instanceof Map<?, ?>))
      throw new IllegalArgumentException("DAEMON_REQUEST_INVALID");
    Map<?, ?> map = (Map<?, ?>) parsed;
    Object rawTarget = map.get("target");
    if (!(rawTarget instanceof String) || !isValidTarget((String) rawTarget))
      throw new IllegalArgumentException("DAEMON_TARGET_INVALID");
    String target = (String) rawTarget;

    List<String> argList = new ArrayList<>();
    Object rawArgs = map.get("args");
    if (rawArgs != null) {
      if (!(rawArgs instanceof List<?>))
        throw new IllegalArgumentException("DAEMON_ARGS_INVALID");
      List<?> list = (List<?>) rawArgs;
      if (list.size() > MAX_ARGS)
        throw new IllegalArgumentException("DAEMON_TOO_MANY_ARGS");
      for (Object item : list) {
        if (!(item instanceof String))
          throw new IllegalArgumentException("DAEMON_ARGS_INVALID");
        String s = (String) item;
        if (s.length() > 4096 || s.indexOf('\0') >= 0)
          throw new IllegalArgumentException("DAEMON_ARGS_INVALID");
        argList.add(s);
      }
    }

    byte[] stdin = null;
    Object rawStdin = map.get("stdinBase64");
    if (rawStdin != null) {
      if (!(rawStdin instanceof String))
        throw new IllegalArgumentException("DAEMON_STDIN_INVALID");
      String b64 = (String) rawStdin;
      if (!b64.isEmpty()) {
        try {
          stdin = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("DAEMON_STDIN_INVALID");
        }
        if (stdin.length > MAX_STDIN_BYTES)
          throw new IllegalArgumentException("DAEMON_STDIN_TOO_LARGE");
      }
    }
    return new Request(target, argList.toArray(new String[0]), stdin);
  }

  public static String encodeError(String code) {
    Map<String, Object> obj = new LinkedHashMap<>();
    obj.put("ok", Boolean.FALSE);
    obj.put("code", sanitizeCode(code, "DAEMON_ERROR"));
    return JsonCodec.encode(obj);
  }
}
