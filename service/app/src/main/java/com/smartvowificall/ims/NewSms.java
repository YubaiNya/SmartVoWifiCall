package com.smartvowificall.ims;

import android.telephony.SmsManager;
import android.telephony.ims.stub.ImsSmsImplBase;
import com.smartvowificall.ims.engine.EngineState;
import com.smartvowificall.ims.sms.SmsPduCodec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Mutable bridge state belongs to rt.worker; blocking engine RPC belongs to rt.io. */
final class NewSms extends ImsSmsImplBase {
  private static final int HISTORY_LIMIT = 512;
  private static final int INCOMING_LIMIT = 128;
  private static final long ASSEMBLY_SECONDS = 60;
  final NewMmTelFeature feature;
  private boolean ready;
  private int inboundToken = 1, nextConcatReference = 1;
  private final Map<String, Parts> assemblies = new HashMap<>();
  private final Set<Integer> activeOutgoing = new HashSet<>();
  private final LinkedHashMap<Integer, Boolean> completedOutgoing = new LinkedHashMap<>();
  private final LinkedHashMap<String, Long> closedAssemblies = new LinkedHashMap<>();
  private final LinkedHashMap<String, Incoming> incoming = new LinkedHashMap<>();
  private final Map<Integer, IncomingPart> awaitingAck = new HashMap<>();
  private final LinkedHashMap<String, Boolean> received = new LinkedHashMap<>(16, .75f, true);

