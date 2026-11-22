package com.smartvowificall.ims.sms;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Text-only 3GPP SMS boundary. No Android classes, I/O, transmission, or receipts.
 *
 * SUBMIT input is the TPDU supplied to Android IMS sendSms, WITHOUT an SMSC
 * prefix. DELIVER output includes a zero-length SMSC prefix for createFromPdu.
 * A multipart Submit is a fragment: callers must assemble all parts before
 * handing it to an engine which accepts only a destination and a text string.
 * Parsed validity periods and report requests are metadata, not promises that
 * such an engine can honor them. Engine acceptance is NOT delivery confirmation.
 *
 * Layout: 3GPP TS 23.040, sections 9.2.2/9.2.3; alphabet: TS 23.038.
 * DCS 08 uses Android-compatible UTF-16BE, including valid surrogate pairs.
 */
public final class SmsPduCodec {
  private SmsPduCodec() {}

  public enum Alphabet { GSM7, UCS2 }

  public static class PduException extends Exception {
    PduException(String message) {
      super(message);
    }
  }
  public static final class MalformedPduException extends PduException {
    public MalformedPduException(String message) {
      super(message);
    }
  }
  public static final class UnsupportedPduException extends PduException {
    public UnsupportedPduException(String message) {
      super(message);
    }
  }

  public static final class Concat {
    public final int reference;
    public final int referenceBits;
    public final int totalParts;
    public final int sequenceNumber;
    private Concat(int reference, int referenceBits, int total, int sequence) {
      this.reference = reference;
      this.referenceBits = referenceBits;
      this.totalParts = total;
      this.sequenceNumber = sequence;
    }
  }

  public static final class Submit {
    public final String recipient;
    public final String text;
    public final int messageRef;
    public final boolean statusReportRequested;
    public final boolean rejectDuplicates;
    public final Alphabet alphabet;
    public final Concat concat;
    public final int firstOctet;
    public final int validityPeriodFormat;
    private final byte[] validityPeriod;
    private Submit(String recipient, String text, int reference, int first, Alphabet alphabet,
        Concat concat, int vpf, byte[] vp) {
      this.recipient = recipient;
      this.text = text;
      this.messageRef = reference;
      this.statusReportRequested = (first & 0x20) != 0;
      this.rejectDuplicates = (first & 4) != 0;
      this.alphabet = alphabet;
      this.concat = concat;
      this.firstOctet = first;
      this.validityPeriodFormat = vpf;
      this.validityPeriod = vp.clone();
    }
    public byte[] getValidityPeriod() {
      return validityPeriod.clone();
    }
  }

  // TS 23.038 default alphabet, index is the septet value. ESC is handled separately.
  private static final String BASIC =
      "@\u00a3$\u00a5\u00e8\u00e9\u00f9\u00ec\u00f2\u00c7\n\u00d8\u00f8\r\u00c5\u00e5"
      + "\u0394_"
        + "\u03a6\u0393\u039b\u03a9\u03a0\u03a8\u03a3\u0398\u039e\u001b\u00c6\u00e6\u00df\u00c9"
      + " !\"#\u00a4%&'()*+,-./"
      + "0123456789:;<=>?"
      + "\u00a1ABCDEFGHIJKLMNOPQRSTUVWXYZ\u00c4\u00d6\u00d1\u00dc\u00a7"
      + "\u00bfabcdefghijklmnopqrstuvwxyz\u00e4\u00f6\u00f1\u00fc\u00e0";
  private static final String EXT_CHARS = "\f^{}\\[~]|\u20ac";
  private static final int[] EXT_CODES = {10, 20, 40, 41, 47, 60, 61, 62, 64, 101};

