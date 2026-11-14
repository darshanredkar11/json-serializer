package json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One-line entry points. Each thread reuses a single writer and reader, so a
 * round trip allocates only the result.
 */
public final class Json {

    private static final ThreadLocal<JsonWriter> WRITER = ThreadLocal.withInitial(JsonWriter::new);
    private static final ThreadLocal<JsonReader> READER = ThreadLocal.withInitial(JsonReader::new);
    private static final ThreadLocal<Boolean> WRITING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> READING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // Bound memory retained by long-lived platform threads after oversized requests.
    private static final int MAX_RETAINED_WRITER_BYTES = 1024 * 1024;
    private static final int MAX_RETAINED_READER_CHARS = 64 * 1024;

    private Json() {}

    public static <T> byte[] toBytes(JsonCodec<T> codec, T value) {
        beginWrite();
        JsonWriter w = WRITER.get();
        try {
            w.reset();
            codec.write(w, value);
            return w.toByteArray();
        } finally {
            w.resetAndTrim(MAX_RETAINED_WRITER_BYTES);
            endWrite();
        }
    }

    public static <T> String toString(JsonCodec<T> codec, T value) {
        beginWrite();
        JsonWriter w = WRITER.get();
        try {
            w.reset();
            codec.write(w, value);
            return w.toString();
        } finally {
            w.resetAndTrim(MAX_RETAINED_WRITER_BYTES);
            endWrite();
        }
    }

    public static <T> void writeTo(JsonCodec<T> codec, T value, OutputStream out) throws IOException {
        beginWrite();
        JsonWriter w = WRITER.get();
        try {
            w.reset();
            codec.write(w, value);
            w.writeTo(out);
        } finally {
            w.resetAndTrim(MAX_RETAINED_WRITER_BYTES);
            endWrite();
        }
    }

    public static <T> T parse(JsonCodec<T> codec, byte[] json) {
        beginRead();
        JsonReader r = READER.get();
        try {
            r.reset(json);
            T value = codec.read(r);
            r.requireEnd();
            return value;
        } finally {
            r.releaseAfterUse(MAX_RETAINED_READER_CHARS);
            endRead();
        }
    }

    public static <T> T parse(JsonCodec<T> codec, byte[] json, int offset, int length) {
        beginRead();
        JsonReader r = READER.get();
        try {
            r.reset(json, offset, length);
            T value = codec.read(r);
            r.requireEnd();
            return value;
        } finally {
            r.releaseAfterUse(MAX_RETAINED_READER_CHARS);
            endRead();
        }
    }

    public static <T> T parse(JsonCodec<T> codec, String json) {
        return parse(codec, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void beginWrite() {
        if (WRITING.get()) {
            throw new IllegalStateException("Json.toBytes/toString/writeTo called reentrantly on the same thread. "
              + "A codec must delegate to a nested type by calling that type's CODEC.write(w, value) directly.");
        }
        WRITING.set(Boolean.TRUE);
    }

    private static void endWrite() { WRITING.set(Boolean.FALSE); }

    private static void beginRead() {
        if (READING.get()) {
            throw new IllegalStateException("Json.parse called reentrantly on the same thread. "
              + "A codec must delegate to a nested type by calling that type's CODEC.read(r) directly.");
        }
        READING.set(Boolean.TRUE);
    }

    private static void endRead() { READING.set(Boolean.FALSE); }
}
