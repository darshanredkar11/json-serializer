# JSON serializer: JMH comparison

This module compares this project's hand-written codec with Jackson Databind 2.x and Gson using the same Java object graph and semantically equivalent JSON. It measures serialization and deserialization separately for native String APIs and UTF-8 byte-array APIs.

## Versions

- JMH 1.37
- Jackson Databind 2.23.0
- Gson 2.15.0
- Project serializer: the exact source checkout installed by the build below

Jackson 2.x is used deliberately because this benchmark compares the widely deployed `com.fasterxml.jackson` API family. Jackson 3.x is a separate major version with different Maven coordinates and Java packages; it should be added as a separate benchmark target rather than silently replacing Jackson 2.x.

## Build

Run from the repository root:

```bash
mvn --batch-mode clean install
cd benchmarks
mvn --batch-mode clean package
```

The first command installs the current checkout of `json-serializer` into the local Maven repository. The benchmark module therefore benchmarks this exact source tree, not an unrelated published artifact.

## Reproducible single-thread benchmark

Run on an otherwise idle machine with a fixed JDK, fixed heap and a stable power profile:

```bash
java -Xms2g -Xmx2g -jar target/benchmarks.jar \
  -t 1 -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc \
  -rf json -rff results-single-thread.json
```

JMH reports throughput and average time. The GC profiler reports normalized allocation (`gc.alloc.rate.norm`, bytes/op), allocation rate, GC count and GC time.

## Concurrency scaling

```bash
for threads in 1 2 4 8; do
  java -Xms2g -Xmx2g -jar target/benchmarks.jar \
    -t "$threads" -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc \
    -rf json -rff "results-${threads}t.json"
done
```

Each JMH worker has thread-scoped state. This measures scaling and allocation pressure under concurrent callers. Compare implementations at the same thread count and on the same host.

## CPU and memory interpretation

- Throughput and average-time scores measure work per operation; they are more portable than a single CPU-utilisation percentage.
- For hardware-counter profiling on Linux, use `perf stat` (cycles, instructions, task-clock, context switches) around an otherwise identical JMH run.
- `-prof gc` measures allocation pressure, not peak retained heap or process RSS. Use a separate, documented heap/RSS experiment if retained-memory claims are needed.
- Do not compare a percentage CPU reading from one machine with another. Record host CPU, OS, JDK, GC, heap, thread count and dependency versions with every result.

## What is measured

Two API families are benchmarked:

- **UTF-8 byte[]:** custom `Json.toBytes/parse(byte[])`, Jackson `writeValueAsBytes/readValue(byte[])`, and Gson with UTF-8 conversion included because Gson's standard API is String-based.
- **String:** each library's native String API.

The fixture is a nested seven-field User object with a three-element tags list and nested Address. Trial setup performs semantic round-trip checks before measurement. All benchmark methods return their output so JMH can consume it.

## Fairness and limitations

- The String API comparison is the native-API comparison. Gson's byte-array methods include UTF-8 conversion; that extra work is unavoidable when using Gson's normal public API.
- The custom library uses an explicit hand-written codec while Jackson and Gson use their normal object mapping. This compares practical library usage, not identical internal mapping strategies.
- This is one representative object shape, not a universal ranking. Add small, large, deeply nested, escaped-Unicode, null-heavy and unknown-field fixtures before making broad performance claims.
- JMH results depend on JVM, hardware, operating system, thermal state and background load. CI-hosted results are useful reproducibility checks, not a substitute for repeated runs on a named dedicated machine.
- Never present results from the old `json.Bench` or README microbench as JMH results.

## Automated CI run

The repository workflow builds the library, runs the JMH suite with GC profiling at 1, 2, 4 and 8 threads, records host/JDK metadata, and uploads raw JSON results as a workflow artifact. Only publish numerical claims after inspecting those raw results and repeating the run on a stable host.
