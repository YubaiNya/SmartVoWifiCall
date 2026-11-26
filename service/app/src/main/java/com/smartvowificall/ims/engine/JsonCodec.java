package com.smartvowificall.ims.engine;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, dependency-free JSON codec for the engine's line protocol. Never logs payloads. */
public final class JsonCodec {
  private JsonCodec() {}

  public static String encode(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value, 0);
    return out.toString();
  }

  public static Object decode(String text) throws IOException {
    Parser p = new Parser(text);
    Object value = p.value(0);
    p.space();
    if (p.at != text.length())
      throw p.error();
    return value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> decodeObject(String text) throws IOException {
    Object value = decode(text);
    if (!(value instanceof Map))
      throw new IOException("Expected a JSON object");
    return (Map<String, Object>) value;
  }

  private static void append(StringBuilder out, Object value, int depth) {
    if (depth > 64)
      throw new IllegalArgumentException("JSON nesting too deep");
    if (value == null) {
      out.append("null");
      return;
    }
    if (value instanceof String) {
      quote(out, (String) value);
      return;
    }
    if (value instanceof Boolean) {
      out.append(value);
      return;
    }
    if (value instanceof Number) {
      if (value instanceof Double && !Double.isFinite((Double) value)
          || value instanceof Float && !Float.isFinite((Float) value)) {
        throw new IllegalArgumentException("Non-finite JSON number");
      }
      out.append(value);
      return;
    }
    if (value instanceof Map) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
        if (!(e.getKey() instanceof String))
          throw new IllegalArgumentException("JSON object key");
        if (!first)
          out.append(',');
        first = false;
        quote(out, (String) e.getKey());
        out.append(':');
        append(out, e.getValue(), depth + 1);
      }
      out.append('}');
      return;
    }
    if (value instanceof Iterable) {
      out.append('[');
      boolean first = true;
      for (Object item : (Iterable<?>) value) {
        if (!first)
          out.append(',');
        first = false;
        append(out, item, depth + 1);
      }
      out.append(']');
      return;
    }
    throw new IllegalArgumentException("Unsupported JSON value type");
  }

  private static void quote(StringBuilder out, String text) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 32) {
            out.append("\\u");
            String h = Integer.toHexString(c);
            for (int j = h.length(); j < 4; j++) out.append('0');
            out.append(h);
          } else
            out.append(c);
      }
    }
    out.append('"');
  }

  private static final class Parser {
    final String text;
    int at;
    Parser(String text) {
      this.text = text;
    }
    IOException error() {
      return new IOException("Malformed JSON at character " + at);
    }
    void space() {
      while (at < text.length()) {
        char c = text.charAt(at);
        if (c != ' ' && c != '\t' && c != '\r' && c != '\n')
          break;
        at++;
      }
    }
    boolean eat(char c) {
      space();
      if (at < text.length() && text.charAt(at) == c) {
        at++;
        return true;
      }
      return false;
    }
    Object value(int depth) throws IOException {
      if (depth > 64)
        throw error();
      space();
      if (at >= text.length())
        throw error();
      char c = text.charAt(at);
      if (c == '"')
        return string();
      if (c == '{') {
        at++;
        Map<String, Object> map = new LinkedHashMap<>();
        if (eat('}'))
          return map;
        do {
          space();
          String key = string();
          if (!eat(':') || map.containsKey(key))
            throw error();
          map.put(key, value(depth + 1));
          if (eat('}'))
            return map;
        } while (eat(','));
        throw error();
      }
      if (c == '[') {
        at++;
        List<Object> list = new ArrayList<>();
        if (eat(']'))
          return list;
        do {
          list.add(value(depth + 1));
          if (eat(']'))
            return list;
        } while (eat(','));
        throw error();
      }
      for (String literal : new String[] {"true", "false", "null"}) {
        if (text.startsWith(literal, at)) {
          at += literal.length();
          return literal.equals("null") ? null : Boolean.valueOf(literal);
        }
      }
      int start = at;
      if (c == '-')
        at++;
      if (at >= text.length())
        throw error();
      if (text.charAt(at) == '0')
        at++;
      else {
        if (text.charAt(at) < '1' || text.charAt(at) > '9')
          throw error();
        digits();
      }
      boolean decimal = false;
      if (at < text.length() && text.charAt(at) == '.') {
        decimal = true;
        at++;
        int first = at;
        digits();
        if (first == at)
          throw error();
      }
      if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
        decimal = true;
        at++;
        if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-'))
          at++;
        int first = at;
        digits();
        if (first == at)
          throw error();
      }
      String number = text.substring(start, at);
      try {
        if (!decimal)
          return Long.valueOf(number);
        return new BigDecimal(number);
      } catch (NumberFormatException ex) {
        throw error();
      }
    }
    void digits() {
      while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
    }
    String string() throws IOException {
      if (at >= text.length() || text.charAt(at++) != '"')
        throw error();
      StringBuilder b = new StringBuilder();
      while (at < text.length()) {
        char c = text.charAt(at++);
        if (c == '"')
          return b.toString();
        if (c < 32)
          throw error();
        if (c != '\\') {
          b.append(c);
          continue;
        }
        if (at == text.length())
          throw error();
        c = text.charAt(at++);
        switch (c) {
          case '"':
          case '\\':
          case '/':
            b.append(c);
            break;
          case 'b':
            b.append('\b');
            break;
          case 'f':
            b.append('\f');
            break;
          case 'n':
            b.append('\n');
            break;
          case 'r':
            b.append('\r');
            break;
          case 't':
            b.append('\t');
            break;
          case 'u':
            if (at + 4 > text.length())
              throw error();
            int code = 0;
            for (int j = 0; j < 4; j++) {
              int digit = Character.digit(text.charAt(at++), 16);
              if (digit < 0)
                throw error();
              code = (code << 4) | digit;
            }
            b.append((char) code);
            break;
          default:
            throw error();
        }
      }
      throw error();
    }
  }
}
