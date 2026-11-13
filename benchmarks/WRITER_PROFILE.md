# Writer hotspot isolation

A separate JFR run disables inlining for `JsonWriter.value` and `JsonWriter.rawString` so the CPU sample stacks can attribute time to the actual writer routines. This is diagnostic-only and does not alter production code.

The workflow compares default JIT behavior with no-inline writer methods on the same runner to isolate inlining effects.

Added unprofiled A/B/C throughput runs to isolate the effect of inlining value() and rawString().
