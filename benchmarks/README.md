# JSON serializer: performance and load benchmarks

This module compares this project's hand-written codec with Jackson Databind 2.x and Gson using the same Java object graph and semantically equivalent JSON.

## Versions

- JMH 1.37
- Jackson Databind 2.23.0
- Gson 2.15.0
- Project serializer: the exact source checkout installed by the build below

Jackson 2.x is used deliberately because this benchmark compares the widely deployed `com.fasterxml.jackson` API family. Jackson 3.x is a separate major version with different Maven coordinates and Java packages; it should be added as a separate benchmark target.

## Build

Run from the repository root:

```bash
mvn --batch-mode clean install
cd benchmarks
mvn --batch-mode clean package
```

The first command installs the current checkout of `json-serializer` into the local Maven repository. The benchmark module therefore benchmarks this exact source tree, not an unrelated published artifact.

## Extended JMH comparison

Run on an otherwise idle machine with a fixed JDK, fixed heap and stable power profile:

```bash
for threads in 1 2 4 8; do
  java -Xms2g -Xmx2g -jar target/benchmarks.jar \
    -t "$threads" -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc \
    -rf json -rff "results/jmh-${threads}t.json"
done
```

JMH reports throughput and average time. The GC profiler reports normalized allocation (`gc.alloc.rate.norm`, bytes/op), allocation rate, GC count and GC time. Each case uses five warmup and five measurement iterations, three forks.

## Sustained concurrent CPU and memory stress

The `json.bench.HeavyLoadBenchmark` harness runs a larger object graph (256 tags) under sustained concurrent encode-only and decode-only load. Each implementation/operation/thread-count combination runs in its own JVM to make process RSS and GC measurements attributable to that case.

```bash
for library in own jackson gson; do
  for operation in encode decode; do
    for threads in 1 2 4 8 16; do
      java -Xms256m -Xmx2g -cp target/benchmarks.jar \
        json.bench.HeavyLoadBenchmark \
        "$library" "$operation" "$threads" 8 20
    done
  done
done
```

Arguments are library, operation, worker threads, warmup seconds and measurement seconds. The program emits one JSON record per run with:

- **Throughput:** completed operations per second.
- **CPU time:** worker-thread CPU time, reported as core-equivalent CPU percentage and percentage normalized to the host's available processors. Core-equivalent CPU may exceed 100% when multiple cores are busy.
- **Allocation:** bytes allocated per operation and total worker-thread allocation, using HotSpot's per-thread allocated-byte counters.
- **Memory:** process peak resident set size (Linux `VmHWM`) and post-run used Java heap.
- **GC:** collection count and collection time during the measured run.

The harness validates all three implementations against the same fixture before timing. Encode and decode are separate tests so their allocation and CPU costs are not hidden inside a blended average.

## CPU and memory interpretation

- CPU time is more reproducible than instantaneous CPU percentage. The reported percentage is derived from thread CPU nanoseconds divided by elapsed wall time; it is not a sampled dashboard reading.
- Allocation bytes/op measures short-lived allocation pressure. Peak RSS measures process resident memory and includes JVM/native overhead; it is not the same as retained application heap.
- Heap used is a point-in-time post-run measurement. Do not interpret it as peak live heap.
- For hardware-counter profiling on Linux, use `perf stat` (cycles, instructions, task-clock and context switches) around an otherwise identical JMH run when host permissions allow it.
- Compare implementations at the same thread count, payload, JDK, heap, GC and host. Hosted CI results are reproducibility checks, not a substitute for repeated runs on a named dedicated machine.

## API coverage and fairness

- **UTF-8 byte[]:** custom `Json.toBytes/parse(byte[])`, Jackson `writeValueAsBytes/readValue(byte[])`, and Gson with UTF-8 conversion included because Gson's standard API is String-based.
- **String:** each library's native String API.
- The custom library uses an explicit hand-written codec while Jackson and Gson use their normal object mapping. This compares practical library usage, not identical internal mapping strategies.
- Trial setup validates round trips before timing. All benchmark methods return their output so JMH can consume it.

## Limitations and publication policy

- The JMH fixture and concurrent stress fixture are representative workloads, not a universal ranking. Add escaped-Unicode, null-heavy, unknown-field, deeply nested and varied object-shape cases before broad claims.
- CI virtual machines can be noisy or throttled. Treat hosted CPU/RSS measurements as directional; repeat on dedicated hardware before publishing headline claims.
- Never present results from the old `json.Bench` or README microbench as JMH results.
- Publish raw JSON/JSONL results, environment metadata and exact commands alongside any numerical claims.
