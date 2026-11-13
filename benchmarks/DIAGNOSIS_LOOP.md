# Active diagnosis loop

This is a diagnostic-only workstream. Production code is unchanged.

## Hypotheses under test

1. Encoder: HotSpot inlining of JsonWriter.value(String) changes generated codec hot-loop performance.
2. Decoder: readString() performs a second scan for escape/non-ASCII bytes on ordinary ASCII strings.

## Evidence required to close

- HotSpot PrintInlining output for default, all-value no-inline, and String-overload-only no-inline.
- Same-runner throughput and allocation measurements.
- A source-level encoder candidate and a single-pass decoder candidate, each independently correctness-tested and benchmarked.
- Repeat runs sufficient to reject noisy wins.

No merge, release, or publication without explicit approval.