  /** Decode ordinary text SMS-SUBMIT. Unsupported transport semantics fail closed. */
  public static Submit parseSubmit(byte[] tpdu) throws PduException {
    if (tpdu == null)
      throw malformed("Null TPDU");
    Cursor c = new Cursor(tpdu);
    int first = c.u8();
    if ((first & 3) != 1)
      throw unsupported("Expected SMS-SUBMIT TPDU without SMSC prefix");
    if ((first & 0x80) != 0)
      throw unsupported("Reply-path SMS cannot be represented by text engine");
    int mr = c.u8();
    int digits = c.u8();
    int toa = c.u8();
    String recipient = readRecipient(c, digits, toa);
    if (c.u8() != 0)
      throw unsupported("Non-default TP-PID");
    int dcs = c.u8();
    Alphabet alphabet;
    if (dcs == 0)
      alphabet = Alphabet.GSM7;
    else if (dcs == 8)
      alphabet = Alphabet.UCS2;
    else
      throw unsupported("DCS " + dcs + ": binary, compressed, class or MWI semantics unsupported");
    int vpf = (first >>> 3) & 3;
    byte[] vp = c.take(vpf == 0 ? 0 : vpf == 2 ? 1 : 7);
    int udl = c.u8();
    int udBytes = alphabet == Alphabet.GSM7 ? (udl * 7 + 7) / 8 : udl;
    if (udBytes > 140 || (alphabet == Alphabet.GSM7 && udl > 160)) {
      throw malformed("User data exceeds SMS capacity");
    }
    byte[] ud = c.take(udBytes);
    if (c.remaining() != 0)
      throw malformed("Trailing bytes after TP-UD");
    int headerBytes = 0;
    Concat concat = null;
    if ((first & 0x40) != 0) {
      if (ud.length == 0)
        throw malformed("UDHI with empty user data");
      headerBytes = (ud[0] & 255) + 1;
      if (headerBytes > ud.length)
        throw malformed("UDH exceeds user data");
      concat = parseHeader(ud, headerBytes);
    }
    String text;
    if (alphabet == Alphabet.GSM7) {
      int headerSeptets = (headerBytes * 8 + 6) / 7;
      if (headerSeptets > udl)
        throw malformed("UDH exceeds septet count");
      text = decodeSeptets(ud, headerSeptets * 7, udl - headerSeptets);
    } else {
      int length = ud.length - headerBytes;
      if ((length & 1) != 0)
        throw malformed("Odd UTF-16BE byte count");
      try {
        text = StandardCharsets.UTF_16BE.newDecoder()
                   .onMalformedInput(CodingErrorAction.REPORT)
                   .onUnmappableCharacter(CodingErrorAction.REPORT)
                   .decode(ByteBuffer.wrap(ud, headerBytes, length))
                   .toString();
      } catch (CharacterCodingException e) {
        throw malformed("Invalid UTF-16BE payload");
      }
    }
    return new Submit(recipient, text, mr, first, alphabet, concat, vpf, vp);
  }

  private static String readRecipient(Cursor c, int digits, int toa) throws PduException {
    if ((toa & 0x80) == 0)
      throw malformed("Address type extension bit is not set");
    int ton = (toa >>> 4) & 7;
    int npi = toa & 15;
    if (ton != 0 && ton != 1 && ton != 2)
      throw unsupported("Non-numeric destination type");
    if (npi != 0 && npi != 1)
      throw unsupported("Unsupported destination numbering plan");
    if (digits < 1 || digits > 20)
      throw malformed("Destination length outside 1..20 digits");
    byte[] value = c.take((digits + 1) / 2);
    StringBuilder result = new StringBuilder(ton == 1 ? "+" : "");
    for (int i = 0; i < digits; i++) {
      int nibble = (value[i / 2] >>> ((i & 1) * 4)) & 15;
      if (nibble == 15)
        throw malformed("Premature destination filler");
      if (nibble > 9)
        throw unsupported("Special BCD destination digits unsupported");
      result.append((char) ('0' + nibble));
    }
    if ((digits & 1) != 0 && ((value[value.length - 1] >>> 4) & 15) != 15) {
      throw malformed("Missing odd-length address filler");
    }
    return result.toString();
  }

  private static Concat parseHeader(byte[] ud, int end) throws PduException {
    int p = 1;
    Concat concat = null;
    while (p < end) {
      if (p + 2 > end)
        throw malformed("Truncated UDH element");
      int iei = ud[p++] & 255;
      int length = ud[p++] & 255;
      if (p + length > end)
        throw malformed("UDH element exceeds declared header");
      if (iei != 0 && iei != 8) {
        throw unsupported("UDH element " + iei + " cannot be preserved by text engine");
      }
      if (concat != null)
        throw malformed("Duplicate concatenation header");
      if (length != (iei == 0 ? 3 : 4))
        throw malformed("Invalid concatenation element length");
      int reference = ud[p++] & 255;
      if (iei == 8)
        reference = (reference << 8) | (ud[p++] & 255);
      int total = ud[p++] & 255;
      int sequence = ud[p++] & 255;
      if (total == 0 || sequence == 0 || sequence > total)
        throw malformed("Invalid concatenation sequence");
      concat = new Concat(reference, iei == 0 ? 8 : 16, total, sequence);
    }
    return concat;
  }

