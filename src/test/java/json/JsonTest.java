package json;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import json.example.User;

/** Dependency-free test runner: {@code java json.JsonTest} exits non-zero on failure. */
public final class JsonTest {

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        roundTrip();
        writerShape();
        strings();
        numbers();
        parsing();
        skipping();
        errors();

        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ tests

    private static void roundTrip() {
        User u = sample();
        String json = Json.toString(User.CODEC, u);
        eq("{\"id\":42,\"name\":\"Ada Lovelace\",\"email\":null,\"active\":true,"
         + "\"score\":99.5,\"tags\":[\"a\",\"b\"],"
         + "\"address\":{\"street\":\"1 Main St\",\"city\":\"London\",\"zip\":90210}}", json, "encode");

        User b = Json.parse(User.CODEC, json);
        eq(u.id, b.id, "id");
        eq(u.name, b.name, "name");
        eq(u.email, b.email, "email");
        eq(u.active, b.active, "active");
        eq(u.score, b.score, "score");
        eq(u.tags, b.tags, "tags");
        eq(u.address.street, b.address.street, "street");
        eq(u.address.zip, b.address.zip, "zip");

        u.address = null;
        u.tags.clear();
        User c = Json.parse(User.CODEC, Json.toBytes(User.CODEC, u));
        eq(null, c.address, "null nested object");
        eq(0, c.tags.size(), "empty array");
    }

    private static void writerShape() {
        JsonWriter w = new JsonWriter();
        JsonField a = JsonField.of("a");
        JsonField b = JsonField.of("b");
        w.beginObject();
        w.name(a).beginArray();
        w.value(1).value(2);
        w.beginObject().name(b).value(true).endObject();
        w.endArray();
        w.endObject();
        eq("{\"a\":[1,2,{\"b\":true}]}", w.toString(), "nested comma placement");

        eq("[]", new JsonWriter().beginArray().endArray().toString(), "empty array");
        eq("{}", new JsonWriter().beginObject().endObject().toString(), "empty object");
        eq("[{\"pre\":1}]", new JsonWriter().beginArray()
                .raw("{\"pre\":1}".getBytes(StandardCharsets.UTF_8)).endArray().toString(), "raw fragment");

        // Reuse must not leak state between documents.
        w.reset();
        eq("{}", w.beginObject().endObject().toString(), "reset");
    }

    private static void strings() {
        rt("plain ascii");
        rt("quote \" backslash \\ slash /");
        rt("control \b\f\n\r\t end");
        rt("accents: café naïve über");
        rt("cjk: 你好世界");
        rt("emoji: 🚀🎉");
        rt("");
        rt("mixed é \" \n 🚀 tail");

        eq("\"\\u0000\\u001f\"", quoted(String.valueOf(new char[]{0, 0x1f})), "control escapes");
        eq("\"café\"", quoted("café"), "utf-8 passthrough");

        // Escapes on input decode correctly.
        eq("a\"b\\c/d\nz", firstString("[\"a\\\"b\\\\c\\/d\\nz\"]"), "input escapes");
        eq("é你🚀", firstString("[\"\\u00e9\\u4f60\\ud83d\\ude80\"]"), "\\u escapes");
        eq("café 你 🚀", firstString("[\"café 你 🚀\"]"), "raw utf-8 input");

        // A long string forces the writer buffer and the reader's char buffer to grow.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("xé🚀\"");
        rt(sb.toString());
    }

