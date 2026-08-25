package json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free test runner for {@link Codecs}: {@code java json.CodecsTest} exits non-zero on failure. */
public final class CodecsTest {

    private static int failures;
    private static int checks;

    enum Status { ACTIVE, SUSPENDED, DELETED }

    public static void main(String[] args) {
        primitives();
        nullableRoundTrips();
        listOfRoundTrips();
        mapOfRoundTrips();
        enumOfRoundTrips();
        nestedCombinators();

        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    private static void primitives() {
        eq("\"hi\"", Json.toString(Codecs.STRING, "hi"), "string encode");
        eq("hi", Json.parse(Codecs.STRING, "\"hi\""), "string decode");
        eq("true", Json.toString(Codecs.BOOLEAN, true), "boolean encode");
        eq(true, Json.parse(Codecs.BOOLEAN, "true"), "boolean decode");
        eq("42", Json.toString(Codecs.INT, 42), "int encode");
        eq(42, Json.parse(Codecs.INT, "42"), "int decode");
        eq("9999999999", Json.toString(Codecs.LONG, 9_999_999_999L), "long encode");
        eq(9_999_999_999L, Json.parse(Codecs.LONG, "9999999999"), "long decode");
        eq("1.5", Json.toString(Codecs.DOUBLE, 1.5), "double encode");
        eq(1.5, Json.parse(Codecs.DOUBLE, "1.5"), "double decode");
    }

    private static void nullableRoundTrips() {
        JsonCodec<String> reason = Codecs.nullable(Codecs.STRING);
        eq("\"nope\"", Json.toString(reason, "nope"), "nullable present encode");
        eq("nope", Json.parse(reason, "\"nope\""), "nullable present decode");
        eq("null", Json.toString(reason, null), "nullable null encode");
        eq(null, Json.parse(reason, "null"), "nullable null decode");
    }

    private static void listOfRoundTrips() {
        JsonCodec<List<String>> tags = Codecs.listOf(Codecs.STRING);
        List<String> in = new ArrayList<>(List.of("a", "b", "c"));
        String json = Json.toString(tags, in);
        eq("[\"a\",\"b\",\"c\"]", json, "listOf encode");
        eq(in, Json.parse(tags, json), "listOf decode");

        JsonCodec<List<Integer>> nums = Codecs.listOf(Codecs.INT);
        eq("[]", Json.toString(nums, new ArrayList<>()), "listOf empty encode");
        eq(new ArrayList<Integer>(), Json.parse(nums, "[]"), "listOf empty decode");

        JsonCodec<List<List<Integer>>> nested = Codecs.listOf(Codecs.listOf(Codecs.INT));
        List<List<Integer>> nestedIn = List.of(List.of(1, 2), List.of(3));
        eq(nestedIn, Json.parse(nested, Json.toString(nested, nestedIn)), "listOf of listOf round trip");
    }

    private static void mapOfRoundTrips() {
        JsonCodec<Map<String, Integer>> scores = Codecs.mapOf(Codecs.INT);
        Map<String, Integer> in = new LinkedHashMap<>();
        in.put("alice", 10);
        in.put("bob", 20);
        String json = Json.toString(scores, in);
        eq("{\"alice\":10,\"bob\":20}", json, "mapOf encode preserves insertion order");
        eq(in, Json.parse(scores, json), "mapOf decode");

        eq("{}", Json.toString(scores, new LinkedHashMap<>()), "mapOf empty encode");
        eq(new LinkedHashMap<String, Integer>(), Json.parse(scores, "{}"), "mapOf empty decode");

        // A key containing characters that need escaping exercises the
        // per-call String path (name(String)) rather than a pre-encoded JsonField.
        Map<String, String> weirdKey = new LinkedHashMap<>();
        weirdKey.put("a\"b", "value");
        JsonCodec<Map<String, String>> strMap = Codecs.mapOf(Codecs.STRING);
        Map<String, String> back = Json.parse(strMap, Json.toBytes(strMap, weirdKey));
        eq(weirdKey, back, "mapOf escapes dynamic keys correctly");
    }

    private static void enumOfRoundTrips() {
        JsonCodec<Status> status = Codecs.enumOf(Status.class);
        eq("\"SUSPENDED\"", Json.toString(status, Status.SUSPENDED), "enumOf encode");
        eq(Status.SUSPENDED, Json.parse(status, "\"SUSPENDED\""), "enumOf decode");

        try {
            Json.parse(status, "\"NOT_A_STATUS\"");
            fail("enumOf decode of an unknown constant should throw");
        } catch (IllegalArgumentException expected) {
            pass();
        }
    }

    private static void nestedCombinators() {
        // A nullable list of enums — proves combinators actually compose.
        JsonCodec<List<Status>> statuses = Codecs.listOf(Codecs.enumOf(Status.class));
        JsonCodec<List<Status>> nullableStatuses = Codecs.nullable(statuses);

        List<Status> in = List.of(Status.ACTIVE, Status.DELETED);
        eq(in, Json.parse(nullableStatuses, Json.toString(nullableStatuses, in)), "nullable(listOf(enumOf)) present");
        eq(null, Json.parse(nullableStatuses, "null"), "nullable(listOf(enumOf)) null");
    }

    // ------------------------------------------------------------------ harness

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual == null : expected.equals(actual)) return;
        fail(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void pass() { checks++; }

    private static void fail(String message) {
        failures++;
        checks++;
        System.out.println("FAIL " + message);
    }
}
