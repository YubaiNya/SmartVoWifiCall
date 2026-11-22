package com.smartvowificall.ims.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** All peers are memory queues. No LocalSocket, IP socket, process or real engine is used. */
public final class EngineContractTest {
    static int checks;
    static void check(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    interface Throwing { void run() throws Exception; }
    static <T extends Throwable> T throwsType(Class<T> type, Throwing action) throws Exception {
        try { action.run(); } catch (Throwable e) {
            check(type.isInstance(e), "Expected " + type.getSimpleName() + ", got " + e.getClass().getSimpleName());
            return type.cast(e);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    public interface Handler { void accept(MemoryTransport t, Map<String, Object> req) throws IOException; }
    public static final class MemoryTransport implements EngineRpcClient.Transport {
        final BlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, Object>> requests = new LinkedBlockingQueue<>();
        final CountDownLatch closedLatch = new CountDownLatch(1);
        final Handler handler;
        volatile boolean closed;
        int helloVersion = 1;
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        public MemoryTransport(Handler handler) { this.handler = handler; }
        @Override public InputStream input() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    if (closed && bytes.isEmpty()) return -1;
                    try { return bytes.take(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    int first = read();
                    if (first < 0) return -1;
                    b[off] = (byte) first;
                    int n = 1;
                    while (n < len) {
                        Integer value = bytes.poll();
                        if (value == null) break;
                        if (value == -1) { bytes.offer(-1); break; }
                        b[off + n++] = (byte) value.intValue();
                    }
                    return n;
                }
            };
        }
        @Override public OutputStream output() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException {
                    if (closed) throw new IOException("Fake peer closed");
                    if (b != '\n') { line.write(b); return; }
                    Map<String, Object> req = JsonCodec.decodeObject(line.toString("UTF-8"));
                    line.reset(); requests.add(req);
                    if ("hello".equals(req.get("method"))) reply(req, EngineSession.map("version", helloVersion));
                    else handler.accept(MemoryTransport.this, req);
                }
            };
        }
        public void frame(Map<String, Object> frame) {
            if (closed) return;
            for (byte b : (JsonCodec.encode(frame) + "\n").getBytes(StandardCharsets.UTF_8)) bytes.add(b & 255);
        }
        public void reply(Map<String, Object> req, Map<String, Object> result) { frame(EngineSession.map("id", req.get("id"), "result", result)); }
        public void event(String name, Map<String, Object> data) { frame(EngineSession.map("event", name, "data", data)); }
        @Override public void close() { closed = true; bytes.offer(-1); closedLatch.countDown(); }
        Map<String, Object> next() throws Exception {
            Map<String, Object> req = requests.poll(2, TimeUnit.SECONDS);
            if (req == null) throw new AssertionError("Fake request not received");
            return req;
        }
    }
    static final class Events implements EngineRpcClient.Listener {
        final BlockingQueue<EngineState.Event> events = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> disconnects = new LinkedBlockingQueue<>();
        @Override public void onEvent(EngineState.Event event) { events.add(event); }
        @Override public void onDisconnected(Throwable cause) { disconnects.add(cause); }
    }
    static EngineRpcClient client(MemoryTransport transport, EngineRpcClient.Listener listener) {
        return new EngineRpcClient("memory-only", (path, timeout) -> transport, listener);
    }
    static void jsonAndPcm() throws Exception {
        Map<String, Object> value = EngineSession.map("text", "quote\" slash\\ newline\n 港澳台", "n", 9007199254740993L,
                "array", Arrays.asList(true, null, EngineSession.map("x", -3)));
        Map<String, Object> decoded = JsonCodec.decodeObject(JsonCodec.encode(value));
        check(value.get("text").equals(decoded.get("text")), "JSON escaped/unicode text");
        check(value.get("n").equals(decoded.get("n")), "Long precision");
        check("A".equals(JsonCodec.decode("\"\\u0041\"")), "Unicode escape");
        for (String bad : new String[]{"{\"x\":1,}", "[1,]", "{\"x\":1,\"x\":2}", "01", "1e", "true false", "\"raw\nline\""})
            throwsType(IOException.class, () -> JsonCodec.decode(bad));
        byte[] pcm = new byte[640];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        byte[] frame = PcmFrameCodec.encode(pcm);
        check(frame[0] == 0 && frame[1] == 0 && frame[2] == 2 && (frame[3] & 255) == 132, "BE total length 644");
        PcmFrameCodec.Decoder decoder = new PcmFrameCodec.Decoder();
        int frames = 0;
        for (int i = 0; i < frame.length; i++) {
            List<byte[]> out = decoder.push(frame, i, 1);
            for (byte[] payload : out) { frames++; check(Arrays.equals(pcm, payload), "Fragmented PCM roundtrip"); }
        }
        decoder.finish(); check(frames == 1, "One decoded PCM frame");
        byte[] pair = new byte[1288]; System.arraycopy(frame, 0, pair, 0, 644); System.arraycopy(frame, 0, pair, 644, 644);
        check(new PcmFrameCodec.Decoder().push(pair).size() == 2, "Coalesced PCM frames");
        PcmFrameCodec.Decoder truncated = new PcmFrameCodec.Decoder(); truncated.push(frame, 0, 643);
        throwsType(IOException.class, truncated::finish);
        throwsType(IOException.class, () -> new PcmFrameCodec.Decoder().push(new byte[]{0,0,2,(byte)128}));
    }
    static void correlationAndEvents() throws Exception {
        Events events = new Events();
        MemoryTransport transport = new MemoryTransport((t, req) -> {});
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (EngineRpcClient client = client(transport, events)) {
            client.connect(1000);
            Map<String, Object> hello = transport.next();
            check(!hello.containsKey("jsonrpc"), "No fabricated JSON-RPC version");
            check(EngineState.integer(((Map<?,?>)hello.get("params")).get("version")) == 1, "hello protocol v1");
            Future<Map<String, Object>> one = workers.submit(() -> client.request("one", Collections.emptyMap(), 1000));
            Future<Map<String, Object>> two = workers.submit(() -> client.request("two", Collections.emptyMap(), 1000));
            Map<String, Object> a = transport.next(), b = transport.next();
            transport.event("incomingCall", EngineSession.map("callId", "fake-call", "epoch", "e1", "revision", 4));
            transport.reply(b, EngineSession.map("method", b.get("method")));
            transport.reply(a, EngineSession.map("method", a.get("method")));
            check("one".equals(one.get().get("method")), "First response correlation");
            check("two".equals(two.get().get("method")), "Second response correlation");
            EngineState.Event event = events.events.poll(1, TimeUnit.SECONDS);
            check(event != null && event.versionOrNull().revision == 4, "Interleaved versioned event");
            throwsType(UnsupportedOperationException.class, () -> event.data.put("callId", "overwrite"));
        } finally { workers.shutdownNow(); }

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        MemoryTransport responsive = new MemoryTransport((t, req) -> t.reply(req, EngineSession.map("ok", true)));
        try (EngineRpcClient client = client(responsive, new EngineRpcClient.Listener() {
            @Override public void onEvent(EngineState.Event e) {
                entered.countDown(); try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            @Override public void onDisconnected(Throwable cause) {}
        })) {
            client.connect(1000); responsive.event("callState", Collections.emptyMap());
            check(entered.await(1, TimeUnit.SECONDS), "Listener entered");
            check(Boolean.TRUE.equals(client.request("getStatus", Collections.emptyMap(), 500).get("ok")), "Blocked listener cannot block RPC reader");
        } finally { release.countDown(); }
    }
    static void failuresAndReconnect() throws Exception {
        MemoryTransport incompatible = new MemoryTransport((t,r) -> {});
        incompatible.helloVersion = 2;
        try (EngineRpcClient client = client(incompatible, new Events())) {
            throwsType(IOException.class, () -> client.connect(1000));
            check(incompatible.closed && !client.isConnected(), "Unsupported hello version closes transport");
        }
        Events events = new Events();
        MemoryTransport transport = new MemoryTransport((t, req) -> {
            if ("error".equals(req.get("method"))) t.frame(EngineSession.map("id", req.get("id"), "error", EngineSession.map("code", 2004, "message", "test error")));
            if ("ping".equals(req.get("method"))) t.reply(req, EngineSession.map("alive", true));
        });
        try (EngineRpcClient client = client(transport, events)) {
            client.connect(1000); transport.next();
            EngineRpcClient.RpcException remote = throwsType(EngineRpcClient.RpcException.class, () -> client.request("error", Collections.emptyMap(), 500));
            check(remote.code == 2004 && "test error".equals(remote.remoteMessage()), "Typed remote error");
            transport.next();
            throwsType(EngineRpcClient.RequestTimeoutException.class, () -> client.request("late", Collections.emptyMap(), 80));
            Map<String, Object> late = transport.next();
            transport.reply(late, EngineSession.map("obsolete", true));
            check(Boolean.TRUE.equals(client.request("ping", Collections.emptyMap(), 500).get("alive")), "Late reply cannot satisfy next request");
            check(transport.next().get("method").equals("ping") && transport.requests.isEmpty(), "No request replay");
        }
        MemoryTransport first = new MemoryTransport((t, req) -> {});
        MemoryTransport second = new MemoryTransport((t, req) -> t.reply(req, EngineSession.map("connected", 2)));
        AtomicInteger opens = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Events disconnected = new Events();
        try (EngineRpcClient client = new EngineRpcClient("memory-only", (p,t) -> opens.getAndIncrement() == 0 ? first : second, disconnected)) {
            client.connect(1000); first.next();
            Future<?> pending = worker.submit(() -> { throwsType(IOException.class, () -> client.request("getStatus", Collections.emptyMap(), 1500)); return null; });
            first.next(); first.close(); pending.get(1, TimeUnit.SECONDS);
            check(disconnected.disconnects.poll(1, TimeUnit.SECONDS) != null, "Disconnect notifies listener");
            check(!client.isConnected(), "Disconnect clears ready");
            client.connect(1000);
            check(EngineState.integer(client.request("getStatus", Collections.emptyMap(), 500).get("connected")) == 2, "Explicit reconnect uses new transport");
            check(disconnected.disconnects.isEmpty(), "One notification for failed connection");
        } finally { worker.shutdownNow(); }

        MemoryTransport blocked = new MemoryTransport((t, req) -> {
            try { t.closedLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try (EngineRpcClient client = client(blocked, new Events())) {
            client.connect(1000);
            throwsType(EngineRpcClient.RequestTimeoutException.class, () -> client.request("blockedWrite", Collections.emptyMap(), 80));
            check(blocked.closed, "Write deadline retires transport");
        }
    }
    static void sessionsAndPolicy() throws Exception {
        BlockingQueue<EngineSession.Identity> identities = new LinkedBlockingQueue<>();
        EngineSession.Listener listener = new EngineSession.Listener() {
            @Override public void onEvent(EngineSession.Identity i, EngineState.Event e) { identities.add(i); }
            @Override public void onDisconnected(EngineSession.Identity i, Throwable cause) {}
        };
        MemoryTransport first = new MemoryTransport((t,r) -> t.reply(r, "getCallSnapshot".equals(r.get("method"))
                ? EngineSession.map("epoch", "e1", "revision", 3, "stage", "IDLE")
                : EngineSession.map("ims", "REGISTERED", "futureField", EngineSession.map("n", 1))));
        MemoryTransport second = new MemoryTransport((t,r) -> t.reply(r, EngineSession.map("ims", "UNREGISTERED")));
        EngineSession.Identity a = new EngineSession.Identity("1111111111111111111", 0, 19);
        EngineSession.Identity b = new EngineSession.Identity("2222222222222222222", 1, 27);
        try (EngineSession sa = new EngineSession(a, (path,timeout) -> { check(path.equals(a.controlSocketPath()), "First ICCID socket"); return first; }, listener);
             EngineSession sb = new EngineSession(b, (path,timeout) -> { check(path.equals(b.controlSocketPath()), "Second ICCID socket"); return second; }, listener)) {
            sa.connect(1000); sb.connect(1000);
            check("REGISTERED".equals(sa.getStatus().get("ims")), "First SIM status");
            check("UNREGISTERED".equals(sb.getStatus().get("ims")), "Second SIM status isolated");
            check(sa.getState().status.containsKey("futureField"), "Unknown status fields retained");
            sa.getCallSnapshot(); check(sa.getState().callVersion.revision == 3, "Snapshot epoch/revision retained");
            first.event("callState", Collections.emptyMap()); second.event("callState", Collections.emptyMap());
            EngineSession.Identity x = identities.poll(1, TimeUnit.SECONDS), y = identities.poll(1, TimeUnit.SECONDS);
            check(x != null && y != null && !x.equals(y), "Events retain distinct subscription identity");
            int before = first.requests.size();
            throwsType(SecurityException.class, () -> sa.dial("123"));
            throwsType(SecurityException.class, () -> sa.answer("fake"));
            throwsType(SecurityException.class, () -> sa.reject("fake"));
            throwsType(SecurityException.class, () -> sa.hangup("fake"));
            throwsType(SecurityException.class, () -> sa.cancelCall("fake"));
            throwsType(SecurityException.class, () -> sa.sendDtmf("fake", '1'));
            throwsType(SecurityException.class, () -> sa.audioStart("fake"));
            throwsType(SecurityException.class, () -> sa.audioStop("fake"));
            throwsType(SecurityException.class, () -> sa.openAudioTransport(500));
            throwsType(SecurityException.class, () -> sa.sendSmsText("123", "test"));
            check(first.requests.size() == before, "Default gate emits zero control requests");
            check(!a.toString().contains(a.iccid), "Identity diagnostic does not expose ICCID");
        }
        BlockingQueue<Map<String,Object>> controlRequests = new LinkedBlockingQueue<>();
        try (EngineSession session = new EngineSession(a, (p,t) -> new MemoryTransport((transport,r) -> {
            controlRequests.add(r); transport.reply(r, Collections.emptyMap());
        }), listener, true)) {
            session.connect(1000);
            session.getClientCapabilities();
            Map<?, ?> capabilityParams = (Map<?, ?>) controlRequests.take().get("params");
            check(((List<?>) capabilityParams.get("required")).contains("rpc.getStatus"), "Capabilities use established required list");
            session.dial("123");
            Map<?, ?> dialParams = (Map<?, ?>) controlRequests.take().get("params");
            check("tel:123".equals(dialParams.get("recipient")), "Fake dial carries recipient URI");
            session.sendSmsText("123", "synthetic offline text");
            Map<?, ?> smsParams = (Map<?, ?>) controlRequests.take().get("params");
            check("123".equals(smsParams.get("number")) && smsParams.containsKey("text"), "Fake text SMS contract");
            session.audioStart("fake-call");
            Map<?, ?> audioParams = (Map<?, ?>) controlRequests.take().get("params");
            check(EngineState.integer(audioParams.get("sampleRate")) == 16000 && EngineState.integer(audioParams.get("channels")) == 1, "Audio start format");
            session.sendDtmf("fake-call", '5');
            Map<?, ?> params = (Map<?, ?>) controlRequests.take().get("params");
            check(EngineState.integer(params.get("durationMs")) == 160, "DTMF uses positive standard duration");
            throwsType(IllegalArgumentException.class, () -> session.sendDtmf("fake-call", '5', -2));
            check(controlRequests.isEmpty(), "Private negative DTMF extension not emitted");
        }
        EngineState state = new EngineState().withCallSnapshot(EngineSession.map("epoch", "e1", "revision", 9));
        check(state.withCallSnapshot(EngineSession.map("epoch", "e1", "revision", 8)) == state, "Older revision cannot replace cache");
        check(state.withCallSnapshot(EngineSession.map("epoch", "e2", "revision", 1)).callVersion.epoch.equals("e2"), "New epoch may reset revision");
        throwsType(IOException.class, () -> state.withCallSnapshot(EngineSession.map("revision", 10)));
    }
    static void pendingDialCancellation() throws Exception {
        CountDownLatch dialing = new CountDownLatch(1), canceled = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        ExecutorService io = Executors.newSingleThreadExecutor();
        EngineSession.Listener listener = new EngineSession.Listener() {
            public void onEvent(EngineSession.Identity i, EngineState.Event e) {}
            public void onDisconnected(EngineSession.Identity i, Throwable e) {}
        };
        try (EngineSession session = new EngineSession(new EngineSession.Identity("3333333333333333333", 1, 19), (p, timeout) -> {
            opens.incrementAndGet();
            return new MemoryTransport((t, r) -> {
                String method = (String) r.get("method");
                if (method.equals("dial")) {
                    dialing.countDown();
                    try { if (!canceled.await(2, TimeUnit.SECONDS)) throw new IOException("Cancel was blocked behind dial"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                    t.frame(EngineSession.map("id", r.get("id"), "error", EngineSession.map("code", 2005, "message", "synthetic canceled")));
                } else if (method.equals("cancelCall")) {
                    canceled.countDown(); t.reply(r, EngineSession.map("accepted", true, "state", "IDLE"));
                } else t.reply(r, EngineSession.map("ims", "REGISTERED"));
            });
        }, listener, true)) {
            session.connect(1000);
            Future<?> dial = io.submit(() -> { throwsType(EngineRpcClient.RpcException.class, () -> session.dial("123")); return null; });
            check(dialing.await(1, TimeUnit.SECONDS), "Synthetic dial is pending");
            check("REGISTERED".equals(session.getStatus(500).get("ims")), "Status not blocked by pending dial");
            session.cancelCall("synthetic-call"); dial.get(1, TimeUnit.SECONDS);
            check(opens.get() >= 3, "Cancellation uses a different engine connection");
            check(session.isConnected(), "Short control connection does not retire observation connection");
        } finally { io.shutdownNow(); }
    }
    static void snapshotReadinessIsNotDisconnect() throws Exception {
        AtomicInteger phase = new AtomicInteger(), disconnects = new AtomicInteger();
        MemoryTransport peer = new MemoryTransport((t, r) -> {
            if ("getCallSnapshot".equals(r.get("method"))) {
                if (phase.get() == 1) {
                    t.reply(r, EngineSession.map("epoch", "ready-epoch", "revision", 7, "stage", "IN_CALL", "callId", "synthetic-active"));
                } else {
                    int code = phase.get() == 0 ? 3002 : 2006;
                    String message = code == 3002 ? "IMS \u672a\u6ce8\u518c" : "call_snapshot_unavailable";
                    t.frame(EngineSession.map("id", r.get("id"), "error", EngineSession.map("code", code, "message", message)));
                }
            } else t.reply(r, EngineSession.map("ims", phase.get() == 0 ? "UNREGISTERED" : "REGISTERED"));
        });
        EngineSession.Listener listener = new EngineSession.Listener() {
            public void onEvent(EngineSession.Identity i, EngineState.Event e) {}
            public void onDisconnected(EngineSession.Identity i, Throwable e) { disconnects.incrementAndGet(); }
        };
        try (EngineSession session = new EngineSession(new EngineSession.Identity("5555555555555555555", 1, 19), (p, timeout) -> peer, listener)) {
            session.connect(1000);
            session.getStatus();
            EngineRpcClient.RpcException unregistered = throwsType(EngineRpcClient.RpcException.class, session::getCallSnapshot);
            check(unregistered.code == 3002 && "IMS \u672a\u6ce8\u518c".equals(unregistered.remoteMessage()), "Native pre-registration error remains typed 3002");
            check(session.isConnected() && disconnects.get() == 0, "Readiness error does not retire RPC connection");
            check("UNREGISTERED".equals(session.getState().ims()) && session.getState().callVersion == null,
                    "Unavailable snapshot preserves status and does not fabricate IDLE/version");
            phase.set(1);
            check("REGISTERED".equals(session.getStatus().get("ims")), "Same socket remains usable after readiness error");
            session.getCallSnapshot();
            phase.set(2);
            EngineRpcClient.RpcException unavailable = throwsType(EngineRpcClient.RpcException.class, session::getCallSnapshot);
            check(unavailable.code == 2006, "Snapshot-unavailable has its distinct error code");
            check("REGISTERED".equals(session.getState().ims()) && "IN_CALL".equals(session.getState().callSnapshot.get("stage"))
                    && session.getState().callVersion.revision == 7, "Snapshot error cannot overwrite last registration/call fact");
            check(session.isConnected() && disconnects.get() == 0, "2006 does not produce disconnect callback");
        }
    }
    static void audioTransportLifecycle() throws Exception {
        AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
        EngineSession.Identity identity = new EngineSession.Identity("6666666666666666666", 1, 19);
        EngineSession.Listener listener = new EngineSession.Listener() {
            public void onEvent(EngineSession.Identity i, EngineState.Event e) {}
            public void onDisconnected(EngineSession.Identity i, Throwable e) {}
        };
        EngineRpcClient.Transport audio = new EngineRpcClient.Transport() {
            public InputStream input() { return InputStream.nullInputStream(); }
            public OutputStream output() { return new ByteArrayOutputStream(); }
            public void close() { closes.incrementAndGet(); }
        };
        try (EngineSession session = new EngineSession(identity, (path, timeout) -> {
            if (path.endsWith(".audio.sock")) {
                check(path.equals(identity.audioSocketPath()) && timeout == 321, "Audio factory receives identity path/deadline");
                opens.incrementAndGet(); return audio;
            }
            return new MemoryTransport((t, r) -> t.reply(r, Collections.emptyMap()));
        }, listener, true)) {
            throwsType(IOException.class, () -> session.openAudioTransport(321));
            check(opens.get() == 0, "Disconnected audio request never opens transport");
            session.connect(1000);
            throwsType(IllegalArgumentException.class, () -> session.openAudioTransport(0));
            EngineRpcClient.Transport first = session.openAudioTransport(321);
            first.close(); first.close();
            check(closes.get() == 1, "Audio transport close is idempotent");
            EngineRpcClient.Transport second = session.openAudioTransport(321);
            session.close(); second.close();
            check(closes.get() == 2, "Session closure releases active audio once");
            throwsType(IOException.class, () -> session.openAudioTransport(321));
            check(opens.get() == 2, "Closed session cannot open another relay");
        }
        CountDownLatch opening = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService io = Executors.newSingleThreadExecutor();
        int before = closes.get();
        try (EngineSession session = new EngineSession(identity, (path, timeout) -> {
            if (!path.endsWith(".audio.sock")) return new MemoryTransport((t, r) -> t.reply(r, Collections.emptyMap()));
            opening.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("Synthetic audio open deadline"); }
            catch (InterruptedException e) { throw new IOException(e); }
            return audio;
        }, listener, true)) {
            session.connect(1000);
            Future<?> pending = io.submit(() -> { throwsType(IOException.class, () -> session.openAudioTransport(321)); return null; });
            check(opening.await(1, TimeUnit.SECONDS), "Synthetic audio open is pending");
            session.close(); release.countDown(); pending.get(1, TimeUnit.SECONDS);
            check(closes.get() == before + 1, "Late audio open after session close is released");
        } finally { release.countDown(); io.shutdownNow(); }
    }
    public static void main(String[] args) throws Exception {
        jsonAndPcm(); correlationAndEvents(); failuresAndReconnect(); sessionsAndPolicy(); pendingDialCancellation(); snapshotReadinessIsNotDisconnect(); audioTransportLifecycle();
        System.out.println("PASS " + checks + " checks; in-memory transports only; no device, engine, call, or SMS execution.");
    }
}
