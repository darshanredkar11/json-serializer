# Writer hotspot isolation

A separate JFR run disables inlining for `JsonWriter.value` and `JsonWriter.rawString` so the CPU sample stacks can attribute time to the actual writer routines. This is diagnostic-only and does not alter production code.
