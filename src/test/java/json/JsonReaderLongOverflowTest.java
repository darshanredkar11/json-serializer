package json;

import java.nio.charset.StandardCharsets;

/** Regression coverage for long parsing overflow. */
public final class JsonReaderLongOverflowTest {
    public static void main(String[] args) {
        check(Long.MAX_VALUE, "9223372036854775807");
        check(Long.MIN_VALUE, "-9223372036854775808");
        rejects("9223372036854775808");
        rejects("-9223372036854775809");
        rejects("18446744073709551616");
        rejects("-18446744073709551616");
        System.out.println("long overflow regression: OK");
    }

    private static void check(long expected, String json) {
        long actual = new JsonReader(json.getBytes(StandardCharsets.UTF_8)).readLong();
        if (actual != expected) throw new AssertionError(json + " -> " + actual);
    }

    private static void rejects(String json) {
        try {
            new JsonReader(json.getBytes(StandardCharsets.UTF_8)).readLong();
            throw new AssertionError("accepted out-of-range long: " + json);
        } catch (JsonException expected) {
            // expected
        }
    }
}
