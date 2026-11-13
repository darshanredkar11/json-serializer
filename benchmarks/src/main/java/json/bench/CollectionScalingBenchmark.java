package json.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import json.Json;
import json.example.User;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Isolates payload-size scaling without changing production serializer code. */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(3)
public class CollectionScalingBenchmark {
    @Param({"0", "3", "16", "64", "256", "1024"})
    public int tagCount;

    private User user;
    private byte[] bytes;
    private ObjectMapper jackson;
    private Gson gson;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        user = sample(tagCount);
        jackson = new ObjectMapper();
        gson = new Gson();
        bytes = Json.toBytes(User.CODEC, user);
        String text = new String(bytes, StandardCharsets.UTF_8);
        verify(user, Json.parse(User.CODEC, bytes));
        verify(user, jackson.readValue(bytes, User.class));
        verify(user, gson.fromJson(text, User.class));
        verify(user, Json.parse(User.CODEC, jackson.writeValueAsBytes(user)));
        verify(user, Json.parse(User.CODEC, gson.toJson(user).getBytes(StandardCharsets.UTF_8)));
    }

    @Benchmark public byte[] ownEncodeBytes() { return Json.toBytes(User.CODEC, user); }
    @Benchmark public byte[] jacksonEncodeBytes() throws Exception { return jackson.writeValueAsBytes(user); }
    @Benchmark public byte[] gsonEncodeBytes() {
        return gson.toJson(user).getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark public User ownDecodeBytes() { return Json.parse(User.CODEC, bytes); }
    @Benchmark public User jacksonDecodeBytes() throws Exception {
        return jackson.readValue(bytes, User.class);
    }
    @Benchmark public User gsonDecodeBytes() {
        return gson.fromJson(new String(bytes, StandardCharsets.UTF_8), User.class);
    }

    private static User sample(int count) {
        User u = new User();
        u.id = 1234567890123L;
        u.name = "Ada Lovelace";
        u.email = "ada@example.com";
        u.active = true;
        u.score = 99.5;
        for (int i = 0; i < count; i++) {
            u.tags.add("engineering-domain-tag-" + i);
        }
        u.address = new User.Address();
        u.address.street = "12 Analytical Engine Way";
        u.address.city = "London";
        u.address.zip = 90210;
        return u;
    }

    private static void verify(User expected, User actual) {
        if (actual == null || expected.id != actual.id || expected.active != actual.active
                || Double.compare(expected.score, actual.score) != 0
                || !java.util.Objects.equals(expected.name, actual.name)
                || !java.util.Objects.equals(expected.email, actual.email)
                || !java.util.Objects.equals(expected.tags, actual.tags)
                || actual.address == null
                || !java.util.Objects.equals(expected.address.street, actual.address.street)
                || !java.util.Objects.equals(expected.address.city, actual.address.city)
                || expected.address.zip != actual.address.zip) {
            throw new IllegalStateException("Serializer mismatch at tagCount=" + expected.tags.size());
        }
    }
}
