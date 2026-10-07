# Changelog

All notable changes to this project are documented here.

## [1.0.0] - 2026-10-07

### Added
- Dependency-free JSON serialization and parsing for Java 17+.
- Pull-based UTF-8 JSON reader and allocation-conscious JSON writer.
- Hand-written `JsonCodec<T>` API.
- `Codecs` combinators for nullable values, lists, maps, enums, and primitives.
- Compile-time `@JsonRecord` codec generation with `@JsonName`.
- Hardened validation for malformed numbers, UTF-8, delimiters, nested values, and trailing document data.
- Maven Central publication metadata and source/Javadoc artifacts.
