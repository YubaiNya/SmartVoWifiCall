import com.newvowifi.ims.sms.SmsPduCodec;
import com.newvowifi.ims.sms.SmsPduCodec.Concat;
import com.newvowifi.ims.sms.SmsPduCodec.Submit;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/** Host tests: fixed AOSP vectors plus independently bit-tabulated fixtures. */
public final class SmsPduCodecTest {
    private static int assertions;
    private static final Instant WHEN = Instant.parse("2026-01-02T03:04:05Z");
    private interface Checked { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        fixedSubmit();
        fixedDeliver();
        multipart();
        malformedAndUnsupported();
        System.out.println("PASS SmsPduCodecTest: " + assertions + " assertions");
    }

    private static void fixedSubmit() throws Exception {
        // AOSP GsmSmsTest.testRecipientAddress. SMSC bytes removed from the fixture.
        Submit aosp = SmsPduCodec.parseSubmit(hex("11000D91683196032930F000000006C8329BFD0E01"));
        equal("+8613693092030", aosp.recipient);
        equal("Hello!", aosp.text);
        equal(0, aosp.messageRef);
        equal(2, aosp.validityPeriodFormat);
        bytes("00", aosp.getValidityPeriod());
        check(!aosp.statusReportRequested && aosp.concat == null);

        Submit unicode = SmsPduCodec.parseSubmit(hex("017F038121F30008044F60597D"));
        equal("123", unicode.recipient);
        equal("你好", unicode.text);
        equal(127, unicode.messageRef);
        equal(SmsPduCodec.Alphabet.UCS2, unicode.alphabet);

        // 20 extension septets, packed independently as an LSB-first bit string.
        Submit ext = SmsPduCodec.parseSubmit(hex("2101049121430000141BCA06B5496D5E1BDEA6B7F16D809BF24601"));
        equal("^{}\\[~]|€\f", ext.text);
        check(ext.statusReportRequested);

        // 6-octet UDH consumes 7 septets: the text starts after one padding bit.
        Submit concat8 = SmsPduCodec.parseSubmit(hex("412A0491214300000C0500037A0201906536FB0D"));
        equal("Hello", concat8.text);
        concat(concat8.concat, 0x7a, 8, 2, 1);

        Submit concat16 = SmsPduCodec.parseSubmit(hex("417F038121F300080B060804123402014F60597D"));
        equal("你好", concat16.text);
        concat(concat16.concat, 0x1234, 16, 2, 1);
        Submit gsm16 = SmsPduCodec.parseSubmit(hex("4100049121430000090608041234020141"));
        equal("A", gsm16.text);
        concat(gsm16.concat, 0x1234, 16, 2, 1);

        // VP formats do not move the TP-UD cursor incorrectly.
        Submit absolute = SmsPduCodec.parseSubmit(hex("1900049121430000621020304050000141"));
        equal("A", absolute.text);
        equal(3, absolute.validityPeriodFormat);
        bytes("62102030405000", absolute.getValidityPeriod());
        byte[] copy = absolute.getValidityPeriod();
        copy[0] = 0;
        equal(0x62, absolute.getValidityPeriod()[0] & 255);
        Submit enhanced = SmsPduCodec.parseSubmit(hex("0900049121430000000000000000000141"));
        equal("A", enhanced.text);
        equal(1, enhanced.validityPeriodFormat);

        Submit empty = SmsPduCodec.parseSubmit(hex("010004912143000000"));
        equal("", empty.text);
    }

    private static void fixedDeliver() throws Exception {
        // AOSP GsmSmsTest.testAddressing, with the SMSC replaced by the requested
        // empty SMSC. The rest of this expected TPDU is byte-for-byte the fixture.
        byte[] pdu = SmsPduCodec.encodeDeliver("+16505551111", "Test",
                Instant.parse("2006-06-06T22:03:03Z"), ZoneOffset.ofHours(-7), 1).get(0);
        bytes("00040B916105551511F100006060605130308A04D4F29C0E", pdu);

        // Numeric originator, exact SCTS and UTF-16BE body; manually specified.
        bytes("0004038121F3000862102030405000044F60597D",
                deliver("123", "你好", 1).get(0));
        // Alpha OA length is 7 useful semi-octets, NOT 4 text characters.
        bytes("000407D0C1E7140A0000621020304050000141",
                deliver("AOSP", "A", 1).get(0));
        // +05:45 is 23 quarter-hours, semi-octet representation 0x32.
        byte[] nepal = SmsPduCodec.encodeDeliver("123", "A", WHEN,
                ZoneOffset.ofHoursMinutes(5, 45), 1).get(0);
        bytes("0004038121F30000621020809450320141", nepal);

        List<byte[]> empty = deliver("123", "", 0);
        equal(1, empty.size());
        equal(0, userDataLength(empty.get(0)));
        equal(0, userData(empty.get(0)).length);
        // Empty text and a short sender still produce SMS-DELIVER, never a receipt.
        equal(0, empty.get(0)[1] & 3);
    }

