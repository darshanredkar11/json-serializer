package json.bench;

import json.Json;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Exercises the generated hash-dispatch fallback with a known field whose
 * JSON spelling is escaped. The f7 key is deliberately last in declaration
 * order, forcing all candidate comparisons in the fallback path.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class EscapedKeyDispatchBenchmark {
    private byte[] jsonBytes;
    private WideRecord expected;

    @Setup(Level.Trial)
    public void setup() {
        expected = new WideRecord("alpha", 17, 1234567890123L, 19.25,
                true, "omega", 42, 8);
        String escapedF7 = "f" + '\\' + "u0037";
        String json = "{\"f0\":\"alpha\",\"f1\":17,\"f2\":1234567890123,"
                + "\"f3\":19.25,\"f4\":true,\"f5\":\"omega\",\"f6\":42,"
                + "\"" + escapedF7 + "\":8}";
        jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        if (!expected.equals(Json.parse(WideRecordCodec.CODEC, jsonBytes))) {
            throw new IllegalStateException("Escaped-key benchmark setup mismatch");
        }
    }

    @Benchmark
    public WideRecord decodeWithEscapedKnownKey() {
        return Json.parse(WideRecordCodec.CODEC, jsonBytes);
    }
}
