package json.bench;

import json.Json;
import json.JsonCodec;
import json.JsonField;
import json.JsonReader;
import json.JsonRecord;
import json.JsonWriter;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Isolates generated compile-time key dispatch against a linear codec baseline. */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class GeneratedDispatchBenchmark {

    @JsonRecord
    public record WideRecord(int id, int f1, int f2, int f3, int f4, int f5,
                             int f6, int f7, int f8, int f9, int f10, int f11) {}

    private static final byte[] JSON = ("{""id"":1,""f1"":2,""f2"":3,""f3"":4,""f4"":5,"
            + ""f5"":6,""f6"":7,""f7"":8,""f8"":9,""f9"":10,""f10"":11,""f11"":12}")
            .getBytes(StandardCharsets.UTF_8);

    private static final JsonField ID = JsonField.of("id");
    private static final JsonField F1 = JsonField.of("f1");
    private static final JsonField F2 = JsonField.of("f2");
    private static final JsonField F3 = JsonField.of("f3");
    private static final JsonField F4 = JsonField.of("f4");
    private static final JsonField F5 = JsonField.of("f5");
    private static final JsonField F6 = JsonField.of("f6");
    private static final JsonField F7 = JsonField.of("f7");
    private static final JsonField F8 = JsonField.of("f8");
    private static final JsonField F9 = JsonField.of("f9");
    private static final JsonField F10 = JsonField.of("f10");
    private static final JsonField F11 = JsonField.of("f11");

    private static final JsonCodec<WideRecord> LINEAR = new JsonCodec<>() {
        @Override public void write(JsonWriter w, WideRecord v) {
            w.beginObject().name(ID).value(v.id()).name(F1).value(v.f1())
             .name(F2).value(v.f2()).name(F3).value(v.f3()).name(F4).value(v.f4())
             .name(F5).value(v.f5()).name(F6).value(v.f6()).name(F7).value(v.f7())
             .name(F8).value(v.f8()).name(F9).value(v.f9()).name(F10).value(v.f10())
             .name(F11).value(v.f11()).endObject();
        }

        @Override public WideRecord read(JsonReader r) {
            int id=0,f1=0,f2=0,f3=0,f4=0,f5=0,f6=0,f7=0,f8=0,f9=0,f10=0,f11=0;
            r.beginObject();
            while (r.nextKey()) {
                if (r.keyIs(ID)) id=r.readInt();
                else if (r.keyIs(F1)) f1=r.readInt();
                else if (r.keyIs(F2)) f2=r.readInt();
                else if (r.keyIs(F3)) f3=r.readInt();
                else if (r.keyIs(F4)) f4=r.readInt();
                else if (r.keyIs(F5)) f5=r.readInt();
                else if (r.keyIs(F6)) f6=r.readInt();
                else if (r.keyIs(F7)) f7=r.readInt();
                else if (r.keyIs(F8)) f8=r.readInt();
                else if (r.keyIs(F9)) f9=r.readInt();
                else if (r.keyIs(F10)) f10=r.readInt();
                else if (r.keyIs(F11)) f11=r.readInt();
                else r.skipValue();
            }
            return new WideRecord(id,f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11);
        }
    };

    @Setup(Level.Trial)
    public void verify() {
        WideRecord generated = Json.parse(WideRecordCodec.CODEC, JSON);
        WideRecord linear = Json.parse(LINEAR, JSON);
        if (!generated.equals(linear)) throw new IllegalStateException("generated/linear mismatch");
    }

    @Benchmark public WideRecord generatedHashDispatch() {
        return Json.parse(WideRecordCodec.CODEC, JSON);
    }

    @Benchmark public WideRecord linearKeyChain() {
        return Json.parse(LINEAR, JSON);
    }
}
