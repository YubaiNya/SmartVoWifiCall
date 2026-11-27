package com.smartvowificall.ims.lpa;

import java.net.IDN;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Input validation shared by UI, private transport and host tests. Never logs credentials. */
public final class LpaProtocol {
  public static final String ISDR_AID = "A0000005591010FFFFFFFF8900000100";
  private LpaProtocol() {}

  public static final class Activation {
    public final String server, matchingId;
    private Activation(String server, String matchingId) {
      this.server = server;
      this.matchingId = matchingId;
    }
    @Override public String toString() { return "Activation[redacted]"; }
  }

  public static Activation activation(String raw) {
    if (raw == null || raw.length() > 4096) throw new IllegalArgumentException("LPA_CODE_INVALID");
    String text = raw.trim();
    if (text.regionMatches(true, 0, "LPA:", 0, 4)) text = text.substring(4);
    String[] fields = text.split("\\$", -1);
    if (fields.length != 3 || !"1".equals(fields[0]))
      throw new IllegalArgumentException("LPA_CODE_FORMAT_UNSUPPORTED");
    String server = server(fields[1]);
    if (fields[2].isEmpty() || fields[2].length() > 1024 || !fields[2].matches("[!-#%-~]+"))
      throw new IllegalArgumentException("LPA_CODE_INVALID");
    return new Activation(server, fields[2]);
  }

  public static String server(String input) {
    if (input == null || input.length() > 253 || !input.equals(input.trim()) || input.endsWith("."))
      throw new IllegalArgumentException("LPA_SERVER_INVALID");
    final String host;
    try { host = IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
    catch (IllegalArgumentException e) { throw new IllegalArgumentException("LPA_SERVER_INVALID"); }
    if (!host.contains(".") || host.matches("[0-9.]+") || host.length() > 253)
      throw new IllegalArgumentException("LPA_SERVER_INVALID");
    for (String label : host.split("\\.", -1)) {
      if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
        throw new IllegalArgumentException("LPA_SERVER_INVALID");
    }
    return host;
  }

  public static byte[] nickname(String text) {
    if (text == null || text.indexOf('\0') >= 0) throw new IllegalArgumentException("LPA_NAME_INVALID");
    try {
      ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(text));
      byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes);
      if (bytes.length >= 64) throw new IllegalArgumentException("LPA_NAME_TOO_LONG");
      return bytes;
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new IllegalArgumentException("LPA_NAME_INVALID");
    }
  }

  public static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) out.append(Character.forDigit((value & 255) >>> 4, 16))
        .append(Character.forDigit(value & 15, 16));
    return out.toString().toUpperCase(Locale.ROOT);
  }

  public static byte[] unhex(String value) {
    if (value == null || value.length() % 2 != 0 || !value.matches("[0-9a-fA-F]*"))
      throw new IllegalArgumentException("LPA_APDU_INVALID");
    byte[] out = new byte[value.length() / 2];
    for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
    return out;
  }

  public static void validateApdu(byte[] bytes) {
    if (bytes.length < 5 || bytes.length > 261 || (bytes[0] & 0xF0) != 0x80)
      throw new IllegalArgumentException("LPA_APDU_INVALID");
    int instruction = bytes[1] & 255, length = bytes[4] & 255;
    if (instruction == 0xC0) {
      if (bytes.length != 5 || bytes[2] != 0 || bytes[3] != 0)
        throw new IllegalArgumentException("LPA_APDU_INVALID");
    } else if (instruction == 0xE2) {
      if (length == 0 || (bytes.length != length + 5 && bytes.length != length + 6))
        throw new IllegalArgumentException("LPA_APDU_INVALID");
    } else throw new IllegalArgumentException("LPA_APDU_NOT_ALLOWED");
  }

  public static String mask(String value) {
    return value == null || value.length() < 5 ? "••••" : "•••• " + value.substring(value.length() - 4);
  }
}
