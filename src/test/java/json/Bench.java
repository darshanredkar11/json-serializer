package json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import json.example.User;

/**
 * Throughput smoke test: {@code java -cp out json.Bench}. Not JMH, so treat the
 * numbers as an order of magnitude rather than a publication result.
 */
public final class Bench {

    private static final int WARMUP = 200_000;
    private static final int RUNS = 5;
    private static final int ITERS = 1_000_000;

    public static void main(String[] args) {
        User user = sample();
        byte[] encoded = Json.toBytes(User.CODEC, user);
        String json = new String(encoded, StandardCharsets.UTF_8);
        System.out.println("payload: " + encoded.length + " bytes");
        System.out.println(json);
        System.out.println();

        JsonWriter w = new JsonWriter();
        JsonReader r = new JsonReader();

        for (int i = 0; i < WARMUP; i++) {
            w.reset();
            User.CODEC.write(w, user);
            sink += User.CODEC.read(r.reset(encoded)).id;
        }

        bench("encode  ", encoded.length, () -> {
            w.reset();
            User.CODEC.write(w, user);
            return w.size();
        });

        bench("decode  ", encoded.length, () -> (int) User.CODEC.read(r.reset(encoded)).id);

        // Bulk array of 1000 records, the shape of a real API response.
        List<User> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) many.add(sample());
        byte[] bulk = writeAll(new JsonWriter(1 << 16), many);
        System.out.println("bulk payload: " + bulk.length + " bytes (1000 records)");
        JsonWriter bw = new JsonWriter(1 << 16);
        benchN("encode x1000", bulk.length, 2000, 1000, () -> writeAll(bw, many).length);
        benchN("decode x1000", bulk.length, 2000, 1000, () -> readAll(r.reset(bulk)).size());
    }

    private static byte[] writeAll(JsonWriter w, List<User> users) {
        w.reset();
        w.beginArray();
        for (int i = 0, n = users.size(); i < n; i++) User.CODEC.write(w, users.get(i));
        w.endArray();
        return w.toByteArray();
    }

    private static List<User> readAll(JsonReader r) {
        List<User> out = new ArrayList<>();
        r.beginArray();
        while (r.hasNextElement()) out.add(User.CODEC.read(r));
        return out;
    }

    // ------------------------------------------------------------------ util

    private static long sink;

    private interface Op { int run(); }

    private static void bench(String label, int bytes, Op op) { benchN(label, bytes, ITERS, 1, op); }

    private static void benchN(String label, int bytes, int iters, int recordsPerOp, Op op) {
        double best = Double.MAX_VALUE;
        for (int run = 0; run < RUNS; run++) {
            long start = System.nanoTime();
            for (int i = 0; i < iters; i++) sink += op.run();
            double ns = (System.nanoTime() - start) / (double) iters;
            best = Math.min(best, ns);
        }
        System.out.printf("%s  %10.1f ns/op   %6.2f M records/s   %7.0f MB/s%n",
                label, best, recordsPerOp * 1000.0 / best, bytes / best * 1000.0);
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
}
