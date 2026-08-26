# json-serializer

JSON ↔ native Java objects with no reflection, no dependencies, and no
intermediate tree. Write a small codec per type by hand, or annotate a
record with `@JsonRecord` and let a compile-time processor generate the
same code for you — either way, there is no reflection at runtime, ever;
the processor only exists at compile time and never ships in your classes.

Five source files, ~700 lines, plain `javac`.

```sh
./build.sh          # compile + run tests
./build.sh bench    # ... and the throughput benchmark
```

## Usage

Define a codec next to your type. This is the whole API surface:

```java
public final class Point {
    public int x, y;

    public static final JsonCodec<Point> CODEC = new JsonCodec<Point>() {
        private final JsonField X = JsonField.of("x");
        private final JsonField Y = JsonField.of("y");

        public void write(JsonWriter w, Point p) {
            w.beginObject();
            w.name(X).value(p.x);
            w.name(Y).value(p.y);
            w.endObject();
        }

        public Point read(JsonReader r) {
            Point p = new Point();
            r.beginObject();
            while (r.nextKey()) {
                if (r.keyIs(X)) p.x = r.readInt();
                else if (r.keyIs(Y)) p.y = r.readInt();
                else r.skipValue();
            }
            return p;
        }
    };
}
```

Then:

```java
byte[] bytes = Json.toBytes(Point.CODEC, p);
String text  = Json.toString(Point.CODEC, p);
Json.writeTo(Point.CODEC, p, outputStream);   // no byte[] copy

Point back   = Json.parse(Point.CODEC, bytes);
Point framed = Json.parse(Point.CODEC, buffer, offset, length);
```

`Json` keeps one writer and one reader per thread, so a round trip allocates
only the objects you actually asked for. For full control, use `new JsonWriter()`
/ `new JsonReader()` directly and call `reset()` between documents.

See [`User.java`](src/main/java/json/example/User.java) for nested objects,
arrays, nulls, and unknown-field handling.

## Combinators

Writing every array, nullable field, map, and enum by hand in each codec gets
old fast. `Codecs` has the common ones ready-made — still no reflection, each
one just a thin wrapper over the same `JsonWriter`/`JsonReader` calls you'd
write yourself:

```java
private static final JsonCodec<List<String>> TAGS   = Codecs.listOf(Codecs.STRING);
private static final JsonCodec<String> REASON        = Codecs.nullable(Codecs.STRING);
private static final JsonCodec<Map<String, Integer>> SCORES = Codecs.mapOf(Codecs.INT);
private static final JsonCodec<Status> STATUS        = Codecs.enumOf(Status.class);
```

They compose: `Codecs.nullable(Codecs.listOf(Codecs.enumOf(Status.class)))` is
a `JsonCodec<List<Status>>` that also accepts a JSON `null`. `Codecs.STRING`,
`BOOLEAN`, `INT`, `LONG`, `DOUBLE` exist mainly to be element/value types for
`listOf`/`mapOf`/`nullable`, though nothing stops you from using them
directly. `mapOf`'s keys aren't known ahead of time, so — unlike everything
else here — encoding one costs a per-call escape rather than a `JsonField`'s
one-time encoding; reading one goes through `JsonReader.key()`, which fully
decodes escapes (unlike `keyIs()`, which compares raw bytes against a known,
escape-free `JsonField` and is what you want for ordinary fixed-schema
fields).

## Code generation

Hand-writing a codec is still the ground truth, but you do not have to type
it yourself. Apply `@JsonRecord` to a record and the bundled annotation
processor emits a sibling `<Type>Codec` class in the same package. The
class contains a generated `public static final JsonCodec<Type> CODEC` and
serializes/deserializes the record with the same direct field access pattern
as a hand-written codec.

```java
import json.JsonName;
import json.JsonRecord;

@JsonRecord
public record TaxResponse(@JsonName("tax_owed") double taxOwed,
                           @JsonName("effective_rate") double effectiveRate) {}

// generates TaxResponseCodec in the same package:
byte[] bytes = Json.toBytes(TaxResponseCodec.CODEC, response);
```

This is a record-only annotation. If you apply it to a non-record type, the
processor emits a compile error: `@JsonRecord can only be applied to a record`.
The wire name for each component defaults to the record component name exactly
as written, with no automatic case conversion. `@JsonName` is the explicit
escape hatch for cases like snake_case wire keys.

Supported component types are:

- `int`, `long`, `double`, `boolean`
- `String`
- enums
- `List<T>` where `T` is itself supported
- nested records annotated with `@JsonRecord`
- any other type that exposes its own `public static final JsonCodec<T> CODEC`

Reference-typed components are treated as nullable automatically: when
writing, a `null` becomes JSON `null`; when reading, a present `null` becomes
`null`, and an absent field leaves the existing default value alone.

The processor is intentionally strict about unsupported shapes. `Map` and
other collection types are rejected with a clear compiler error that points at
the offending record component and suggests the correct `Codecs.mapOf(...)`
or `List<T>` pattern instead of producing a confusing generated-file error.

Generated classes cannot inject a `CODEC` field into the original record —
annotation processors can emit new files, not modify the source type that
triggered them. The generated field therefore lives on the sibling
`<Type>Codec` class, not on the record itself.

### Annotation processor discovery

For plain `javac` and Gradle builds, the bundled processor is auto-discovered
from the compile classpath via `META-INF/services` with no extra setup.

Under Maven, the usual caveat is that implicit processor discovery can be
unreliable when Maven runs compiler internals in-process. If your build does
not pick it up automatically, add it explicitly:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>com.jsonserializer</groupId>
        <artifactId>json-serializer</artifactId>
        <version>1.0.0</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

