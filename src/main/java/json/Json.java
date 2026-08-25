package json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One-line entry points. Each thread reuses a single writer and reader, so a
 * round trip allocates only the result.
 *
 * <pre>{@code
 * byte[] bytes = Json.toBytes(User.CODEC, user);
 * User back    = Json.parse(User.CODEC, bytes);
 * }</pre>
 */
public final class Json {

    private static final ThreadLocal<JsonWriter> WRITER = ThreadLocal.withInitial(JsonWriter::new);
    private static final ThreadLocal<JsonReader> READER = ThreadLocal.withInitial(JsonReader::new);

    // Guards against a same-thread reentrancy hazard: since WRITER/READER are
    // one-per-thread, a codec that delegates to a nested type by calling
    // Json.toBytes/toString/writeTo/parse again (instead of calling that
    // type's CODEC.write(w, ...)/read(r) directly on the writer/reader it was
    // already given) would fetch the *same* thread-local instance and reset()
    // it mid-write, silently truncating/corrupting the outer document. These
    // flags turn that into a clear exception instead of silent corruption.
    private static final ThreadLocal<Boolean> WRITING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> READING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Json() {}

    public static <T> byte[] toBytes(JsonCodec<T> codec, T value) {
        beginWrite();
        try {
            JsonWriter w = WRITER.get().reset();
            codec.write(w, value);
            return w.toByteArray();
        } finally {
            endWrite();
        }
    }

    public static <T> String toString(JsonCodec<T> codec, T value) {
        beginWrite();
        try {
            JsonWriter w = WRITER.get().reset();
            codec.write(w, value);
            return w.toString();
        } finally {
            endWrite();
        }
    }

    /** Encodes straight to a stream, without materialising a byte[] copy. */
    public static <T> void writeTo(JsonCodec<T> codec, T value, OutputStream out) throws IOException {
        beginWrite();
        try {
            JsonWriter w = WRITER.get().reset();
            codec.write(w, value);
            w.writeTo(out);
        } finally {
            endWrite();
        }
    }

    public static <T> T parse(JsonCodec<T> codec, byte[] json) {
        beginRead();
        try {
            return codec.read(READER.get().reset(json));
        } finally {
            endRead();
        }
    }

    public static <T> T parse(JsonCodec<T> codec, byte[] json, int offset, int length) {
        beginRead();
        try {
            return codec.read(READER.get().reset(json, offset, length));
        } finally {
            endRead();
        }
    }

    public static <T> T parse(JsonCodec<T> codec, String json) {
        return parse(codec, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void beginWrite() {
        if (WRITING.get()) {
            throw new IllegalStateException(
                "Json.toBytes/toString/writeTo called reentrantly on the same thread. "
              + "A codec must delegate to a nested type by calling that type's "
              + "CODEC.write(w, value) directly on the writer it was given, not by "
              + "calling Json.toBytes/toString/writeTo again — the thread-local writer "
              + "is shared, and re-entering would reset() out from under the outer call. "
              + "See json.example.User for the correct nested-codec pattern.");
        }
        WRITING.set(Boolean.TRUE);
    }

    private static void endWrite() { WRITING.set(Boolean.FALSE); }

    private static void beginRead() {
        if (READING.get()) {
            throw new IllegalStateException(
                "Json.parse called reentrantly on the same thread. A codec must delegate "
              + "to a nested type by calling that type's CODEC.read(r) directly on the "
              + "reader it was given, not by calling Json.parse again — the thread-local "
              + "reader is shared, and re-entering would reset() out from under the outer "
              + "call. See json.example.User for the correct nested-codec pattern.");
        }
        READING.set(Boolean.TRUE);
    }

    private static void endRead() { READING.set(Boolean.FALSE); }
}
