package json;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A pull parser over a UTF-8 byte array.
 *
 * <p>Values are decoded straight from the input bytes: numbers never become
 * strings, field names never become {@link String}s (they are matched against
 * pre-encoded {@link JsonField}s), and pure-ASCII string values are turned into
 * compact Latin-1 {@code String}s with a single array copy.
 *
 * <p>Instances are reusable and <b>not</b> thread-safe: keep one per thread and
 * call {@link #reset} per document.
 */
public final class JsonReader {

    private static final double[] POW10 = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
        1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22
    };

    private byte[] buf;
    private int pos;
    private int end;
    private int keyStart;
    private int keyEnd;
    private char[] chars = new char[64];

    public JsonReader() { this(new byte[0], 0, 0); }

    public JsonReader(byte[] data) { this(data, 0, data.length); }

    public JsonReader(byte[] data, int offset, int length) { reset(data, offset, length); }

    /** Points the reader at a new document without allocating. */
    public JsonReader reset(byte[] data, int offset, int length) {
        this.buf = data;
        this.pos = offset;
        this.end = offset + length;
        return this;
    }

    public JsonReader reset(byte[] data) { return reset(data, 0, data.length); }

    // ------------------------------------------------------------ structure

    public void beginObject() { expect('{'); }

    public void beginArray() { expect('['); }

    /**
     * Advances to the next field of the current object, consuming the key and
     * its colon, and returns {@code false} at the closing brace (which it also
     * consumes). The value is then read with one of the {@code read*} methods,
     * or discarded with {@link #skipValue()}.
     */
    public boolean nextKey() {
        byte c = peek();
        if (c == '}') { pos++; return false; }
        if (c == ',') { pos++; c = peek(); }
        if (c != '"') throw error("expected a field name");
        pos++;
        keyStart = pos;
        byte[] b = buf;
        int p = pos;
        while (p < end) {
            byte x = b[p];
            if (x == '"') break;
            if (x == '\\') p++;
            p++;
        }
        if (p >= end) throw error("unterminated field name");
        keyEnd = p;
        pos = p + 1;
        if (peek() != ':') throw error("expected ':'");
        pos++;
        return true;
    }

    /** True when the current key equals {@code field}, compared as raw bytes. */
    public boolean keyIs(JsonField field) {
        byte[] name = field.name;
        return keyEnd - keyStart == name.length
            && Arrays.equals(buf, keyStart, keyEnd, name, 0, name.length);
    }

    /** The current key as a {@code String}. Allocates; for errors and dynamic use. */
    public String key() { return new String(buf, keyStart, keyEnd - keyStart, StandardCharsets.UTF_8); }

    /**
     * Advances to the next array element and returns {@code false} at the
     * closing bracket (which it also consumes).
     */
    public boolean hasNextElement() {
        byte c = peek();
        if (c == ']') { pos++; return false; }
        if (c == ',') pos++;
        return true;
    }

    /** True (consuming the literal) when the next value is {@code null}. */
    public boolean isNull() {
        if (peek() != 'n') return false;
        expectLiteral("null");
        return true;
    }

    // --------------------------------------------------------------- values

    public boolean readBoolean() {
        byte c = peek();
        if (c == 't') { expectLiteral("true"); return true; }
        if (c == 'f') { expectLiteral("false"); return false; }
        throw error("expected a boolean");
    }

    public int readInt() {
        long v = readLong();
        if (v != (int) v) throw error("integer out of range: " + v);
        return (int) v;
    }

    public long readLong() {
        byte c = peek();
        boolean neg = c == '-';
        if (neg || c == '+') { pos++; c = current(); }
        if (c < '0' || c > '9') throw error("expected a number");
        long v = 0;
        byte[] b = buf;
        int p = pos;
        while (p < end) {
            int d = b[p] - '0';
            if (d < 0 || d > 9) break;
            v = v * 10 + d;
            p++;
        }
        pos = p;
        return neg ? -v : v;
    }

    public double readDouble() {
        byte c = peek();
        int start = pos;
        boolean neg = c == '-';
        if (neg || c == '+') pos++;
        byte[] b = buf;
        int p = pos;
        long mantissa = 0;
        int digits = 0;
        int exp = 0;
        while (p < end) {
            int d = b[p] - '0';
            if (d < 0 || d > 9) break;
            if (digits < 19) { mantissa = mantissa * 10 + d; digits++; } else { exp++; }
            p++;
        }
        boolean any = p > pos;
        if (p < end && b[p] == '.') {
            p++;
            while (p < end) {
                int d = b[p] - '0';
                if (d < 0 || d > 9) break;
                if (digits < 19) { mantissa = mantissa * 10 + d; digits++; exp--; }
                p++;
                any = true;
            }
        }
        if (!any) throw error("expected a number");
        if (p < end && (b[p] == 'e' || b[p] == 'E')) {
            p++;
            boolean eneg = false;
            if (p < end && (b[p] == '-' || b[p] == '+')) { eneg = b[p] == '-'; p++; }
            int e = 0;
            while (p < end) {
                int d = b[p] - '0';
                if (d < 0 || d > 9) break;
                e = e * 10 + d;
                p++;
            }
            exp += eneg ? -e : e;
        }
        pos = p;
        // Exact fast path: the mantissa and the power of ten are both exactly
        // representable, so one multiply or divide is correctly rounded.
        if (digits <= 15 && exp >= -22 && exp <= 22) {
            double d = (double) mantissa;
            d = exp >= 0 ? d * POW10[exp] : d / POW10[-exp];
            return neg ? -d : d;
        }
        return Double.parseDouble(new String(b, start, p - start, StandardCharsets.ISO_8859_1));
    }

    /** Reads a string, or {@code null} for a JSON null. */
    public String readString() {
        byte c = peek();
        if (c == 'n') { expectLiteral("null"); return null; }
        if (c != '"') throw error("expected a string");
        pos++;
        int start = pos;
        byte[] b = buf;
        int p = start;
        while (p < end) {
            byte x = b[p];
            if (x == '"') {
                pos = p + 1;
                // Pure ASCII: Latin-1 decoding is byte-identical to UTF-8 here
                // and lands in a compact String with one arraycopy.
                return new String(b, start, p - start, StandardCharsets.ISO_8859_1);
            }
            if (x == '\\' || x < 0) break;
            p++;
        }
        if (p >= end) throw error("unterminated string");
        return slowString(start, p);
    }

    /** Discards the next value, however deeply nested. */
    public void skipValue() {
        byte c = peek();
        switch (c) {
            case '{':
            case '[': {
                int depth = 0;
                do {
                    if (pos >= end) throw error("unterminated " + (c == '{' ? "object" : "array"));
                    byte x = buf[pos++];
                    if (x == '"') skipStringBody();
                    else if (x == '{' || x == '[') depth++;
                    else if (x == '}' || x == ']') depth--;
                } while (depth > 0);
                break;
            }
            case '"':
                pos++;
                skipStringBody();
                break;
            default:
                while (pos < end) {
                    byte x = buf[pos];
                    if (x == ',' || x == '}' || x == ']' || x <= ' ') break;
                    pos++;
                }
        }
    }

    // ------------------------------------------------------------ internals

    /** Skips whitespace and returns the next byte without consuming it. */
    private byte peek() {
        byte[] b = buf;
        int p = pos;
        while (p < end) {
            byte c = b[p];
            if (c > ' ') { pos = p; return c; }
            p++;
        }
        pos = p;
        throw error("unexpected end of input");
    }

    private byte current() {
        if (pos >= end) throw error("unexpected end of input");
        return buf[pos];
    }

    private void expect(char c) {
        if (peek() != c) throw error("expected '" + c + "'");
        pos++;
    }

    private void expectLiteral(String lit) {
        int n = lit.length();
        if (pos + n > end) throw error("expected '" + lit + "'");
        for (int i = 0; i < n; i++) {
            if (buf[pos + i] != lit.charAt(i)) throw error("expected '" + lit + "'");
        }
        pos += n;
    }

    private void skipStringBody() {
        byte[] b = buf;
        int p = pos;
        while (p < end) {
            byte x = b[p];
            if (x == '"') { pos = p + 1; return; }
            if (x == '\\') p++;
            p++;
        }
        throw error("unterminated string");
    }

    /** Decodes escapes and multi-byte UTF-8 from {@code p} onwards. */
    private String slowString(int start, int p) {
        int n = p - start;
        char[] out = chars;
        if (out.length < n + 16) out = chars = new char[Math.max(n * 2 + 16, 64)];
        for (int i = 0; i < n; i++) out[i] = (char) buf[start + i];
        byte[] b = buf;
        while (p < end) {
            if (n + 2 > out.length) out = chars = Arrays.copyOf(out, out.length << 1);
            int x = b[p++] & 0xFF;
            if (x == '"') {
                pos = p;
                return new String(out, 0, n);
            }
            if (x == '\\') {
                if (p >= end) break;
                int esc = b[p++];
                switch (esc) {
                    case '"': out[n++] = '"'; break;
                    case '\\': out[n++] = '\\'; break;
                    case '/': out[n++] = '/'; break;
                    case 'b': out[n++] = '\b'; break;
                    case 'f': out[n++] = '\f'; break;
                    case 'n': out[n++] = '\n'; break;
                    case 'r': out[n++] = '\r'; break;
                    case 't': out[n++] = '\t'; break;
                    case 'u':
                        if (p + 4 > end) throw error("truncated \\u escape");
                        out[n++] = (char) ((hex(b[p]) << 12) | (hex(b[p + 1]) << 8)
                                         | (hex(b[p + 2]) << 4) | hex(b[p + 3]));
                        p += 4;
                        break;
                    default: throw error("invalid escape '\\" + (char) esc + "'");
                }
            } else if (x < 0x80) {
                out[n++] = (char) x;
            } else if (x < 0xE0) {
                out[n++] = (char) (((x & 0x1F) << 6) | (b[p++] & 0x3F));
            } else if (x < 0xF0) {
                out[n++] = (char) (((x & 0x0F) << 12) | ((b[p] & 0x3F) << 6) | (b[p + 1] & 0x3F));
                p += 2;
            } else {
                int cp = ((x & 0x07) << 18) | ((b[p] & 0x3F) << 12)
                       | ((b[p + 1] & 0x3F) << 6) | (b[p + 2] & 0x3F);
                p += 3;
                cp -= 0x10000;
                out[n++] = (char) (0xD800 + (cp >> 10));
                out[n++] = (char) (0xDC00 + (cp & 0x3FF));
            }
        }
        throw error("unterminated string");
    }

    private int hex(byte c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw error("invalid hex digit");
    }

    private JsonException error(String message) {
        return new JsonException(message + " at offset " + pos);
    }
}
