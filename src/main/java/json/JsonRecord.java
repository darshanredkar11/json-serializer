package json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record for compile-time codec generation. An annotation
 * processor bundled in this same jar (auto-discovered via
 * {@code META-INF/services} — nothing to configure beyond having
 * json-serializer on the compile classpath) generates a
 * {@code <Type>Codec} class in the same package, exposing
 * {@code public static final JsonCodec<Type> CODEC}. The generated code
 * is written to look exactly like a codec you'd hand-write — direct
 * component access, {@link JsonField} constants, one {@code write}/
 * {@code read} pair — not a generic reflective mapper. There is no
 * reflection anywhere in the result, not even at compile time in the
 * generated class; the only place any type introspection happens is in
 * the processor itself, while producing the source file.
 *
 * <p>{@code @Retention(SOURCE)}: this annotation never reaches a
 * {@code .class} file, so it costs nothing at runtime, not even a
 * constant-pool entry.
 *
 * <p>Supported component types: {@code int}, {@code long}, {@code double},
 * {@code boolean}, {@code String}, an enum, a {@code List<T>} of any
 * supported type (nested lists included), or another type that is either
 * itself {@code @JsonRecord}-annotated or exposes its own hand-written
 * {@code public static final JsonCodec<T> CODEC} field. Every
 * reference-typed component is treated as nullable — the generated
 * {@code read} guards with {@code isNull()}, {@code write} guards with a
 * null check — because a JSON value being absent or {@code null} is the
 * common case, not the exception; primitive components can't be null and
 * get no guard, no branch, nothing extra at all.
 *
 * <p>The wire name for a component defaults to its Java name, verbatim —
 * no automatic camelCase-to-snake_case or any other transform, matching
 * this library's stance that naming strategies should be explicit, not
 * guessed. Use {@link JsonName} on a component for the cases that need a
 * different wire name.
 *
 * <pre>{@code
 * @JsonRecord
 * public record TaxResponse(@JsonName("tax_owed") double taxOwed,
 *                            @JsonName("effective_rate") double effectiveRate) {}
 *
 * // generates TaxResponseCodec in the same package:
 * byte[] bytes = Json.toBytes(TaxResponseCodec.CODEC, value);
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface JsonRecord {
}
