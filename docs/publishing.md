# Publishing

This document is for maintainers who need to package the Android library for Maven / AAR publishing.

## Artifact Coordinates

```text
io.agora.agents:agora-agent-client-toolkit:<version>
```

The release version comes from `lib/version.properties`:

```properties
CONVOAI_API_VERSION=2.9.0
```

You can override the version for a local packaging run with:

```bash
./gradlew :conversational-ai:packageMavenReleaseZip -PCONVOAI_API_VERSION=<version>
```

## Release Strategy

Do not use the final release version as the first validation artifact. Treat a
published Maven release as immutable.

Recommended flow:

1. Package and publish a release candidate first, for example `2.9.0-rc.1`.
2. Validate the RC through the publishing platform's staging / validation flow.
3. Consume the RC from the sample app or a clean test app and verify core APIs.
4. If fixes are needed, publish the next RC, for example `2.9.0-rc.2`.
5. Publish the final version, for example `2.9.0`, only after the RC passes.

If a problem is found before the deployment is formally published, drop the
staging deployment and rebuild. If a problem is found after the final version is
published, do not overwrite or delete that version; publish a new version such
as `2.9.1`.

Version guidance:

- RC validation: `2.9.0-rc.1`, `2.9.0-rc.2`
- Bug fix after final release: `2.9.0` -> `2.9.1`
- Backward-compatible feature: `2.9.0` -> `2.10.0`
- Breaking API change: `2.9.0` -> `3.0.0`

## Package the Maven Release Zip

Run:

```bash
./gradlew :conversational-ai:packageMavenReleaseZip
```

The generated zip is:

```text
conversational-ai/build/distributions/agora-agent-client-toolkit-<version>-maven-release.zip
```

The zip contains:

```text
agora-agent-client-toolkit/
├── agora-agent-client-toolkit-<version>.pom
├── agora-agent-client-toolkit-<version>.aar
├── agora-agent-client-toolkit-<version>-sources.jar
└── agora-agent-client-toolkit-<version>-javadoc.jar
```

## Required POM Metadata

The generated POM must include:

- `groupId`: `io.agora.agents`
- `artifactId`: `agora-agent-client-toolkit`
- `version`: release version
- `name`
- `description`
- `url`
- `licenses`
- `developers`
- `scm`

Before publishing, inspect the packaged POM:

```bash
unzip -p conversational-ai/build/distributions/agora-agent-client-toolkit-<version>-maven-release.zip \
  agora-agent-client-toolkit/agora-agent-client-toolkit-<version>.pom
```

## Pre-Publish Checklist

1. `CONVOAI_API_VERSION` is a non-SNAPSHOT version.
2. Before publishing the final version, a release candidate has passed sample / clean-app validation.
3. Unit tests pass:

   ```bash
   ./gradlew :app:testDebugUnitTest :conversational-ai:testDebugUnitTest
   ```

4. The package task succeeds:

   ```bash
   ./gradlew :conversational-ai:packageMavenReleaseZip
   ```

5. The packaged POM includes `url` and `scm`.
6. The zip includes `.pom`, `.aar`, `-sources.jar`, and `-javadoc.jar`.
7. `git status --short` has no missing release files, untracked resources needed by layouts, or stale staged files.
8. Public README files do not include internal publishing URLs or platform-specific release instructions.
