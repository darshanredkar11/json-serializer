package json.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import json.Json;
import json.example.User;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Same User graph and JSON payload for all three implementations.
 * The byte benchmarks include each library's public API conversion costs:
 * Gson's String-to-UTF-8 conversion is deliberately included because Gson
 * does not expose a byte[] serialization API.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class SerializerBenchmark {
    private User user;
    private byte[] jsonBytes;
    private String jsonText;
    private ObjectMapper jackson;
    private Gson gson;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        user = sample();
        jackson = new ObjectMapper();
        gson = new Gson();

        // Use the custom codec as the canonical payload, then validate that
        // all implementations can read the same data before timing anything.
        jsonBytes = Json.toBytes(User.CODEC, user);
        jsonText = new String(jsonBytes, StandardCharsets.UTF_8);

        User own = Json.parse(User.CODEC, jsonBytes);
        User jacksonValue = jackson.readValue(jsonBytes, User.class);
        User gsonValue = gson.fromJson(jsonText, User.class);
        assertEquivalent(user, own);
        assertEquivalent(user, jacksonValue);
        assertEquivalent(user, gsonValue);

        byte[] jacksonBytes = jackson.writeValueAsBytes(user);
        byte[] gsonBytes = gson.toJson(user).getBytes(StandardCharsets.UTF_8);
        assertEquivalent(user, Json.parse(User.CODEC, jacksonBytes));
        assertEquivalent(user, Json.parse(User.CODEC, gsonBytes));
    }

    // ---------- UTF-8 byte[] API (end-to-end, including Gson conversion) ----------

    @Benchmark
    public byte[] ownEncodeBytes() {
        return Json.toBytes(User.CODEC, user);
    }

    @Benchmark
    public byte[] jacksonEncodeBytes() throws Exception {
        return jackson.writeValueAsBytes(user);
    }

    @Benchmark
    public byte[] gsonEncodeBytes() {
        return gson.toJson(user).getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public User ownDecodeBytes() {
        return Json.parse(User.CODEC, jsonBytes);
    }

    @Benchmark
    public User jacksonDecodeBytes() throws Exception {
        return jackson.readValue(jsonBytes, User.class);
    }

    @Benchmark
    public User gsonDecodeBytes() {
        return gson.fromJson(new String(jsonBytes, StandardCharsets.UTF_8), User.class);
    }

    // ---------- String API (native String APIs for all three) ----------

    @Benchmark
    public String ownEncodeString() {
        return Json.toString(User.CODEC, user);
    }

    @Benchmark
    public String jacksonEncodeString() throws Exception {
        return jackson.writeValueAsString(user);
    }

    @Benchmark
    public String gsonEncodeString() {
        return gson.toJson(user);
    }

    @Benchmark
    public User ownDecodeString() {
        return Json.parse(User.CODEC, jsonText);
    }

    @Benchmark
    public User jacksonDecodeString() throws Exception {
        return jackson.readValue(jsonText, User.class);
    }

    @Benchmark
    public User gsonDecodeString() {
        return gson.fromJson(jsonText, User.class);
    }

    private static User sample() {
        User u = new User();
        u.id = 1234567890123L;
        u.name = "Ada Lovelace";
        u.email = "ada@example.com";
        u.active = true;
        u.score = 99.5;
        u.tags.add("engineer");
        u.tags.add("mathematician");
        u.tags.add("pioneer");
        u.address = new User.Address();
        u.address.street = "12 Analytical Engine Way";
        u.address.city = "London";
        u.address.zip = 90210;
        return u;
    }

    private static void assertEquivalent(User expected, User actual) {
        if (actual == null
                || expected.id != actual.id
                || expected.active != actual.active
                || Double.compare(expected.score, actual.score) != 0
                || !java.util.Objects.equals(expected.name, actual.name)
                || !java.util.Objects.equals(expected.email, actual.email)
                || !java.util.Objects.equals(expected.tags, actual.tags)
                || actual.address == null
                || !java.util.Objects.equals(expected.address.street, actual.address.street)
                || !java.util.Objects.equals(expected.address.city, actual.address.city)
                || expected.address.zip != actual.address.zip) {
            throw new IllegalStateException("Serializer round-trip mismatch: " + actual);
        }
    }
}