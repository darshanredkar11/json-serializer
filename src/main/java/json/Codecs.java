package json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ready-made {@link JsonCodec} combinators for the handful of shapes that
 * show up in almost every schema: primitives, nullable fields, lists,
 * string-keyed maps, and enums. Zero reflection, same as everything else
 * here — each combinator is a thin, statically-typed wrapper over the
 * existing {@link JsonWriter}/{@link JsonReader} primitives, not a new
 * mechanism, so composing them costs one extra (monomorphic, easily
 * inlined) method call per level, never a lookup.
 *
 * <p>These don't replace hand-written codecs for your own types — a codec
 * <em>is</em> the schema, and that's still the point (see {@link JsonCodec}).
 * They exist so a nullable field, a {@code List<T>}, a string-keyed map, or
 * an enum doesn't need its own hand-rolled null-check / array-loop /
 * {@code valueOf} written out at every call site:
 *
 * <pre>{@code
 * private static final JsonCodec<List<String>> TAGS = Codecs.listOf(Codecs.STRING);
 * private static final JsonCodec<String> REASON = Codecs.nullable(Codecs.STRING);
 * private static final JsonCodec<Status> STATUS = Codecs.enumOf(Status.class);
 * }</pre>
 */
public final class Codecs {
    private Codecs() {}

    public static final JsonCodec<String> STRING = new JsonCodec<String>() {
        public void write(JsonWriter w, String v) { w.value(v); }
        public String read(JsonReader r) { return r.readString(); }
    };

    public static final JsonCodec<Boolean> BOOLEAN = new JsonCodec<Boolean>() {
        public void write(JsonWriter w, Boolean v) { w.value(v.booleanValue()); }
        public Boolean read(JsonReader r) { return r.readBoolean(); }
    };

    public static final JsonCodec<Integer> INT = new JsonCodec<Integer>() {
        public void write(JsonWriter w, Integer v) { w.value(v.intValue()); }
        public Integer read(JsonReader r) { return r.readInt(); }
    };

    public static final JsonCodec<Long> LONG = new JsonCodec<Long>() {
        public void write(JsonWriter w, Long v) { w.value(v.longValue()); }
        public Long read(JsonReader r) { return r.readLong(); }
    };

    public static final JsonCodec<Double> DOUBLE = new JsonCodec<Double>() {
        public void write(JsonWriter w, Double v) { w.value(v.doubleValue()); }
        public Double read(JsonReader r) { return r.readDouble(); }
    };

    /**
     * Wraps {@code codec} to write/read JSON {@code null} for a Java
     * {@code null}, instead of every call site hand-checking {@link JsonReader#isNull()}.
     */
    public static <T> JsonCodec<T> nullable(JsonCodec<T> codec) {
        return new JsonCodec<T>() {
            public void write(JsonWriter w, T v) {
                if (v == null) w.nullValue(); else codec.write(w, v);
            }

            public T read(JsonReader r) {
                return r.isNull() ? null : codec.read(r);
            }
        };
    }

    /** A JSON array of {@code element}-encoded values, decoded into an {@link ArrayList}. */
    public static <T> JsonCodec<List<T>> listOf(JsonCodec<T> element) {
        return new JsonCodec<List<T>>() {
            public void write(JsonWriter w, List<T> list) {
                w.beginArray();
                for (T item : list) element.write(w, item);
                w.endArray();
            }

            public List<T> read(JsonReader r) {
                List<T> out = new ArrayList<>();
                r.beginArray();
                while (r.hasNextElement()) out.add(element.read(r));
                return out;
            }
        };
    }

    /**
     * A JSON object of string keys to {@code value}-encoded values, decoded
     * into a {@link LinkedHashMap} (insertion order preserved). Keys aren't
     * known ahead of time, so they go through {@link JsonWriter#name(String)}
     * (a per-call encode) rather than a pre-encoded {@link JsonField} — this
     * is the one combinator here that isn't free, by construction.
     */
    public static <V> JsonCodec<Map<String, V>> mapOf(JsonCodec<V> value) {
        return new JsonCodec<Map<String, V>>() {
            public void write(JsonWriter w, Map<String, V> map) {
                w.beginObject();
                for (Map.Entry<String, V> e : map.entrySet()) {
                    w.name(e.getKey());
                    value.write(w, e.getValue());
                }
                w.endObject();
            }

            public Map<String, V> read(JsonReader r) {
                Map<String, V> out = new LinkedHashMap<>();
                r.beginObject();
                while (r.nextKey()) {
                    out.put(r.key(), value.read(r));
                }
                return out;
            }
        };
    }

    /** Encodes an enum as its {@link Enum#name()} string; decodes via {@link Enum#valueOf}. */
    public static <E extends Enum<E>> JsonCodec<E> enumOf(Class<E> type) {
        return new JsonCodec<E>() {
            public void write(JsonWriter w, E v) { w.value(v.name()); }
            public E read(JsonReader r) { return Enum.valueOf(type, r.readString()); }
        };
    }
}
