# SMS PDU codec host verification and integration contract

Run from PowerShell:

```powershell
& D:\NewVowifi\service\tests\sms\run.ps1
```

The runner uses the same JDK for `javac` and `java`, compiles with `--release 8`,
and requires no Gradle, Android runtime, network connection, phone, or SMS send.
Initial validation passed **135 assertions**. Android framework integration is
outside these host tests; `java.time` requires Android API 26 or desugaring.

## Public API

```java
SmsPduCodec.Submit message = SmsPduCodec.parseSubmit(tpduWithoutSmsc);
// recipient, text, messageRef, alphabet, concat, statusReportRequested,
// rejectDuplicates, firstOctet, validityPeriodFormat, getValidityPeriod()

List<byte[]> deliveries = SmsPduCodec.encodeDeliver(
    engineOriginator, engineText, engineTimestamp, explicitZoneOffset,
    concatReference16Bit);
// Each result is a full 3GPP SMS-DELIVER PDU, including an empty SMSC prefix.
```

All result types and exceptions are nested public types of
`com.newvowifi.ims.sms.SmsPduCodec`. `PduException` is checked; malformed input
and well-formed but unsupported semantics are distinct subclasses. No failure
returns substituted text or a best-effort destination.

`Concat` has `reference`, `referenceBits` (8 or 16), `totalParts`, and
`sequenceNumber`. It is null for an unsegmented message. Numeric recipients
retain a leading `+` when TON is international. Outbound engine text can use
numeric/international or GSM-alphabet alphanumeric originators. Alphanumeric
sender lengths are measured in useful semi-octets, not Java character count.

The supported SUBMIT subset is ordinary TP-PID 0, DCS 0 (GSM default alphabet
plus extension table), and DCS 8 (Android-compatible UTF-16BE). Well-formed
surrogate pairs are preserved. Only concatenation UDH elements are accepted;
port addressing, national-language shifts, EMS and unknown elements cannot be
represented by a number/text engine and are explicitly unsupported. Binary,
compressed, message-class/flash, MWI, reply-path and special-PID messages also
fail explicitly. Neither parser nor encoder generates a delivery report.

## Caller responsibilities

- `parseSubmit` expects the TPDU from Android IMS `sendSms`, without SMSC bytes.
  The separate SMSC argument is not prepended to the parser input.
- A multipart result is only one fragment. Assemble and validate all parts
  before sending to an engine with a number/text-only API. Do not silently send
  fragments as separate messages. TP-MR is not a globally unique transaction ID.
- The caller must account for IMS request tokens, retries and duplicates.
  `rejectDuplicates` is exposed but cannot implement network duplicate checking.
- Validity-period bytes are retained defensively by `getValidityPeriod()`.
  Parsing does not make a text-only engine enforce expiry. The integration must
  explicitly decide which validity-period policies it can honor.
- A requested status report remains a request. An engine's send acknowledgement
  permits a send-result callback according to that engine's contract; it is not
  evidence for an Android SMS delivery/status-report callback.
- Supply the actual engine receive timestamp. Encoding does not invent one.
  Time zones must be multiples of 15 minutes, and representable years are
  1990–2089. Fractional seconds are omitted because SCTS has second precision.
- Multipart incoming messages use 16-bit concatenation references. Allocate a
  distinct reference for each simultaneously active delivery. GSM7 capacity is
  160 septets singly / 152 per multipart segment; Unicode is 70 / 66 UTF-16 code
  units, with escape sequences and surrogate pairs never split across parts.

## Independent evidence and cases

The SUBMIT fixture comes from AOSP's `testRecipientAddress`, with its SMSC
prefix removed. The complete DELIVER golden result comes from `testAddressing`,
with only its SMSC changed to the required empty SMSC. Their expectations are
fixed bytes, independent of this codec's encoder or decoder:

- [AOSP GsmSmsTest](https://android.googlesource.com/platform/frameworks/opt/telephony/+/refs/heads/main/tests/telephonytests/src/com/android/internal/telephony/GsmSmsTest.java)
- [AOSP fixed historic addressing fixture](https://android.googlesource.com/platform/frameworks/base/+/b49a73dfc4c9817bba1f227e9330555acdf9b56f/telephony/tests/telephonytests/src/com/android/internal/telephony/GsmSmsTest.java)

Additional fixed vectors were manually assembled from the TPDU fields and an
independent LSB-first bit-string calculation. In particular,
`05 00 03 7A 02 01 90 65 36 FB 0D` is an 8-bit concat UDH plus `Hello` with
one padding bit; `1B CA 06 B5 49 6D 5E 1B DE A6 B7 F1 6D 80 9B F2 46 01`
contains the ten GSM default extension characters. Unicode, 16-bit UDH,
alphanumeric sender and timestamp vectors are literal expected byte arrays.

Boundary tests independently inspect encoded headers, UDL, segment byte counts,
and septets with a test-only bit-by-bit reader. They cover 160/161 septets,
extension characters at a segment boundary, 70/71 Unicode units, paired
surrogates at a boundary, and the 255-part limit. Every truncated prefix of a
valid UDH sample must produce a checked parse failure. Malformed lengths,
address filler, invalid sequences, duplicate UDH, dangling escapes, invalid
UTF-16, extra bytes and unsupported binary/control semantics are covered.

Protocol references used for layout cross-checking are primary AOSP sources:

- [SmsMessage parser/encoder and TS 23.040 references](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/main/com/android/internal/telephony/gsm/SmsMessage.java)
- [GsmAlphabet and TS 23.038 tables](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/telephony/common/com/android/internal/telephony/GsmAlphabet.java)
- [GsmSmsAddress and alphanumeric address lengths](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/telephony/java/com/android/internal/telephony/gsm/GsmSmsAddress.java)

AOSP fixtures are attributed to the Android Open Source Project; the source
test files are licensed under Apache License 2.0. This component does not
include or execute Android hidden framework classes.
