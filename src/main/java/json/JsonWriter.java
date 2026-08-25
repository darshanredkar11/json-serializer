package json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Appends JSON straight into a growable UTF-8 byte buffer.
 *
 * <p>No reflection, no intermediate {@code StringBuilder}, no boxing, no
 * per-call allocation: numbers are formatted digit-by-digit into the buffer and
 * ASCII strings are copied with a single bounds check. Separating commas are
 * tracked by one boolean, so callers never manage punctuation.
 *
 * <p>Instances are reusable and <b>not</b> thread-safe: keep one per thread and
 * call {@link #reset()} between documents.
 */
public final class JsonWriter {

    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRUE = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE = {'f', 'a', 'l', 's', 'e'};
    private static final byte[] NULL = {'n', 'u', 'l', 'l'};

    /** Two ASCII digits per index, so numbers can be emitted two at a time. */
    private static final byte[] DIGITS = new byte[200];

    /** 0 = copy as-is, >0 = emit {@code \}+value, -1 = emit {@code \}u00XX. */
    private static final byte[] ESCAPE = new byte[128];

    static {
        for (int i = 0; i < 100; i++) {
            DIGITS[i * 2] = (byte) ('0' + i / 10);
            DIGITS[i * 2 + 1] = (byte) ('0' + i % 10);
        }
        for (int i = 0; i < 0x20; i++) ESCAPE[i] = -1;
        ESCAPE['"'] = '"';
        ESCAPE['\\'] = '\\';
        ESCAPE['\b'] = 'b';
        ESCAPE['\f'] = 'f';
        ESCAPE['\n'] = 'n';
        ESCAPE['\r'] = 'r';
        ESCAPE['\t'] = 't';
    }

    private byte[] buf;
    private int pos;
    private boolean comma;
    private final byte[] num = new byte[24];

    public JsonWriter() { this(1024); }

    public JsonWriter(int capacity) { buf = new byte[Math.max(16, capacity)]; }

    // ---------------------------------------------------------------- output

    /** Clears the buffer for reuse, keeping the allocated capacity. */
    public JsonWriter reset() { pos = 0; comma = false; return this; }

    /** Number of bytes written so far. */
    public int size() { return pos; }

    /** Copies the document out. Prefer {@link #writeTo} to avoid the copy. */
    public byte[] toByteArray() { return Arrays.copyOf(buf, pos); }

    /** Writes the document without copying it first. */
    public void writeTo(OutputStream out) throws IOException { out.write(buf, 0, pos); }

    @Override public String toString() { return new String(buf, 0, pos, StandardCharsets.UTF_8); }

    // ---------------------------------------------------------- structure

    public JsonWriter beginObject() { startValue(); buf[pos++] = '{'; comma = false; return this; }

    public JsonWriter endObject() { ensure(1); buf[pos++] = '}'; comma = true; return this; }

    public JsonWriter beginArray() { startValue(); buf[pos++] = '['; comma = false; return this; }

    public JsonWriter endArray() { ensure(1); buf[pos++] = ']'; comma = true; return this; }

    /** Writes a pre-encoded field name; the value must follow. */
    public JsonWriter name(JsonField field) {
        byte[] key = field.encoded;
        int n = key.length;
        ensure(n + 1);
        if (comma) buf[pos++] = ',';
        System.arraycopy(key, 0, buf, pos, n);
        pos += n;
        comma = false;
        return this;
    }

    /**
     * Writes a field name that isn't known ahead of time — e.g. a map's
     * runtime keys — so there's nothing to pre-encode into a {@link JsonField}.
     * Costs a per-call escape and copy instead of {@code JsonField}'s
     * one-time encoding; prefer a {@code JsonField} constant whenever the
     * key is fixed at compile time.
     */
    public JsonWriter name(String key) {
        ensure(2);
        if (comma) buf[pos++] = ',';
        comma = false;
        rawString(key);
        rawByte((byte) ':');
        return this;
    }

    // -------------------------------------------------------------- values

    public JsonWriter value(String v) {
        if (v == null) return nullValue();
        startValue();
        rawString(v);
        comma = true;
        return this;
    }

    public JsonWriter value(boolean v) {
        startValue();
        byte[] lit = v ? TRUE : FALSE;
        ensure(lit.length);
        System.arraycopy(lit, 0, buf, pos, lit.length);
        pos += lit.length;
        comma = true;
        return this;
    }

    public JsonWriter value(int v) {
        startValue();
        ensure(11);
        if (v < 0) {
            if (v == Integer.MIN_VALUE) { rawAscii("-2147483648"); comma = true; return this; }
            buf[pos++] = '-';
            v = -v;
        }
        int i = 24;
        while (v >= 100) {
            int r = (v % 100) * 2;
            v /= 100;
            num[--i] = DIGITS[r + 1];
            num[--i] = DIGITS[r];
        }
        if (v < 10) {
            num[--i] = (byte) ('0' + v);
        } else {
            int r = v * 2;
            num[--i] = DIGITS[r + 1];
            num[--i] = DIGITS[r];
        }
        System.arraycopy(num, i, buf, pos, 24 - i);
        pos += 24 - i;
        comma = true;
        return this;
    }

    public JsonWriter value(long v) {
        if (v == (int) v) return value((int) v);
        startValue();
        ensure(20);
        if (v < 0) {
            if (v == Long.MIN_VALUE) { rawAscii("-9223372036854775808"); comma = true; return this; }
            buf[pos++] = '-';
            v = -v;
        }
        int i = 24;
        while (v >= 100) {
            int r = (int) (v % 100) * 2;
            v /= 100;
            num[--i] = DIGITS[r + 1];
            num[--i] = DIGITS[r];
        }
        int t = (int) v;
        if (t < 10) {
            num[--i] = (byte) ('0' + t);
        } else {
            int r = t * 2;
            num[--i] = DIGITS[r + 1];
            num[--i] = DIGITS[r];
        }
        System.arraycopy(num, i, buf, pos, 24 - i);
        pos += 24 - i;
        comma = true;
        return this;
    }

    public JsonWriter value(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new JsonException("JSON cannot represent " + v);
        }
        if (v == (long) v && Math.abs(v) < 1e15) return value((long) v);
        startValue();
        rawAscii(Double.toString(v));
        comma = true;
        return this;
    }

    public JsonWriter nullValue() {
        startValue();
        ensure(4);
        System.arraycopy(NULL, 0, buf, pos, 4);
        pos += 4;
        comma = true;
        return this;
    }

    /** Splices in an already-encoded JSON fragment (e.g. a cached sub-document). */
    public JsonWriter raw(byte[] json) {
        startValue();
        ensure(json.length);
        System.arraycopy(json, 0, buf, pos, json.length);
        pos += json.length;
        comma = true;
        return this;
    }

    // -------------------------------------------------------------- internals

    private void startValue() {
        ensure(2);
        if (comma) buf[pos++] = ',';
    }

    void rawByte(byte b) { ensure(1); buf[pos++] = b; }

    private void rawAscii(String s) {
        int len = s.length();
        ensure(len);
        for (int i = 0; i < len; i++) buf[pos++] = (byte) s.charAt(i);
    }

    /** Writes a quoted, escaped string without touching comma state. */
    void rawString(String s) {
        int len = s.length();
        ensure(len + 2);
        byte[] b = buf;
        int p = pos;
        b[p++] = '"';
        int i = 0;
        for (; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x80 || ESCAPE[c] != 0) break;
            b[p++] = (byte) c;
        }
        if (i == len) {
            b[p++] = '"';
            pos = p;
            return;
        }
        pos = p;
        slowString(s, i, len);
    }

    /** Handles escapes and non-ASCII from index {@code i} onwards. */
    private void slowString(String s, int i, int len) {
        for (; i < len; i++) {
            ensure(6);
            byte[] b = buf;
            int p = pos;
            char c = s.charAt(i);
            if (c < 0x80) {
                byte esc = ESCAPE[c];
                if (esc == 0) {
                    b[p++] = (byte) c;
                } else if (esc > 0) {
                    b[p++] = '\\';
                    b[p++] = esc;
                } else {
                    b[p++] = '\\';
                    b[p++] = 'u';
                    b[p++] = '0';
                    b[p++] = '0';
                    b[p++] = HEX[(c >> 4) & 0xF];
                    b[p++] = HEX[c & 0xF];
                }
            } else if (c < 0x800) {
                b[p++] = (byte) (0xC0 | (c >> 6));
                b[p++] = (byte) (0x80 | (c & 0x3F));
            } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len
                    && s.charAt(i + 1) >= 0xDC00 && s.charAt(i + 1) <= 0xDFFF) {
                int cp = 0x10000 + ((c - 0xD800) << 10) + (s.charAt(++i) - 0xDC00);
                b[p++] = (byte) (0xF0 | (cp >> 18));
                b[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                b[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                b[p++] = (byte) (0x80 | (cp & 0x3F));
            } else {
                b[p++] = (byte) (0xE0 | (c >> 12));
                b[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                b[p++] = (byte) (0x80 | (c & 0x3F));
            }
            pos = p;
        }
        ensure(1);
        buf[pos++] = '"';
    }

    private void ensure(int n) {
        if (pos + n > buf.length) grow(n);
    }

    private void grow(int n) {
        buf = Arrays.copyOf(buf, Math.max(buf.length << 1, pos + n));
    }
}
