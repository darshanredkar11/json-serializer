package json;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import json.example.User;

/**
 * Concurrency hardening: proves the {@code ThreadLocal}-based {@link Json}
 * statics are safe under real concurrent load, and that reentrant misuse
 * (a codec calling {@code Json.toBytes}/{@code parse} again instead of
 * delegating to a nested codec directly) fails loudly instead of silently
 * corrupting a shared buffer. {@code java json.ConcurrencyTest} exits
 * non-zero on failure, same convention as {@link JsonTest}.
 */
public final class ConcurrencyTest {

    private static final int THREADS = 16;
    private static final int ITERATIONS_PER_THREAD = 5000;

    private static int failures;
    private static int checks;

    public static void main(String[] args) throws InterruptedException {
        concurrentRoundTrips();
        reentrantWriteThrows();
        reentrantReadThrows();

        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ tests

    /** THREADS threads hammer the shared Json.toBytes/parse statics concurrently
     * with per-thread, per-iteration distinct data, and every round trip must
     * come back exactly as written — proves the thread-local isolation holds
     * under real contention, not just in a single-threaded reading of the code. */
    private static void concurrentRoundTrips() throws InterruptedException {
        AtomicInteger mismatches = new AtomicInteger();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                        User u = sample(threadId, i);
                        byte[] bytes = Json.toBytes(User.CODEC, u);
                        User back = Json.parse(User.CODEC, bytes);
                        if (!matches(u, back)) {
                            mismatches.incrementAndGet();
                            errors.add("thread " + threadId + " iter " + i + ": round trip mismatch");
                        }
                    }
                } catch (Throwable e) {
                    mismatches.incrementAndGet();
                    errors.add("thread " + threadId + ": " + e);
                } finally {
                    done.countDown();
                }
            }, "concurrency-test-" + t);
            threads[t].start();
        }
        start.countDown();
        done.await();

        checks++;
        if (mismatches.get() > 0) {
            failures++;
            System.out.println("FAIL concurrentRoundTrips: " + mismatches.get()
                + " mismatches across " + THREADS + " threads x " + ITERATIONS_PER_THREAD + " iterations");
            int shown = 0;
            for (String e : errors) {
                if (shown++ >= 5) break;
                System.out.println("  " + e);
            }
        } else {
            System.out.println("OK concurrentRoundTrips: " + (THREADS * ITERATIONS_PER_THREAD)
                + " round trips across " + THREADS + " threads, zero mismatches");
        }
    }

    /** A codec that re-enters Json.toBytes instead of delegating to the nested
     * codec directly — exactly the mistake the reentrancy guard exists to catch. */
    private static void reentrantWriteThrows() {
        checks++;
        JsonCodec<User> evil = new JsonCodec<User>() {
            public void write(JsonWriter w, User u) {
                Json.toBytes(User.CODEC, u); // wrong: should be User.CODEC.write(w, u)
            }
            public User read(JsonReader r) { throw new UnsupportedOperationException(); }
        };
        try {
            Json.toBytes(evil, sample(0, 0));
            failures++;
            System.out.println("FAIL reentrantWriteThrows: expected IllegalStateException, nothing was thrown");
        } catch (IllegalStateException expected) {
            System.out.println("OK reentrantWriteThrows: guard fired as expected");
        }

        // The guard must also reset cleanly after throwing, so a *correct*
        // call right after doesn't get wrongly blocked by a stuck flag.
        checks++;
        byte[] bytes = Json.toBytes(User.CODEC, sample(0, 0));
        if (bytes.length == 0) {
            failures++;
            System.out.println("FAIL reentrantWriteThrows: guard left WRITING stuck true after throwing");
        } else {
            System.out.println("OK reentrantWriteThrows: guard state resets after throwing");
        }
    }

    private static void reentrantReadThrows() {
        checks++;
        byte[] json = Json.toBytes(User.CODEC, sample(0, 0));
        JsonCodec<User> evil = new JsonCodec<User>() {
            public void write(JsonWriter w, User u) { throw new UnsupportedOperationException(); }
            public User read(JsonReader r) {
                return Json.parse(User.CODEC, json); // wrong: should be User.CODEC.read(r)
            }
        };
        try {
            Json.parse(evil, json);
            failures++;
            System.out.println("FAIL reentrantReadThrows: expected IllegalStateException, nothing was thrown");
        } catch (IllegalStateException expected) {
            System.out.println("OK reentrantReadThrows: guard fired as expected");
        }

        checks++;
        User back = Json.parse(User.CODEC, json);
        if (back == null) {
            failures++;
            System.out.println("FAIL reentrantReadThrows: guard left READING stuck true after throwing");
        } else {
            System.out.println("OK reentrantReadThrows: guard state resets after throwing");
        }
    }

    // ----------------------------------------------------------------- fixtures

    private static User sample(int threadId, int i) {
        User u = new User();
        u.id = threadId * 1_000_000L + i;
        u.name = "thread-" + threadId + "-user-" + i;
        u.email = (i % 3 == 0) ? null : ("user" + i + "@example.com");
        u.active = (i % 2 == 0);
        u.score = threadId + i * 0.001;
        u.tags.add("t" + threadId);
        u.tags.add("i" + i);
        if (i % 5 != 0) {
            User.Address a = new User.Address();
            a.street = threadId + " Main St";
            a.city = "City" + i;
            a.zip = 10000 + (i % 90000);
            u.address = a;
        }
        return u;
    }

    private static boolean matches(User a, User b) {
        return a.id == b.id
            && Objects.equals(a.name, b.name)
            && Objects.equals(a.email, b.email)
            && a.active == b.active
            && Double.compare(a.score, b.score) == 0
            && Objects.equals(a.tags, b.tags)
            && addressMatches(a.address, b.address);
    }

    private static boolean addressMatches(User.Address a, User.Address b) {
        if (a == null || b == null) return a == b;
        return Objects.equals(a.street, b.street) && Objects.equals(a.city, b.city) && a.zip == b.zip;
    }
}
