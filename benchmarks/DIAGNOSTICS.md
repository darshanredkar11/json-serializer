# Serializer bottleneck diagnostics

This branch adds benchmark-only experiments. No production serializer code is modified.

- `CollectionScalingBenchmark` measures encode/decode throughput and allocation across 0, 3, 16, 64, 256 and 1,024 tags.
- `.github/workflows/diagnostics.yml` runs the sweep at one and four threads and captures Java Flight Recorder profiles for each library and operation.
- Results are uploaded as the `json-serializer-bottleneck-diagnostics` Actions artifact.
