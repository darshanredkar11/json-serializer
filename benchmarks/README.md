# JSON serializer: JMH comparison

This module compares this project's hand-written/generated-codec serializer with Jackson Databind and Gson using the exact same `json.example.User` object graph.

## Build

Run from the repository root:

```bash
mvn -DskipTests install
cd benchmarks
mvn -DskipTests clean package
```

The first command installs the current checkout of `json-serializer` into the local Maven repository. The benchmark module then uses that exact artifact, rather than silently benchmarking an unrelated published build.

## Reproducible single-thread benchmark

```bash
java -Xms2g -Xmx2g -jar target/benchmarks.jar \
  -t 1 -f 3 -wi 5 -i 5 -w 1s -r 1s \
  -rf json -rff results-single-thread.json
```

## Allocation / GC profile

```bash
java -Xms2g -Xmx2g -jar target/benchmarks.jar \
  -t 1 -f 3 -wi 5 -i 5 -prof gc \
  -rf json -rff results-gc.json
```

Record `gc.alloc.rate.norm` (bytes/op), `gc.alloc.rate`, GC count and GC time. Normalized bytes/op is the best direct comparison of allocation pressure. It is not the same as peak live heap.

## Concurrency scaling

```bash
for threads in 1 2 4 8; do
  java -Xms2g -Xmx2g -jar target/benchmarks.jar \
    -t $threads -f 3 -wi 5 -i 5 -prof gc \
    -rf json -rff results-${threads}t.json
done
```

Each JMH worker has thread-scoped state. This tests throughput scaling and allocation pressure under concurrent callers. Use the same physical machine, JDK, heap, power profile and background-load conditions for every run.

## CPU profiling

JMH throughput and average-time scores quantify CPU work indirectly. For sampled CPU hotspots, use async-profiler with the JMH async profiler integration, or on Linux run the benchmark under `perf stat` (cycles, instructions, task-clock, context-switches). Do not report a single CPU-utilisation percentage as an intrinsic serializer property: it depends on thread count, available cores and workload.

## What is measured

Two API families are benchmarked:

- **UTF-8 byte[]:** custom `Json.toBytes/parse(byte[])`, Jackson `writeValueAsBytes/readValue(byte[])`, Gson with UTF-8 conversion included because Gson's normal API is String-based.
- **String:** each library's native String API.

The fixture is a nested 7-field User object with a three-element tags list and nested Address. Setup performs semantic round-trip checks before timing.

## Interpretation cautions

- JMH's `-prof gc` reports allocation rate and normalized allocation per operation; it does not by itself establish peak retained heap.
- The byte[] Gson result includes String creation and UTF-8 conversion. The String benchmark is the fair native-API comparison.
- This is one representative object shape, not a universal ranking. Add small, large, deeply nested, escaped-Unicode, null-heavy and unknown-field fixtures before making broad performance claims.
- Keep JDK version, GC, heap, CPU model, OS and dependency versions with published results.
- Do not compare these results with the old `json.Bench` or README microbench numbers; those were not JMH runs.