    private static void multipart() throws Exception {
        equal(1, deliver("123", repeat("A", 160), 9).size());
        List<byte[]> gsm = deliver("123", repeat("A", 161), 0xabcd);
        equal(2, gsm.size());
        equal(160, userDataLength(gsm.get(0)));
        equal(17, userDataLength(gsm.get(1)));
        bytes("060804ABCD0201", Arrays.copyOf(userData(gsm.get(0)), 7));
        bytes("060804ABCD0202", Arrays.copyOf(userData(gsm.get(1)), 7));
        equal(140, userData(gsm.get(0)).length);
        equal(15, userData(gsm.get(1)).length);
        equal(repeat("A", 152), asciiSeptets(gsm.get(0)));
        equal(repeat("A", 9), asciiSeptets(gsm.get(1)));

        equal(1, deliver("123", repeat("^", 80), 10).size());
        List<byte[]> ext = deliver("123", repeat("A", 151) + "^" + repeat("B", 8), 10);
        equal(2, ext.size());
        // ESC + extension stay together; the first part leaves one septet unused.
        equal(159, userDataLength(ext.get(0)));
        equal(18, userDataLength(ext.get(1)));
        equal(repeat("A", 151), asciiSeptets(ext.get(0)));
        int[] second = septets(ext.get(1));
        equal(27, second[0]); equal(20, second[1]);
        for (int i = 2; i < second.length; i++) equal(66, second[i]);

        equal(1, deliver("123", repeat("中", 70), 11).size());
        List<byte[]> unicode = deliver("123", repeat("中", 71), 0xffff);
        equal(2, unicode.size());
        equal(139, userDataLength(unicode.get(0))); // 7 + 66 * 2, not 140.
        equal(17, userDataLength(unicode.get(1)));
        equal(repeat("中", 66), unicodeBody(unicode.get(0)));
        equal(repeat("中", 5), unicodeBody(unicode.get(1)));

        List<byte[]> emoji = deliver("123", repeat("中", 65) + "😀" + repeat("中", 4), 12);
        equal(2, emoji.size());
        equal(repeat("中", 65), unicodeBody(emoji.get(0)));
        equal("😀" + repeat("中", 4), unicodeBody(emoji.get(1)));
        equal(8, dataCodingScheme(emoji.get(0)));
        equal(255, deliver("123", repeat("A", 152 * 255), 13).size());
        fails(SmsPduCodec.UnsupportedPduException.class,
                () -> deliver("123", repeat("A", 152 * 255 + 1), 13));
        for (byte[] part : gsm) {
            equal(0, part[1] & 3);
            check((part[1] & 0x40) != 0);
        }
    }

    private static void malformedAndUnsupported() throws Exception {
        byte[] valid = hex("412A0491214300000C0500037A0201906536FB0D");
        for (int n = 0; n < valid.length; n++) {
            final byte[] truncated = Arrays.copyOf(valid, n);
            fails(SmsPduCodec.MalformedPduException.class, () -> SmsPduCodec.parseSubmit(truncated));
        }
        fails(SmsPduCodec.MalformedPduException.class, () -> SmsPduCodec.parseSubmit(null));
        bad("01000381211300000141"); // Missing odd-address F filler.
        bad("0100049121430000014100"); // Extra data must not be silently ignored.
        bad("4100049121430000090500037A00014100"); // total zero.
        bad("4100049121430000090500037A02034100"); // sequence > total.
        bad("4100049121430000080600037A020141"); // UDH extends beyond actual UD.
        bad("01000491214300080141"); // Odd UTF-16BE.
        bad("010004912143000802D800"); // Unpaired UTF-16 surrogate.
        bad("0100049121430000011B"); // Dangling GSM escape.
        bad("4100049121430008110B000301020100030202014F60597D0000"); // duplicate concat.
        unsupported("01000491214300040141"); // Binary DCS.
        unsupported("01000491214300100141"); // Flash/class semantics.
        unsupported("01000491214300200141"); // Compressed GSM7.
        unsupported("01000491214300C00141"); // MWI indication.
        unsupported("01000491214340000141"); // Type-zero PID.
        unsupported("81000491214300000141"); // Reply path.
        unsupported("010004D0214300000141"); // Alpha destination, not an engine number.
        unsupported("41000491214300000703240101080201"); // National language UDH.
        unsupported("4100049121430000080605040B8423F0"); // Port-addressed UDH.
        unsupported("0100049121430000021B00"); // Undefined extension.
        unsupported("0001000491214300000141"); // SMSC-prefixed input is not a TPDU.
        fails(SmsPduCodec.MalformedPduException.class, () -> deliver("123", "\uD800", 1));
        fails(SmsPduCodec.UnsupportedPduException.class, () -> deliver("发送方", "A", 1));
        fails(SmsPduCodec.UnsupportedPduException.class, () -> deliver("ABCDEFGHIJKL", "A", 1));
        fails(SmsPduCodec.UnsupportedPduException.class, () -> deliver("+12X", "A", 1));
        fails(SmsPduCodec.MalformedPduException.class, () -> deliver("", "A", 1));
        fails(SmsPduCodec.MalformedPduException.class, () -> deliver("123", "A", 65536));
        fails(SmsPduCodec.UnsupportedPduException.class, () -> SmsPduCodec.encodeDeliver("123", "A",
                WHEN, ZoneOffset.ofTotalSeconds(30), 1));
        fails(SmsPduCodec.UnsupportedPduException.class, () -> SmsPduCodec.encodeDeliver("123", "A",
                Instant.parse("2090-01-01T00:00:00Z"), ZoneOffset.UTC, 1));
    }