  NewSms(NewMmTelFeature feature) {
    super(feature.rt.binderExecutor);
    this.feature = feature;
  }
  @Override
  public String getSmsFormat() {
    return "3gpp";
  }
  @Override
  public void onReady() {
    feature.rt.worker.execute(() -> {
      ready = true;
      for (Incoming message : new ArrayList<>(incoming.values())) deliver(message);
    });
  }
  static final class Part {
    final int token, ref;
    final SmsPduCodec.Submit submit;
    Part(int token, int ref, SmsPduCodec.Submit submit) {
      this.token = token;
      this.ref = ref;
      this.submit = submit;
    }
  }
  static final class Parts {
    final Part[] parts;
    final RuntimeController.Binding binding;
    final SmsPduCodec.Submit first;
    ScheduledFuture<?> expiry;
    Parts(int count, RuntimeController.Binding binding, SmsPduCodec.Submit first) {
      this.parts = new Part[count];
      this.binding = binding;
      this.first = first;
    }
  }
  @Override
  public void sendSms(int token, int ref, String format, String smsc, boolean retry, byte[] pdu) {
    final boolean entryAllowed = feature.rt.billable(feature.slot);
    final byte[] snapshot = pdu == null ? null : pdu.clone();
    feature.rt.worker.execute(
        () -> acceptSubmit(token, ref, format, retry, snapshot, entryAllowed));
  }
  private void acceptSubmit(
      int token, int ref, String format, boolean retry, byte[] pdu, boolean entryAllowed) {
    if (completedOutgoing.containsKey(token) || !activeOutgoing.add(token))
      return;
    RuntimeController.Binding binding = feature.binding();
    SmsPduCodec.Submit submit = null;
    try {
      if (!entryAllowed || !feature.rt.billable(feature.slot) || !ready || binding == null
          || !binding.registered || !"3gpp".equals(format)) {
        throw new IllegalStateException("SMS unavailable");
      }
      submit = SmsPduCodec.parseSubmit(pdu);
      // No engine contract for expiry, status reports, or uncertain-send retry.
      if (submit.statusReportRequested || retry || submit.rejectDuplicates
          || !(submit.validityPeriodFormat == 0
              || (submit.validityPeriodFormat == 2
                  && (submit.getValidityPeriod()[0] & 255) == 255))) {
        throw new IllegalArgumentException("Unsupported SMS semantics");
      }
      Part part = new Part(token, ref, submit);
      if (submit.concat == null) {
        send(binding, Collections.singletonList(part));
        return;
      }
      String key = assemblyKey(binding, submit);
      Long until = closedAssemblies.get(key);
      if (until != null) {
        if (System.nanoTime() < until)
          throw new IllegalStateException("Closed multipart message");
        closedAssemblies.remove(key);
      }
      Parts parts = assemblies.get(key);
      if (parts == null) {
        parts = new Parts(submit.concat.totalParts, binding, submit);
        assemblies.put(key, parts);
        Parts expected = parts;
        parts.expiry = feature.rt.worker.schedule(() -> {
          if (assemblies.get(key) == expected)
            abortAssembly(key, expected);
        }, ASSEMBLY_SECONDS, TimeUnit.SECONDS);
      }
      int index = submit.concat.sequenceNumber - 1;
      if (parts.binding != binding || parts.parts[index] != null
          || parts.first.alphabet != submit.alphabet
          || parts.first.validityPeriodFormat != submit.validityPeriodFormat
          || !Arrays.equals(parts.first.getValidityPeriod(), submit.getValidityPeriod())) {
        abortAssembly(key, parts);
        throw new IllegalArgumentException("Conflicting multipart segment");
      }
      parts.parts[index] = part;
      for (Part p : parts.parts)
        if (p == null)
          return;
      closeAssembly(key, parts);
      send(binding, Arrays.asList(parts.parts));
    } catch (Exception ignored) {
      // Unsupported later fragments must not leave earlier tokens hanging.
      if (binding != null && submit != null && submit.concat != null) {
        String key = assemblyKey(binding, submit);
        Parts parts = assemblies.get(key);
        if (parts != null)
          abortAssembly(key, parts);
      }
      complete(token, ref, false);
    }
  }
  private String assemblyKey(RuntimeController.Binding binding, SmsPduCodec.Submit submit) {
    return binding.sim.policy.simKey + "/" + submit.recipient + "/" + submit.concat.referenceBits
        + "/" + submit.concat.reference + "/" + submit.concat.totalParts;
  }
  private void closeAssembly(String key, Parts parts) {
    assemblies.remove(key, parts);
    if (parts.expiry != null)
      parts.expiry.cancel(false);
    closedAssemblies.put(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(ASSEMBLY_SECONDS));
    trim(closedAssemblies);
  }
  private void abortAssembly(String key, Parts parts) {
    closeAssembly(key, parts);
    for (Part p : parts.parts)
      if (p != null)
        complete(p.token, p.ref, false);
  }
  private void send(RuntimeController.Binding binding, List<Part> parts) {
    StringBuilder text = new StringBuilder();
    for (Part p : parts) text.append(p.submit.text);
    String body = text.toString();
    try {
      feature.rt.io.execute(() -> {
        boolean accepted = false;
        try {
          // Recheck before the side effect; EngineSession gates it again.
          if (!feature.rt.billable(feature.slot) || feature.binding() != binding || !binding.registered) {
            throw new IllegalStateException("SMS binding changed");
          }
          Map<String, Object> response =
              binding.engine.sendSmsText(parts.get(0).submit.recipient, body);
          Long code = EngineState.integer(response.get("statusCode"));
          accepted = code != null && code >= 200 && code < 300;
        } catch (Exception ignored) {
          // No automatic resend/fallback: an RPC timeout may mean uncertain acceptance.
        }
        final boolean result = accepted;
        feature.rt.worker.execute(() -> {
          for (Part p : parts) complete(p.token, p.ref, result);
        });
      });
    } catch (RuntimeException ignored) {
      for (Part p : parts) complete(p.token, p.ref, false);
    }
  }
  private void complete(int token, int ref, boolean success) {
    if (!activeOutgoing.remove(token))
      return;
    completedOutgoing.put(token, success);
    trim(completedOutgoing);
    // Commit terminal state before calling external code. Callback exceptions
    // cannot turn an accepted send into failure or affect other tokens.
    try {
      if (success)
        onSendSmsResultSuccess(token, ref);
      else
        onSendSmsResultError(token, ref, SEND_STATUS_ERROR, SmsManager.RESULT_ERROR_GENERIC_FAILURE,
            RESULT_NO_NETWORK_ERROR);
    } catch (RuntimeException ignored) {
    }
  }
  static final class Incoming {
    final String key, simKey;
    final int concatReference;
    final List<byte[]> pdus;
    final boolean[] acknowledged;
    final int[] tokens;
    Incoming(String key, String simKey, int ref, List<byte[]> pdus) {
      this.key = key;
      this.simKey = simKey;
      this.concatReference = ref;
      this.pdus = pdus;
      acknowledged = new boolean[pdus.size()];
      tokens = new int[pdus.size()];
    }
  }
  static final class IncomingPart {
    final Incoming message;
    final int index;
    IncomingPart(Incoming message, int index) {
      this.message = message;
      this.index = index;
    }
  }
  void received(Map<String, Object> data) {
    RuntimeController.Binding binding = feature.binding();
    Map<String, Object> snapshot = new HashMap<>(data);
    feature.rt.worker.execute(() -> acceptIncoming(binding, snapshot));
  }
  private void acceptIncoming(RuntimeController.Binding binding, Map<String, Object> data) {
    if (binding == null || feature.binding() != binding)
      return;
    String id = EngineState.text(data, "smsId");
    String number = EngineState.text(data, "number");
    String text = EngineState.text(data, "text");
    if (id == null || id.isEmpty() || number == null || text == null)
      return;
    String key = binding.sim.policy.simKey + "\u0000" + id;
    if (received.get(key) != null)
      return;
    Incoming message = incoming.get(key);
    if (message == null) {
      if (incoming.size() >= INCOMING_LIMIT)
        return; // Never mark this as delivered.
      try {
        Long time = EngineState.integer(data.get("timestamp"));
        // Missing engine time means local arrival time, not claimed network SCTS.
        Instant when = time == null ? Instant.now()
            : time < 100000000000L  ? Instant.ofEpochSecond(time)
                                    : Instant.ofEpochMilli(time);
        int ref = allocateConcatReference();
        List<byte[]> pdus = SmsPduCodec.encodeDeliver(number, text, when, ZoneOffset.UTC, ref);
        message = new Incoming(key, binding.sim.policy.simKey, ref, pdus);
        incoming.put(key, message);
      } catch (Exception ignored) {
        return;
      }
    }
    // Replays retry failed/unsubmitted parts only, never ACKed or in-flight parts.
    deliver(message);
  }
  private int allocateConcatReference() {
    for (;;) {
      int candidate = nextConcatReference++ & 65535;
      boolean used = false;
      for (Incoming value : incoming.values())
        if (value.concatReference == candidate)
          used = true;
      if (!used)
        return candidate;
    }
  }
  private int allocateInboundToken() {
    for (;;) {
      if (inboundToken <= 0)
        inboundToken = 1;
      int token = inboundToken++;
      if (!awaitingAck.containsKey(token))
        return token;
    }
  }
  private void deliver(Incoming message) {
    RuntimeController.Binding binding = feature.binding();
    if (!ready || binding == null || !message.simKey.equals(binding.sim.policy.simKey))
      return;
    for (int i = 0; i < message.pdus.size(); i++) {
      if (message.acknowledged[i] || message.tokens[i] != 0)
        continue;
      int token = allocateInboundToken();
      message.tokens[i] = token;
      awaitingAck.put(token, new IncomingPart(message, i));
      try {
        onSmsReceived(token, "3gpp", message.pdus.get(i).clone());
      } catch (RuntimeException ignored) {
        awaitingAck.remove(token);
        message.tokens[i] = 0;
      }
    }
  }
  @Override
  public void acknowledgeSms(int token, int messageRef, int result) {
    feature.rt.worker.execute(() -> {
      IncomingPart part = awaitingAck.remove(token);
      if (part == null)
        return;
      Incoming message = part.message;
      message.tokens[part.index] = 0;
      if (result == DELIVER_STATUS_OK)
        message.acknowledged[part.index] = true;
      for (boolean ack : message.acknowledged)
        if (!ack)
          return;
      incoming.remove(message.key, message);
      received.put(message.key, true);
      trim(received);
    });
  }
  @Override
  public void acknowledgeSms(int token, int messageRef, int result, byte[] pdu) {
    acknowledgeSms(token, messageRef, result);
  }
  private static <K, V> void trim(LinkedHashMap<K, V> map) {
    while (map.size() > HISTORY_LIMIT) map.remove(map.keySet().iterator().next());
  }
}
