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

Hand-writing a codec is still the ground truth, but you don't have to type
it yourself. Annotate a record and a compile-time processor (bundled in
this same jar, auto-discovered via `META-INF/services` on `javac`'s
annotation processor path) generates a `<Type>Codec` class next to it —
structurally the exact same code you'd write by hand, not a generic
reflective mapper:

```java
@JsonRecord
public record TaxResponse(@JsonName("tax_owed") double taxOwed,
                           @JsonName("effective_rate") double effectiveRate) {}

// generates TaxResponseCodec in the same package:
byte[] bytes = Json.toBytes(TaxResponseCodec.CODEC, response);
```

The wire name for a component defaults to its Java name, verbatim — no
guessed camelCase-to-snake_case conversion, matching this library's stance
that naming should be explicit (see "Combinators" above for why `mapOf`
works the same way). `@JsonName` is the escape hatch for the cases that
need something else, e.g. matching a Rust `serde` struct's snake_case
fields.

Supported component types: `int`, `long`, `double`, `boolean`, `String`,
an enum, a `List<T>` of any supported type (nested lists included), or
another type that is either itself `@JsonRecord`-annotated or exposes its
own hand-written `CODEC` field. Every reference-typed component is treated
as nullable automatically — no `@Nullable` annotation needed, no cost
beyond the one branch a hand-written codec would have anyway. `Map` isn't
auto-supported (there's no single obvious codec for two type parameters);
the processor tells you so directly, at the record, with a `Codecs.mapOf`
pointer, rather than leaving you to decode a "cannot find symbol" error in
generated code you never asked to look at.

Generated classes can't get a `CODEC` field injected into your original
type — a standard annotation processor can only emit new files, never
modify the one it was triggered by — so the generated field lives on the
sibling `<Type>Codec` class instead. If you're bridging this into your own
reflective dispatch layer the way `Wire` in `cpurest-java` does, look for
both conventions: a `CODEC` field directly on the type, then a `<Type>Codec`
sibling class, in that order.

**Maven**: implicit annotation-processor discovery from the plain compile
classpath is unreliable under Maven's default in-process compilation (a
long-standing Maven quirk that affects every JVM annotation processor, not
something specific to this one — Lombok, MapStruct, and Dagger all document
the identical workaround). Declare the processor path explicitly:

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

Gradle and a plain `javac` invocation both pick the processor up from the
ordinary compile classpath with no extra configuration.

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
