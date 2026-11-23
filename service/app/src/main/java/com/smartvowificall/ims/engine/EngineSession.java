package com.smartvowificall.ims.engine;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-home-SIM protocol adapter. Subscription selection and Android ImsService live above this
 * layer.
 */
public final class EngineSession implements Closeable {
  public static final int DEFAULT_TIMEOUT_MS = 5000;
  // Baseline EngineCallRequestExecutor uses 0x1fbd0 ms for dial completion.
  public static final int DIAL_TIMEOUT_MS = 130000;
  public static final class Identity {
    public final String iccid;
    public final int slotId;
    public final int subId;
    public Identity(String iccid, int slotId, int subId) {
      if (iccid == null || !iccid.matches("[0-9]{1,32}"))
        throw new IllegalArgumentException("Invalid ICCID identity");
      if (slotId < 0 || subId < 0)
        throw new IllegalArgumentException("Invalid subscription binding");
      this.iccid = iccid;
      this.slotId = slotId;
      this.subId = subId;
    }
    public String controlSocketPath() {
      return "/data/local/tmp/vowifi/" + iccid + ".sock";
    }
    public String audioSocketPath() {
      return "/data/local/tmp/vowifi/" + iccid + ".audio.sock";
    }
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Identity))
        return false;
      Identity i = (Identity) other;
      return slotId == i.slotId && subId == i.subId && iccid.equals(i.iccid);
    }
    @Override
    public int hashCode() {
      return (iccid.hashCode() * 31 + slotId) * 31 + subId;
    }
    @Override
    public String toString() {
      return "EngineIdentity(slot=" + slotId + ",subId=" + subId + ")";
    }
  }
  public interface Listener {
    void onEvent(Identity identity, EngineState.Event event);
    void onDisconnected(Identity identity, Throwable cause);
  }
  public final Identity identity;
  private final EngineRpcClient client;
  private final EngineRpcClient.TransportFactory transportFactory;
  private final Set<EngineRpcClient> controls = ConcurrentHashMap.newKeySet();
  private final Set<AudioTransport> audioTransports = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;
  private final boolean billableAllowed;
  private EngineState state = new EngineState();

  public EngineSession(
      Identity identity, EngineRpcClient.TransportFactory factory, Listener listener) {
    this(identity, factory, listener, false);
  }
  public EngineSession(Identity identity, EngineRpcClient.TransportFactory factory,
      Listener listener, boolean billableAllowed) {
    if (identity == null || listener == null)
      throw new NullPointerException();
    this.identity = identity;
    this.transportFactory = factory;
    this.billableAllowed = billableAllowed;
    this.client =
        new EngineRpcClient(identity.controlSocketPath(), factory, new EngineRpcClient.Listener() {
          @Override
          public void onEvent(EngineState.Event event) {
            listener.onEvent(identity, event);
          }
          @Override
          public void onDisconnected(Throwable cause) {
            listener.onDisconnected(identity, cause);
          }
        });
  }
  public void connect() throws IOException {
    connect(DEFAULT_TIMEOUT_MS);
  }
  public void setPacketObserver(EngineRpcClient.PacketObserver observer) {
    client.setPacketObserver(observer);
  }
  public void connect(int timeoutMs) throws IOException {
    client.connect(timeoutMs);
  }
  public boolean isConnected() {
    return client.isConnected();
  }
  public synchronized EngineState getState() {
    return state;
  }
  public Map<String, Object> getStatus() throws IOException {
    return getStatus(DEFAULT_TIMEOUT_MS);
  }
  public Map<String, Object> getStatus(int timeoutMs) throws IOException {
    Map<String, Object> value = client.request("getStatus", Collections.emptyMap(), timeoutMs);
    synchronized (this) {
      state = state.withStatus(value);
    }
    return value;
  }
  public Map<String, Object> getCallSnapshot() throws IOException {
    return getCallSnapshot(DEFAULT_TIMEOUT_MS);
  }
  public Map<String, Object> getCallSnapshot(int timeoutMs) throws IOException {
    Map<String, Object> value =
        client.request("getCallSnapshot", Collections.emptyMap(), timeoutMs);
    synchronized (this) {
      state = state.withCallSnapshot(value);
    }
    return value;
  }
  public Map<String, Object> getClientCapabilities() throws IOException {
    return client.request("getClientCapabilities",
        map("schema", 1, "required",
            Arrays.asList(
                "config.metadata.v1", "rpc.getConfig", "rpc.getStatus", "rpc.ping", "rpc.stop")),
        DEFAULT_TIMEOUT_MS);
  }
  public Map<String, Object> dial(String number) throws IOException {
    gate();
    if (number == null || !number.matches("\\+?[0-9]{1,20}"))
      throw new IllegalArgumentException("Invalid dial number");
    return controlRequest("dial", map("recipient", "tel:" + number), DIAL_TIMEOUT_MS);
  }
  public Map<String, Object> answer(String callId) throws IOException {
    gate();
    return call("answer", callId(callId));
  }
  public Map<String, Object> reject(String callId) throws IOException {
    gate();
    return call("reject", callId(callId));
  }
  public Map<String, Object> hangup(String callId) throws IOException {
    gate();
    return call("hangup", callId(callId));
  }
  public Map<String, Object> cancelCall(String callId) throws IOException {
    gate();
    return call("cancelCall", callId(callId));
  }
  public Map<String, Object> sendDtmf(String callId, char digit) throws IOException {
    return sendDtmf(callId, digit, 160);
  }
  public Map<String, Object> sendDtmf(String callId, char digit, int durationMs)
      throws IOException {
    gate();
    if ("0123456789*#ABCD".indexOf(digit) < 0 || durationMs <= 0)
      throw new IllegalArgumentException("Invalid DTMF request");
    Map<String, Object> params = callId(callId);
    params.put("digit", String.valueOf(digit));
    params.put("durationMs", durationMs);
    return call("sendDtmf", params);
  }
  /** Opens raw PCM transport through the same platform factory, without a JSON handshake. */
  public EngineRpcClient.Transport openAudioTransport(int timeoutMs) throws IOException {
    gate();
    if (timeoutMs <= 0)
      throw new IllegalArgumentException("Positive timeout required");
    synchronized (controls) {
      if (closed || !client.isConnected())
        throw new IOException("Engine session is disconnected");
    }
    AudioTransport transport =
        new AudioTransport(transportFactory.open(identity.audioSocketPath(), timeoutMs));
    synchronized (controls) {
      if (closed || !client.isConnected()) {
        transport.close();
        throw new IOException("Engine session is disconnected");
      }
      audioTransports.add(transport);
    }
    return transport;
  }
  private final class AudioTransport implements EngineRpcClient.Transport {
    private final EngineRpcClient.Transport delegate;
    private final AtomicBoolean released = new AtomicBoolean();
    AudioTransport(EngineRpcClient.Transport delegate) {
      this.delegate = delegate;
    }
    public InputStream input() throws IOException {
      return delegate.input();
    }
    public OutputStream output() throws IOException {
      return delegate.output();
    }
    public void close() throws IOException {
      if (!released.compareAndSet(false, true))
        return;
      audioTransports.remove(this);
      delegate.close();
    }
  }
  /** Open the separate audio transport BEFORE this command; it has no JSON handshake. */
  public Map<String, Object> audioStart(String callId) throws IOException {
    gate();
    Map<String, Object> params = callId(callId);
    params.put("sampleRate", PcmFrameCodec.SAMPLE_RATE_HZ);
    params.put("channels", PcmFrameCodec.CHANNEL_COUNT);
    return call("audioStart", params);
  }
  public Map<String, Object> audioStop(String callId) throws IOException {
    gate();
    return call("audioStop", callId(callId));
  }
  /** Text API only. Does not claim Android IMS SMS PDU/acknowledgment compatibility. */
  public Map<String, Object> sendSmsText(String number, String text) throws IOException {
    gate();
    if (number == null || number.isEmpty() || text == null)
      throw new IllegalArgumentException("SMS fields required");
    return call("sendSms", map("number", number, "text", text));
  }
  private Map<String, Object> call(String method, Map<String, Object> params) throws IOException {
    return controlRequest(method, params, DEFAULT_TIMEOUT_MS);
  }
  private Map<String, Object> controlRequest(
      String method, Map<String, Object> params, int timeoutMs) throws IOException {
    // The observed server handles requests synchronously per connection. A separate connection
    // is necessary so a pending dial cannot block cancelCall, snapshots, or registration polling.
    EngineRpcClient rpc = new EngineRpcClient(
        identity.controlSocketPath(), transportFactory, new EngineRpcClient.Listener() {
          @Override
          public void onEvent(EngineState.Event ignored) { /* Main connection owns events. */ }
          @Override
          public void onDisconnected(Throwable ignored) { /* Request reports this error. */ }
        });
    synchronized (controls) {
      if (closed || !client.isConnected()) {
        rpc.close();
        throw new IOException("Engine session is disconnected");
      }
      controls.add(rpc);
    }
    try {
      rpc.connect(DEFAULT_TIMEOUT_MS);
      return rpc.request(method, params, timeoutMs);
    } finally {
      controls.remove(rpc);
      rpc.close();
    }
  }
  private void gate() {
    if (!billableAllowed)
      throw new SecurityException("Call, audio and SMS control is disabled for this session");
  }
  private static Map<String, Object> callId(String id) {
    if (id == null || id.isEmpty())
      throw new IllegalArgumentException("Call ID required");
    return map("callId", id);
  }
  public static Map<String, Object> map(Object... entries) {
    if (entries.length % 2 != 0)
      throw new IllegalArgumentException("Key/value pairs required");
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
    return result;
  }
  @Override
  public void close() {
    synchronized (controls) {
      closed = true;
      client.close();
      for (EngineRpcClient rpc : controls) rpc.close();
      controls.clear();
      for (AudioTransport transport : audioTransports) {
        try {
          transport.close();
        } catch (IOException ignored) {
        }
      }
      audioTransports.clear();
    }
  }
}
