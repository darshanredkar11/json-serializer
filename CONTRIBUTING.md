# Contributing

Thanks for considering a contribution.

## Development

Requirements:
- JDK 17 or newer
- Maven 3.9+ for the Maven build, or a POSIX shell for `build.sh`

Run the full test suite:

```sh
./build.sh
mvn clean verify
```

Run the benchmark:

```sh
./build.sh bench
```

## Pull requests

Please keep changes focused and include regression coverage for parser, writer, codec, or processor behavior that changes.

For performance changes, include the benchmark scenario and environment used to compare the before/after result.

## Scope

The project intentionally avoids runtime reflection and runtime dependencies. New features should preserve that design unless there is a compelling reason to change it.