    // Test-only wire inspection; does not call the codec's SUBMIT parser.
    private static int udlOffset(byte[] pdu) { return 1 + 1 + 2 + ((pdu[2] & 255) + 1) / 2 + 2 + 7; }
    private static int dataCodingScheme(byte[] pdu) { return pdu[udlOffset(pdu) - 8] & 255; }
    private static int userDataLength(byte[] pdu) { return pdu[udlOffset(pdu)] & 255; }
    private static byte[] userData(byte[] pdu) { return Arrays.copyOfRange(pdu, udlOffset(pdu) + 1, pdu.length); }
    private static String unicodeBody(byte[] pdu) {
        byte[] ud = userData(pdu);
        return new String(ud, 7, ud.length - 7, StandardCharsets.UTF_16BE);
    }
    private static int[] septets(byte[] pdu) {
        byte[] ud = userData(pdu);
        int[] result = new int[userDataLength(pdu) - 8];
        // Independent bit-by-bit oracle (rather than the production shifting algorithm).
        for (int i = 0; i < result.length; i++) for (int b = 0; b < 7; b++) {
            int bit = 56 + 7 * i + b;
            result[i] |= ((ud[bit / 8] >>> (bit % 8)) & 1) << b;
        }
        return result;
    }
    private static String asciiSeptets(byte[] pdu) {
        StringBuilder s = new StringBuilder();
        for (int n : septets(pdu)) s.append((char) n);
        return s.toString();
    }
    private static List<byte[]> deliver(String sender, String text, int ref) throws Exception {
        return SmsPduCodec.encodeDeliver(sender, text, WHEN, ZoneOffset.UTC, ref);
    }
    private static void concat(Concat c, int ref, int bits, int count, int sequence) {
        check(c != null); equal(ref, c.reference); equal(bits, c.referenceBits);
        equal(count, c.totalParts); equal(sequence, c.sequenceNumber);
    }
    private static void bad(String hex) throws Exception {
        fails(SmsPduCodec.MalformedPduException.class, () -> SmsPduCodec.parseSubmit(hex(hex)));
    }
    private static void unsupported(String hex) throws Exception {
        fails(SmsPduCodec.UnsupportedPduException.class, () -> SmsPduCodec.parseSubmit(hex(hex)));
    }
    private static void fails(Class<? extends Throwable> type, Checked action) throws Exception {
        try { action.run(); } catch (Throwable t) {
            assertions++;
            if (type.isInstance(t)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + t, t);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static String repeat(String s, int n) { StringBuilder b = new StringBuilder(); for(int i=0;i<n;i++) b.append(s); return b.toString(); }
    private static void check(boolean condition) { assertions++; if(!condition) throw new AssertionError("condition failed"); }
    private static void equal(Object expected, Object actual) { assertions++; if(!expected.equals(actual)) throw new AssertionError("Expected <"+expected+">, got <"+actual+">"); }
    private static void bytes(String expected, byte[] actual) { check(Arrays.equals(hex(expected), actual)); }
    private static byte[] hex(String value) { byte[] b = new byte[value.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16); return b; }
}
