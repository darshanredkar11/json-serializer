package json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides a {@code @JsonRecord} component's wire field name. The default
 * is the component's own name, verbatim — this is the explicit escape
 * hatch for the cases that need something else, e.g. matching a Rust
 * {@code serde} struct's snake_case fields:
 *
 * <pre>{@code
 * @JsonRecord
 * public record TaxResponse(@JsonName("tax_owed") double taxOwed, ...) {}
 * }</pre>
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.SOURCE)
public @interface JsonName {
    String value();
}