  /**
   * Produce one or more complete SMS-DELIVER PDUs from engine text.
   * Callers supply the engine's actual receive time and an explicit timezone.
   * Use a distinct concatReference (0..65535) for each multipart delivery.
   * Returned arrays are new buffers. No SMS-STATUS-REPORT is ever produced.
   */
  public static List<byte[]> encodeDeliver(String originator, String text, Instant timestamp,
      ZoneOffset offset, int concatReference) throws PduException {
    if (originator == null || text == null || timestamp == null || offset == null) {
      throw malformed("Null delivery input");
    }
    if (concatReference < 0 || concatReference > 65535)
      throw malformed("Invalid concat reference");
    validateUnicode(text);
    byte[] address = encodeOriginator(originator);
    byte[] scts = encodeTimestamp(timestamp, offset);
    boolean gsm = isGsm(text);
    int totalUnits = gsm ? septetLength(text) : text.length();
    int singleLimit = gsm ? 160 : 70;
    boolean multipart = totalUnits > singleLimit;
    // Seven header octets for 16-bit concat: exactly eight septets, no padding.
    int partLimit = multipart ? (gsm ? 152 : 66) : singleLimit;
    List<String> parts = splitText(text, partLimit, gsm);
    if (parts.size() > 255)
      throw unsupported("More than 255 SMS parts");
    List<byte[]> result = new ArrayList<byte[]>(parts.size());
    for (int part = 0; part < parts.size(); part++) {
      byte[] header = multipart
          ? new byte[] {6, 8, 4, (byte) (concatReference >>> 8), (byte) concatReference,
                (byte) parts.size(), (byte) (part + 1)}
          : new byte[0];
      byte[] userData;
      int udl;
      if (gsm) {
        int[] septets = encodeSeptets(parts.get(part));
        int headerSeptets = (header.length * 8 + 6) / 7;
        udl = headerSeptets + septets.length;
        userData = new byte[(udl * 7 + 7) / 8];
        System.arraycopy(header, 0, userData, 0, header.length);
        writeSeptets(userData, headerSeptets * 7, septets);
      } else {
        byte[] body = parts.get(part).getBytes(StandardCharsets.UTF_16BE);
        userData = new byte[header.length + body.length];
        System.arraycopy(header, 0, userData, 0, header.length);
        System.arraycopy(body, 0, userData, header.length, body.length);
        udl = userData.length;
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream(180);
      out.write(0); // SMSC length, not part of TPDU.
      out.write(multipart ? 0x44 : 0x04); // DELIVER, no more messages, optional UDH.
      out.write(address, 0, address.length);
      out.write(0); // PID, ordinary SMS.
      out.write(gsm ? 0 : 8);
      out.write(scts, 0, scts.length);
      out.write(udl);
      out.write(userData, 0, userData.length);
      result.add(out.toByteArray());
    }
    return Collections.unmodifiableList(result);
  }

  private static byte[] encodeOriginator(String sender) throws PduException {
    if (sender.length() == 0)
      throw malformed("Empty originator");
    boolean international = sender.charAt(0) == '+';
    String number = international ? sender.substring(1) : sender;
    boolean numeric = number.length() != 0;
    for (int i = 0; i < number.length(); i++)
      numeric &= number.charAt(i) >= '0' && number.charAt(i) <= '9';
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (numeric) {
      if (number.length() > 20)
        throw unsupported("Originator exceeds 20 digits");
      out.write(number.length());
      out.write(international ? 0x91 : 0x81);
      for (int i = 0; i < number.length(); i += 2) {
        int low = number.charAt(i) - '0';
        int high = i + 1 < number.length() ? number.charAt(i + 1) - '0' : 15;
        out.write(low | high << 4);
      }
    } else {
      if (international)
        throw unsupported("Non-numeric international originator");
      if (!isGsm(sender))
        throw unsupported("Alphanumeric originator requires GSM alphabet");
      int[] septets = encodeSeptets(sender);
      if (septets.length > 11)
        throw unsupported("Alphanumeric originator exceeds 11 septets");
      // TP-OA length is useful semi-octets, including for alphanumeric TON.
      out.write((septets.length * 7 + 3) / 4);
      out.write(0xd0); // TON=alphanumeric, NPI=unknown.
      byte[] packed = new byte[(septets.length * 7 + 7) / 8];
      writeSeptets(packed, 0, septets);
      out.write(packed, 0, packed.length);
    }
    return out.toByteArray();
  }

  private static byte[] encodeTimestamp(Instant timestamp, ZoneOffset zone) throws PduException {
    int seconds = zone.getTotalSeconds();
    if (seconds % 900 != 0)
      throw unsupported("SMS timestamp requires 15-minute timezone units");
    OffsetDateTime local;
    try {
      local = timestamp.atOffset(zone);
    } catch (RuntimeException e) {
      throw malformed("Timestamp outside supported range");
    }
    // Framework decoders resolve 00..89 to 2000..2089 and 90..99 to 1990..1999.
    if (local.getYear() < 1990 || local.getYear() > 2089)
      throw unsupported("Ambiguous two-digit SMS year");
    int quarters = Math.abs(seconds / 900);
    byte tz = bcd(quarters);
    if (seconds < 0)
      tz |= 8; // Sign is bit 3 of the first semi-octet.
    return new byte[] {bcd(local.getYear() % 100), bcd(local.getMonthValue()),
        bcd(local.getDayOfMonth()), bcd(local.getHour()), bcd(local.getMinute()),
        bcd(local.getSecond()), tz};
  }

  private static byte bcd(int n) {
    return (byte) ((n % 10) << 4 | n / 10);
  }

  private static String decodeSeptets(byte[] data, int bitOffset, int count) throws PduException {
    StringBuilder result = new StringBuilder(count);
    boolean escaped = false;
    for (int i = 0; i < count; i++) {
      int bit = bitOffset + i * 7;
      int value = (data[bit / 8] & 255) >>> (bit & 7);
      if ((bit & 7) > 1)
        value |= (data[bit / 8 + 1] & 255) << (8 - (bit & 7));
      value &= 127;
      if (escaped) {
        int index = -1;
        for (int k = 0; k < EXT_CODES.length; k++)
          if (EXT_CODES[k] == value)
            index = k;
        if (index < 0)
          throw unsupported("Undefined GSM extension code " + value);
        result.append(EXT_CHARS.charAt(index));
        escaped = false;
      } else if (value == 27)
        escaped = true;
      else
        result.append(BASIC.charAt(value));
    }
    if (escaped)
      throw malformed("Dangling GSM escape");
    return result.toString();
  }

  private static int charCost(char ch) {
    int i = BASIC.indexOf(ch);
    if (i >= 0 && i != 27)
      return 1;
    return EXT_CHARS.indexOf(ch) >= 0 ? 2 : -1;
  }
  private static boolean isGsm(String s) {
    for (int i = 0; i < s.length(); i++)
      if (charCost(s.charAt(i)) < 0)
        return false;
    return true;
  }
  private static int septetLength(String s) {
    int length = 0;
    for (int i = 0; i < s.length(); i++) length += charCost(s.charAt(i));
    return length;
  }
  private static int[] encodeSeptets(String text) {
    int[] result = new int[septetLength(text)];
    int p = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      int basic = BASIC.indexOf(ch);
      if (basic >= 0 && basic != 27)
        result[p++] = basic;
      else {
        result[p++] = 27;
        result[p++] = EXT_CODES[EXT_CHARS.indexOf(ch)];
      }
    }
    return result;
  }
  private static void writeSeptets(byte[] out, int firstBit, int[] values) {
    for (int i = 0; i < values.length; i++) {
      int bit = firstBit + i * 7;
      int index = bit / 8;
      int shift = bit & 7;
      out[index] |= (byte) (values[i] << shift);
      if (shift > 1)
        out[index + 1] |= (byte) (values[i] >>> (8 - shift));
    }
  }
  private static List<String> splitText(String text, int limit, boolean gsm) {
    List<String> parts = new ArrayList<String>();
    int start = 0, used = 0;
    for (int i = 0; i < text.length();) {
      int chars = Character.charCount(text.codePointAt(i));
      int units = gsm ? charCost(text.charAt(i)) : chars;
      if (used + units > limit) {
        parts.add(text.substring(start, i));
        start = i;
        used = 0;
      }
      used += units;
      i += chars;
    }
    parts.add(text.substring(start));
    return parts;
  }
  private static void validateUnicode(String s) throws MalformedPduException {
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (Character.isHighSurrogate(ch)) {
        if (++i == s.length() || !Character.isLowSurrogate(s.charAt(i)))
          throw malformed("Unpaired high surrogate");
      } else if (Character.isLowSurrogate(ch))
        throw malformed("Unpaired low surrogate");
    }
  }

  private static MalformedPduException malformed(String message) {
    return new MalformedPduException(message);
  }
  private static UnsupportedPduException unsupported(String message) {
    return new UnsupportedPduException(message);
  }

  private static final class Cursor {
    private final byte[] data;
    private int position;
    Cursor(byte[] data) {
      this.data = data;
    }
    int remaining() {
      return data.length - position;
    }
    int u8() throws MalformedPduException {
      if (remaining() < 1)
        throw malformed("Truncated TPDU at offset " + position);
      return data[position++] & 255;
    }
    byte[] take(int length) throws MalformedPduException {
      if (length < 0 || length > remaining())
        throw malformed("Truncated field at offset " + position);
      byte[] value = Arrays.copyOfRange(data, position, position + length);
      position += length;
      return value;
    }
  }
}
