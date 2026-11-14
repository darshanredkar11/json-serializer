package json.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import json.Json;
import json.JsonRecord;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Exercises the annotation-processor-generated codec rather than the
 * hand-written example codec. The payload deliberately contains an unknown
 * numeric field so decode also measures the allocation-free skip path.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class GeneratedRecordBenchmark {
    private WideRecord value;
    private byte[] jsonBytes;
    private String jsonText;
    private ObjectMapper jackson;
    private Gson gson;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        value = new WideRecord("alpha", 17, 1234567890123L, 19.25, true, "omega", 42, 8);
        jackson = new ObjectMapper();
        gson = new Gson();
        jsonText = """
                {"f7":8,"unknownNumber":12345678901234567890.123e-7,
                 "f5":"omega","f4":true,"f3":19.25,
                 "f2":1234567890123,"f1":17,"f0":"alpha","f6":42}
                """;
        jsonBytes = jsonText.getBytes(StandardCharsets.UTF_8);
        if (!value.equals(Json.parse(WideRecordCodec.CODEC, jsonBytes))
                || !value.equals(jackson.readValue(jsonBytes, WideRecord.class))
                || !value.equals(gson.fromJson(jsonText, WideRecord.class))) {
            throw new IllegalStateException("Generated-record benchmark setup mismatch");
        }
    }

    @Benchmark public byte[] ownEncodeBytes() { return Json.toBytes(WideRecordCodec.CODEC, value); }
    @Benchmark public WideRecord ownDecodeBytes() { return Json.parse(WideRecordCodec.CODEC, jsonBytes); }
    @Benchmark public byte[] jacksonEncodeBytes() throws Exception { return jackson.writeValueAsBytes(value); }
    @Benchmark public WideRecord jacksonDecodeBytes() throws Exception { return jackson.readValue(jsonBytes, WideRecord.class); }
    @Benchmark public byte[] gsonEncodeBytes() { return gson.toJson(value).getBytes(StandardCharsets.UTF_8); }
    @Benchmark public WideRecord gsonDecodeBytes() { return gson.fromJson(jsonText, WideRecord.class); }
}

@JsonRecord
record WideRecord(String f0, int f1, long f2, double f3,
                  boolean f4, String f5, int f6, long f7) {}