    private static void numbers() {
        eq("0", num(0), "zero");
        eq("-1", num(-1), "negative");
        eq("2147483647", num(Integer.MAX_VALUE), "int max");
        eq("-2147483648", num(Integer.MIN_VALUE), "int min");
        eq("9223372036854775807", num(Long.MAX_VALUE), "long max");
        eq("-9223372036854775808", num(Long.MIN_VALUE), "long min");
        eq("99", num(99), "two digits");
        eq("100", num(100), "three digits");

        for (long v : new long[]{0, 1, -1, 9, 10, 99, 100, 12345, -98765, 1000000007L,
                                 Long.MAX_VALUE, Long.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            eq(Long.toString(v), num(v), "long " + v);
            eq(v, parseLong(num(v)), "long round trip " + v);
        }

        eq("1.5", dbl(1.5), "double");
        eq("3", dbl(3.0), "integral double");
        eq("-0.001", dbl(-0.001), "small double");
        for (double d : new double[]{0, 1.5, -1.5, 0.1, 1e-7, 1.7976931348623157e308,
                                     4.9e-324, 123456789.123456}) {
            eq(d, parseDouble(dbl(d)), "double round trip " + d);
        }
        // Values parsed from text the writer would not itself produce.
        eq(1.0, parseDouble("1e0"), "1e0");
        eq(-12.5e3, parseDouble("-12.5e3"), "exponent");
        eq(1e-30, parseDouble("1e-30"), "tiny exponent");
        eq(0.1 + 0.2, parseDouble(Double.toString(0.1 + 0.2)), "long mantissa");
        eq(123456789012345678.0, parseDouble("123456789012345678"), "19 digits");

        try {
            new JsonWriter().value(Double.NaN);
            fail("NaN should throw");
        } catch (JsonException expected) {
            pass();
        }
    }

    private static void parsing() {
        String spaced = "  {  \"id\" : 7 ,  \"tags\" : [ \"x\" , \"y\" ] , \"active\" : false }  ";
        User u = Json.parse(User.CODEC, spaced);
        eq(7L, u.id, "whitespace tolerant id");
        eq(Arrays.asList("x", "y"), u.tags, "whitespace tolerant array");
        eq(false, u.active, "whitespace tolerant boolean");

        User d = Json.parse(User.CODEC, "{\"name\":null,\"address\":null}");
        eq(null, d.name, "null string");
        eq(null, d.address, "explicit null object");

        // Offset/length parsing over a shared buffer.
        byte[] framed = "XXXX{\"id\":5}YYYY".getBytes(StandardCharsets.UTF_8);
        eq(5L, Json.parse(User.CODEC, framed, 4, 8).id, "offset parse");
    }

    private static void skipping() {
        String json = "{\"unknown\":{\"a\":[1,2,{\"b\":\"}\"}],\"c\":\"str with } and ]\"},"
                    + "\"other\":[[[]]],\"junk\":12.5,\"flag\":true,\"id\":99}";
        eq(99L, Json.parse(User.CODEC, json).id, "skip nested unknown fields");
        eq(1L, Json.parse(User.CODEC, "{\"skip\":\"esc \\\" } \",\"id\":1}").id, "skip escaped quote");
    }

    private static void errors() {
        throwsJson("{\"id\":", "truncated");
        throwsJson("{\"id\" 1}", "missing colon");
        throwsJson("{id:1}", "unquoted key");
        throwsJson("{\"id\":tru}", "bad literal");
        throwsJson("{\"name\":\"unterminated", "unterminated string");
        throwsJson("{\"score\":abc}", "not a number");
    }

    // ------------------------------------------------------------------ util

    private static User sample() {
        User u = new User();
        u.id = 42;
        u.name = "Ada Lovelace";
        u.email = null;
        u.active = true;
        u.score = 99.5;
        u.tags.add("a");
        u.tags.add("b");
        u.address = new User.Address();
        u.address.street = "1 Main St";
        u.address.city = "London";
        u.address.zip = 90210;
        return u;
    }

    private static String quoted(String s) { return new JsonWriter().value(s).toString(); }

    private static String num(long v) { return new JsonWriter().value(v).toString(); }

    private static String dbl(double v) { return new JsonWriter().value(v).toString(); }

    private static long parseLong(String s) { return new JsonReader(bytes(s)).readLong(); }

    private static double parseDouble(String s) { return new JsonReader(bytes(s)).readDouble(); }

    private static String firstString(String arrayJson) {
        JsonReader r = new JsonReader(bytes(arrayJson));
        r.beginArray();
        r.hasNextElement();
        return r.readString();
    }

    /** Encodes a string then reads it back, asserting the value survives. */
    private static void rt(String s) {
        String json = quoted(s);
        JsonReader r = new JsonReader(bytes(json));
        eq(s, r.readString(), "string round trip (" + summary(s) + ")");
    }

    private static void throwsJson(String json, String what) {
        try {
            Json.parse(User.CODEC, json);
            fail("expected a JsonException for " + what + ": " + json);
        } catch (JsonException e) {
            pass();
        } catch (RuntimeException e) {
            fail(what + " threw " + e.getClass().getSimpleName() + " instead of JsonException");
        }
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String summary(String s) {
        return s.length() <= 24 ? s : s.substring(0, 24) + "... (" + s.length() + " chars)";
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual == null : expected.equals(actual)) return;
        fail(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void eq(long expected, long actual, String what) { eq((Object) expected, (Object) actual, what); }

    private static void eq(double expected, double actual, String what) {
        checks++;
        if (Double.compare(expected, actual) == 0) return;
        fail(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void eq(boolean expected, boolean actual, String what) { eq((Object) expected, (Object) actual, what); }

    private static void pass() { checks++; }

    private static void fail(String message) {
        failures++;
        checks++;
        System.out.println("FAIL " + message);
    }
}
