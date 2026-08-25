# json-serializer

JSON ↔ native Java objects with no reflection, no annotations, no dependencies,
and no intermediate tree. You write a small codec per type; it compiles down to
plain field access over a byte buffer.

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
  Json.java             one-line entry points, thread-local buffers, reentrancy guard
  JsonCodec.java        the interface you implement
  JsonField.java        a field name, encoded once
  JsonWriter.java       UTF-8 byte-buffer writer
  JsonReader.java       in-place pull parser
  JsonException.java
  example/User.java     worked example
src/test/java/json/
  JsonTest.java         97 checks, no test framework
  ConcurrencyTest.java  concurrent round trips + reentrancy-guard checks
  Bench.java            throughput benchmark
```

No dependencies — copy the `json` package into any project, or point `javac`
at it (`./build.sh`). A `pom.xml` is also provided for projects that want it
as a proper Maven artifact (`mvn install`); it doesn't add any dependency of
its own — the test classes are `main()` runners invoked via `exec-maven-plugin`
during `mvn test`, not JUnit.
