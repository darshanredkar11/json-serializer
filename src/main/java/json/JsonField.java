package json;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A field name, pre-encoded once so that neither writing nor matching it
 * costs any per-record work.
 *
 * <p>Hold these in {@code static final} constants:
 * <pre>{@code
 * private static final JsonField ID = JsonField.of("id");
 * }</pre>
 * Writing becomes an {@code arraycopy} of {@code "id":} and matching becomes a
 * length check plus a vectorised {@link Arrays#equals} over the raw input bytes,
 * so no {@link String} is ever allocated for a key.
 */
public final class JsonField {

    /** The encoded key including quotes and colon, e.g. {@code "id":}. */
    final byte[] encoded;

    /** The raw UTF-8 name without quotes, e.g. {@code id}. */
    final byte[] name;

    private final String text;

    private JsonField(String name) {
        this.text = name;
        this.name = name.getBytes(StandardCharsets.UTF_8);
        JsonWriter w = new JsonWriter(this.name.length + 8);
        w.rawString(name);
        w.rawByte((byte) ':');
        this.encoded = w.toByteArray();
    }

    public static JsonField of(String name) { return new JsonField(name); }

    @Override public String toString() { return text; }
}
