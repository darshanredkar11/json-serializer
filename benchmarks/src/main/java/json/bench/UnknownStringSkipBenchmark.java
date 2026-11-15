package json.bench;

import json.Json;
import json.example.User;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Isolates the allocation cost of discarding unknown string values.
 * The payload is parsed into the same User codec; the large unknown property
 * is intentionally ignored by the codec.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class UnknownStringSkipBenchmark {
    @Param({"ascii", "utf8", "escaped"})
    public String kind;

    private byte[] json;

    @Setup(Level.Trial)
    public void setup() {
        String value;
        switch (kind) {
            case "ascii":
                value = "x".repeat(16 * 1024);
                break;
            case "utf8":
                value = "é你🚀".repeat(4096);
                break;
            case "escaped":
                value = "\\n".repeat(8192);
                break;
            default:
                throw new IllegalArgumentException("Unknown fixture: " + kind);
        }
        String text = "{\"unknown\":\"" + value + "\",\"id\":7}";
        json = text.getBytes(StandardCharsets.UTF_8);
        if (Json.parse(User.CODEC, json).id != 7L) {
            throw new IllegalStateException("Unknown-string skip benchmark setup failed");
        }
    }

    @Benchmark
    public User decodeWithUnknownString() {
        return Json.parse(User.CODEC, json);
    }
}
