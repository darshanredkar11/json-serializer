package json;

/**
 * A hand-written (or code-generated) reader/writer pair for one type.
 * No reflection, no annotations, no runtime type inspection: the codec
 * <em>is</em> the schema, so field access compiles down to plain getters.
 */
public interface JsonCodec<T> {

    /** Writes {@code value} as a JSON value (usually an object) into {@code w}. */
    void write(JsonWriter w, T value);

    /** Reads one JSON value from {@code r} and returns the decoded object. */
    T read(JsonReader r);
}
