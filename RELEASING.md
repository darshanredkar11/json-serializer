# Releasing

## One-time Maven Central setup

1. Create/sign in to the Maven Central Portal.
2. Verify ownership of the `io.github.darshanredkar11` namespace through the GitHub-backed namespace verification flow.
3. Generate a Central Portal user token.
4. Configure Maven `settings.xml` with the token under server id `central`.
5. Configure a GPG key and ensure the public key is available to the public key infrastructure used for Central verification.

## Release checklist

1. Ensure the release branch is based on the hardened parser branch.
2. Update the version in `pom.xml`.
3. Update `CHANGELOG.md`.
4. Run `./build.sh`.
5. Run `mvn clean verify`.
6. Inspect `target/` and confirm the main, sources, and Javadoc JARs are present.
7. Run the signed release deployment:

```sh
mvn -Prelease clean deploy
```

8. Review the deployment in Maven Central Portal and publish it.
9. Create a Git tag matching the version, for example `json-serializer-1.0.0`.
10. Create the corresponding GitHub Release.

Published Maven Central versions are immutable; fix future issues with a new version rather than replacing an existing artifact.
