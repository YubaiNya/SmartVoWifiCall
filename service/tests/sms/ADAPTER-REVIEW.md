# NewSms adapter review and verified repair

## Dispatcher ordering: no demonstrated AOSP multipart deadlock

The current AOSP `SMSDispatcher.sendMultipartText` creates all trackers and
passes them to `sendSubmitPdu(trackers)`. Its `sendRawPdu` loops over the trackers
and calls `sendSms` for each. `ImsSmsDispatcher.sendSms` allocates a token,
registers that tracker and invokes the IMS Binder; it does not wait for a send
result before the next segment is submitted.

Sources inspected:

- [SMSDispatcher](https://android.googlesource.com/platform/frameworks/opt/telephony/+/refs/heads/main/src/java/com/android/internal/telephony/SMSDispatcher.java), methods `sendMultipartText`, `sendSubmitPdu`, `sendRawPdu`.
- [ImsSmsDispatcher](https://android.googlesource.com/platform/frameworks/opt/telephony/+/refs/heads/main/src/java/com/android/internal/telephony/ImsSmsDispatcher.java), methods `sendSms`, `onSendSmsResult`, `onSmsReceived`.
- [ImsSmsImplBase](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/android/telephony/ims/stub/ImsSmsImplBase.java), send-result exceptions and inbound acknowledgment contract.

Thus the assembler must not report premature success to avoid a hypothetical
serial-dispatch deadlock. This is verified for the inspected AOSP implementation;
it is not a claim that the phone's OEM dispatcher was independently decompiled.

## Actual defects and changes

1. The old `send` wrapped RPC and every success callback in the same try/catch.
   If one success callback threw, it reported failure for every segment,
   including already successful tokens. The failure loop could itself throw,
   leaving subsequent tokens uncompleted. A token now enters terminal state
   before its callback, and callback failures are isolated. An accepted send
   never becomes a failed send merely because the listener is unavailable.

2. A duplicate token could be failed while its original pending entry later
   succeeded. Conflicting duplicate segments could leave an older group alive.
   Active/completed tokens now suppress duplicate calls. Conflicting segments
   fail the pending group; a 60-second closed-group record rejects late parts
   instead of reopening the same group. Encoding and validity metadata must
   agree. Unsupported later fragments abort already identified pending groups.

3. The blocking engine RPC occupied the single state worker. It now runs on
   `rt.io`; completion returns to `rt.worker`. The paid-operation gate is checked
   at entry, on the state worker, and immediately before RPC; the existing
   EngineSession gate remains in place. A stale binding cannot begin a send.
   No timeout causes an automatic resend or CS fallback.

4. Incoming messages were marked received as soon as `onSmsReceived` returned,
   without overriding `acknowledgeSms`. A framework storage failure therefore
   still suppressed later events. The adapter now tracks tokens per incoming
   PDU and waits for `DELIVER_STATUS_OK` for every segment before marking the
   engine message delivered. Both acknowledgment overloads are implemented.
   Replayed events retry failed/unsubmitted segments only, preserving the same
   PDU/timestamp/concatenation reference; acknowledged and in-flight segments
   are not reinjected.

5. Before `onReady`, incoming events were dropped. They are now queued in the
   adapter and drained when ready. Delivery/dedup keys include SIM identity so
   the same engine message ID on a different SIM is not suppressed. The old
   `received.clear()` after 512 messages was replaced with individual LRU
   eviction, preserving recent entries.

6. `smsId.hashCode() & 65535` gave collisions such as Java strings `Aa` and `BB`.
   Incoming concatenation references are now allocated sequentially and skip
   references held by current pending deliveries.

Unsupported binary/control PDUs, delivery-report requests, explicit retries,
TP-RD duplicate-suppression semantics, and unsupported expiry policy still fail
before engine transmission. An engine 2xx acceptance yields only a send result;
there is no generated SMS-STATUS-REPORT.

## Host verification

```powershell
& D:\NewVowifi\service\tests\sms\run-adapter.ps1
```

**52 assertions passed**, compiling the actual `NewSms.java`, actual PDU codec
and actual EngineState with Java 8 compatibility. Only Android listener, binding,
engine and executor boundaries are mocked. Tests are deterministic and perform
no network, device access or SMS transmission.

The tests cover batch dispatch without early callbacks, exactly one mock engine
command, queue separation, success/failure callback exceptions, timeouts,
duplicate tokens/segments, closed groups, unsupported later parts, authorization
and binding changes before RPC, uncertain RPC failure, readiness queueing,
positive/negative/duplicate ACK, partial multipart ACK retry, listener failures,
SIM-isolated dedup, LRU behavior after 513 deliveries and concat hash collisions.

## Scope of the guarantees

The state is in memory. This is not a durable inbox or an exactly-once guarantee
across app/process restarts. At most 128 incoming messages wait for ACK; excess
events are not marked delivered. Negative ACKs are retained and retried on an
engine event replay or a new `onReady`; there is no undocumented engine ACK or
replay RPC. Lost ACKs remain pending rather than risking a blind duplicate.

History contains the most recent 512 completed outgoing tokens and successful
incoming IDs. Legitimate multipart reference reuse during the 60-second closed
window is conservatively rejected because the text-only contract does not
provide a stronger transaction identity. If the engine omits its timestamp,
local arrival time is used explicitly; this is not claimed to be the original
network service-centre timestamp.

These limits must remain visible in release claims. Android SDK compilation,
Binder integration and actual device receive behavior are separate checks
owned by the coordinating task.
