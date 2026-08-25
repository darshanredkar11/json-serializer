package json;

/** Thrown on malformed JSON input or unencodable values. */
public final class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonException(String message) { super(message); }
}
