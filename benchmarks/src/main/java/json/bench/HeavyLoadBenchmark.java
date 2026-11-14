package json.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import json.Json;
import json.example.User;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Sustained concurrent load test. Run one implementation/operation/thread-count
 * combination per JVM so peak RSS and GC counters are attributable to that case.
 *
 * Args: <library: own|jackson|gson> <operation: encode|decode>
 *       <threads> <warmup-seconds> <measurement-seconds>
 */
public final class HeavyLoadBenchmark {
    private static final ObjectMapper JACKSON = new ObjectMapper();
    private static final Gson GSON = new Gson();
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private HeavyLoadBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: <own|jackson|gson> <encode|decode> <threads> <warmup-seconds> <measurement-seconds>");
        }
        String library = args[0].toLowerCase(Locale.ROOT);
        String operation = args[1].toLowerCase(Locale.ROOT);
        int threadCount = Integer.parseInt(args[2]);
        int warmupSeconds = Integer.parseInt(args[3]);
        int measurementSeconds = Integer.parseInt(args[4]);
        if (!List.of("own", "jackson", "gson").contains(library)
                || !List.of("encode", "decode").contains(operation)
                || threadCount < 1 || warmupSeconds < 1 || measurementSeconds < 1) {
            throw new IllegalArgumentException("Invalid benchmark arguments");
        }

        User user = sample(256);
        byte[] bytes = Json.toBytes(User.CODEC, user);
        String json = new String(bytes, StandardCharsets.UTF_8);
        validate(user, Json.parse(User.CODEC, bytes));
        validate(user, JACKSON.readValue(bytes, User.class));
        validate(user, GSON.fromJson(json, User.class));

        if (THREADS.isThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled()) {
            THREADS.setThreadCpuTimeEnabled(true);
        }
        boolean allocationSupported = THREADS.isThreadAllocatedMemorySupported();
        if (allocationSupported && !THREADS.isThreadAllocatedMemoryEnabled()) {
            THREADS.setThreadAllocatedMemoryEnabled(true);
        }

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            try { pool.resetPeakUsage(); } catch (UnsupportedOperationException ignored) { }
        }
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTime();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        WorkerResult[] results = new WorkerResult[threadCount];
        Thread[] workers = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            workers[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    results[index] = execute(library, operation, user, bytes, json,
                            warmupSeconds, measurementSeconds, allocationSupported);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }, "serializer-load-" + i);
            workers[i].start();
        }

        ready.await();
        start.countDown();
        for (Thread worker : workers) worker.join();

        long operations = 0;
        long allocated = 0;
        long cpuNanos = 0;
        long wallNanos = 0;
        long checksum = 0;
        for (WorkerResult result : results) {
            operations += result.operations;
            allocated += result.allocatedBytes;
            cpuNanos += result.cpuNanos;
            wallNanos = Math.max(wallNanos, result.wallNanos);
            checksum += result.checksum;
        }

        long peakHeapUsed = ManagementFactory.getMemoryPoolMXBeans().stream()
                .map(MemoryPoolMXBean::getPeakUsage)
                .filter(java.util.Objects::nonNull)
                .mapToLong(MemoryUsage::getUsed).sum();
        // Measure retained heap after the timed section, not during throughput measurement.
        System.gc();
        Thread.sleep(150);
        long retainedHeapAfterGc = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long maxHeap = Runtime.getRuntime().maxMemory();

        int processors = Runtime.getRuntime().availableProcessors();
        double throughput = operations / (wallNanos / 1_000_000_000.0);
        double allocatedPerOp = allocationSupported && operations > 0
                ? (double) allocated / operations : -1.0;
        double oneCoreCpuPercent = wallNanos > 0 ? 100.0 * cpuNanos / wallNanos : 0.0;
        double machineCpuPercent = processors > 0 ? oneCoreCpuPercent / processors : 0.0;

        System.out.printf(Locale.ROOT,
                "{\"library\":\"%s\",\"operation\":\"%s\",\"threads\":%d,"
                + "\"payload_bytes\":%d,\"processors\":%d,\"operations\":%d,"
                + "\"wall_ms\":%.2f,\"ops_per_sec\":%.2f,"
                + "\"allocated_bytes_per_op\":%.2f,\"allocated_bytes_total\":%d,"
                + "\"cpu_core_percent\":%.2f,\"cpu_machine_percent\":%.2f,"
                + "\"gc_count\":%d,\"gc_time_ms\":%d,\"heap_used_bytes\":%d,"
                + "\"peak_heap_used_bytes\":%d,\"retained_heap_after_gc_bytes\":%d,"
                + "\"max_heap_bytes\":%d,\"peak_rss_kb\":%d,\"checksum\":%d}%n",
                library, operation, threadCount, bytes.length, processors, operations,
                wallNanos / 1_000_000.0, throughput, allocatedPerOp, allocated,
                oneCoreCpuPercent, machineCpuPercent,
                gcCount() - gcCountBefore, gcTime() - gcTimeBefore,
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                peakHeapUsed, retainedHeapAfterGc, maxHeap, peakRssKb(), checksum);
    }

    private static WorkerResult execute(String library, String operation, User user,
                                        byte[] bytes, String json, int warmupSeconds,
                                        int measurementSeconds, boolean allocationSupported) throws Exception {
        long warmupEnd = System.nanoTime() + warmupSeconds * 1_000_000_000L;
        long threadId = Thread.currentThread().getId();
        while (System.nanoTime() < warmupEnd) runOne(library, operation, user, bytes, json);

        long allocatedBefore = allocationSupported ? THREADS.getThreadAllocatedBytes(threadId) : 0;
        long cpuBefore = THREADS.isThreadCpuTimeSupported() ? THREADS.getThreadCpuTime(threadId) : 0;
        long start = System.nanoTime();
        long deadline = start + measurementSeconds * 1_000_000_000L;
        long operations = 0;
        long checksum = 0;
        while (System.nanoTime() < deadline) {
            checksum += runOne(library, operation, user, bytes, json);
            operations++;
        }
        long end = System.nanoTime();
        long cpuAfter = THREADS.isThreadCpuTimeSupported() ? THREADS.getThreadCpuTime(threadId) : cpuBefore;
        long allocatedAfter = allocationSupported ? THREADS.getThreadAllocatedBytes(threadId) : allocatedBefore;
        return new WorkerResult(operations, allocatedAfter - allocatedBefore,
                cpuAfter - cpuBefore, end - start, checksum);
    }

    private static long runOne(String library, String operation, User user, byte[] bytes, String json)
            throws Exception {
        if ("encode".equals(operation)) {
            return switch (library) {
                case "own" -> Json.toBytes(User.CODEC, user).length;
                case "jackson" -> JACKSON.writeValueAsBytes(user).length;
                case "gson" -> GSON.toJson(user).getBytes(StandardCharsets.UTF_8).length;
                default -> throw new IllegalArgumentException(library);
            };
        }
        User decoded = switch (library) {
            case "own" -> Json.parse(User.CODEC, bytes);
            case "jackson" -> JACKSON.readValue(bytes, User.class);
            case "gson" -> GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), User.class);
            default -> throw new IllegalArgumentException(library);
        };
        return decoded.id + decoded.tags.size() + decoded.name.length();
    }

    private static User sample(int tagCount) {
        User u = new User();
        u.id = 1234567890123L;
        u.name = "Ada Lovelace";
        u.email = "ada@example.com";
        u.active = true;
        u.score = 99.5;
        for (int i = 0; i < tagCount; i++) u.tags.add("engineering-domain-tag-" + i);
        u.address = new User.Address();
        u.address.street = "12 Analytical Engine Way";
        u.address.city = "London";
        u.address.zip = 90210;
        return u;
    }

    private static void validate(User expected, User actual) {
        if (actual == null || expected.id != actual.id || expected.tags.size() != actual.tags.size()
                || !java.util.Objects.equals(expected.name, actual.name)
                || !java.util.Objects.equals(expected.email, actual.email)
                || !java.util.Objects.equals(expected.tags, actual.tags)
                || actual.address == null
                || !java.util.Objects.equals(expected.address.street, actual.address.street)
                || !java.util.Objects.equals(expected.address.city, actual.address.city)
                || expected.address.zip != actual.address.zip) {
            throw new IllegalStateException("Concurrent benchmark fixture mismatch");
        }
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(v -> v >= 0).sum();
    }

    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(v -> v >= 0).sum();
    }

    private static long peakRssKb() {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {
            // /proc is Linux-specific; leave the metric unavailable elsewhere.
        }
        return -1;
    }

    private record WorkerResult(long operations, long allocatedBytes, long cpuNanos,
                                long wallNanos, long checksum) {}
}