If your project is already compiling with the library on the classpath and the
processor is being discovered normally, no additional configuration is required.

## Why it's fast

| | |
|---|---|
| **No reflection** | Field access is a compiled getter, not a `Field.get`. Nothing to warm up, nothing to cache, works under GraalVM native-image with no config. |
| **Pre-encoded field names** | `JsonField.of("id")` encodes `"id":` once at class-init. Writing a name is an `arraycopy`; matching one is a length check plus a vectorised `Arrays.equals` against the raw input bytes — **keys never become `String`s**. |
| **Bytes end to end** | The writer appends UTF-8 into a growable `byte[]`; the reader parses the input `byte[]` in place. No `StringBuilder`, no `char[]` middleman, no `JsonNode` tree, no `Map<String, Object>`. |
| **Numbers formatted in place** | Two ASCII digits at a time out of a lookup table; parsed back digit-by-digit off the input. No `Long.toString`, no boxing, no substring. |
| **Single-pass strings** | ASCII with no escapes — the overwhelming majority — costs one bounds check and one `arraycopy` into a compact `String`. Escapes and multi-byte UTF-8 fall into a slower path only for the strings that need it. |
| **Reusable buffers** | `reset()` keeps the allocation. Steady state allocates the result and nothing else. |

## Measured

`java -cp out json.Bench` on a 217-byte record (7 fields, a 3-element array, a
nested object), JDK 22, Apple Silicon. Best of 5 runs × 1M iterations — not JMH,
so read it as an order of magnitude:

```
encode         168 ns/op    5.9 M records/s    1289 MB/s
decode         265 ns/op    3.8 M records/s     820 MB/s
```

Throughput holds flat on a 218 KB / 1000-record array, i.e. it is not buffer-growth
bound.

Independently cross-checked from a consuming project (`cpurest-java`, which uses this library instead of Jackson): an isolated, controlled Jackson-vs-json-serializer comparison on this same `User` shape measured 202.6/313.3 ns/op (encode/decode) for json-serializer against 399.3/587.4 ns/op for Jackson — 2.0×/1.9× faster, different machine state, same order of magnitude as the numbers above.

## Behaviour worth knowing

- **Unknown fields** are skipped by a byte scan (`skipValue`), never materialised.
- **Field order** on input doesn't matter; `nextKey()` loops until the closing brace.
- **Missing fields** leave your object's defaults untouched — the codec decides
  what's required, because it's your code.
- **`null`** decodes to `null` from `readString()`; use `r.isNull()` before
  delegating to a nested codec.
- **Whitespace** between tokens is tolerated everywhere.
- **`NaN`/`Infinity`** throw `JsonException` on write; JSON cannot represent them.
- **Doubles** take an exact fast path (≤15 significant digits, exponent within
  ±22) and fall back to `Double.parseDouble` otherwise, so results are always
  correctly rounded.
- **Validation is pragmatic**, not a conformance suite: malformed input throws
  `JsonException` with a byte offset, but the parser trusts the structure it is
  told to expect rather than pre-validating the whole document. Parse untrusted
  input into codecs that check their own invariants.
- **Not thread-safe** per instance. `Json`'s statics are, via thread locals —
  proven under real contention by `ConcurrencyTest` (16 threads × 5,000
  round trips), not just by reading the code.
- **Reentrant misuse throws, it doesn't corrupt.** Delegating to a nested
  type's codec must go through `CODEC.write(w, value)` / `CODEC.read(r)`
  directly on the writer/reader you were given (see `User.java`) — calling
  `Json.toBytes`/`toString`/`writeTo`/`parse` again on the same thread mid-call
  would fetch that thread's *same* writer/reader and `reset()` it out from
  under the outer call. `Json` detects this and throws `IllegalStateException`
  with an explanation instead of silently truncating your document.

## Layout

```
src/main/java/json/
  Json.java                        one-line entry points, thread-local buffers, reentrancy guard
  JsonCodec.java                   the interface you implement (or generate)
  Codecs.java                      ready-made combinators: nullable, listOf, mapOf, enumOf, primitives
  JsonRecord.java, JsonName.java   the two annotations code generation looks for
  JsonField.java                   a field name, encoded once
  JsonWriter.java                  UTF-8 byte-buffer writer
  JsonReader.java                  in-place pull parser
  JsonException.java
  processor/JsonRecordProcessor.java  generates <Type>Codec from an @JsonRecord
  example/User.java                worked example
src/main/resources/META-INF/services/
  javax.annotation.processing.Processor  SPI registration for JsonRecordProcessor
src/test/java/json/
  JsonTest.java                    97 checks, no test framework
  CodecsTest.java                  combinator checks (nullable, listOf, mapOf, enumOf, composed)
  ConcurrencyTest.java             concurrent round trips + reentrancy-guard checks
  processor/JsonRecordProcessorTest.java  compiles and runs real @JsonRecord sources via javac in-process
  Bench.java                       throughput benchmark
```

No required dependencies — copy the `json` package into any project, or
point `javac` at it (`./build.sh`). A `pom.xml` is also provided for
projects that want it as a proper Maven artifact (`mvn install`); it
doesn't add any runtime dependency of its own — the hand-test classes are
`main()` runners invoked via `exec-maven-plugin` during `mvn test`, not
JUnit, and the annotation processor's own dependencies
(`javax.annotation.processing`, `javax.lang.model`) are part of the JDK,
not an external library.

## License

MIT (see `LICENSE`).
