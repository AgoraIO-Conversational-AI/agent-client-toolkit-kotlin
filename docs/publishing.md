# Publishing

This document is for maintainers who need to prepare the Android Maven / AAR upload package for Rehoboam.

## Artifact Coordinates

```text
io.agora.agents:agora-agent-client-toolkit:<version>
```

For Rehoboam packaging, pass the version explicitly:

```bash
VERSION=<version> scripts/build_rehoboam_maven_input_zip.sh
```

## Release Strategy

Treat a published Maven release as immutable. Do not publish release candidates.

Recommended flow:

1. Build the final package, for example `2.9.0`.
2. Validate it through the publishing platform's staging / validation flow.
3. Consume it from the sample app or a clean test app and verify core APIs.
4. Publish only after validation passes.

If a problem is found before the deployment is formally published, drop the
staging deployment and rebuild. If a problem is found after the final version is
published, do not overwrite or delete that version; publish a new version such
as `2.9.1`.

## SemVer and Changelog Gate

Every release must update `CHANGELOG.md`.

- Patch releases are for compatible fixes, parser hardening, documentation corrections, and packaging metadata fixes.
- Minor releases may add optional APIs, optional event fields, new callbacks with default no-op behavior, or new supported protocol events.
- Major releases are required for source or binary incompatible public API changes, changed defaults, changed callback timing, changed package identity, or higher minimum platform baselines that exclude existing consumers.

Only formal SemVer versions are allowed.

## Package the Rehoboam Maven Input Zip

Run:

```bash
VERSION=2.9.0 scripts/build_rehoboam_maven_input_zip.sh
```

The generated zip is:

```text
conversational-ai/build/distributions/agora-agent-client-toolkit-<version>-maven-rehoboam-input.zip
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
unzip -p conversational-ai/build/distributions/agora-agent-client-toolkit-<version>-maven-rehoboam-input.zip \
  agora-agent-client-toolkit/agora-agent-client-toolkit-<version>.pom
```

## Pre-Publish Checklist

1. `CHANGELOG.md` has a release entry for the version being packaged. The first public release must establish the compatibility baseline.
2. Public API changes in `conversational-ai/src/main/java/io/agora/conversational/api/IConversationalAIAPI.kt` have been reviewed for SemVer impact.
3. Public README files are aligned with the API surface and do not include internal publishing URLs or platform-specific release instructions.
4. The release version is a formal SemVer version, for example `2.9.0`.
5. Before publishing, sample / clean-app validation has passed.
6. Unit tests pass:

   ```bash
   ./gradlew :app:testDebugUnitTest :conversational-ai:testDebugUnitTest
   ```

7. The package script succeeds:

   ```bash
   VERSION=2.9.0 scripts/build_rehoboam_maven_input_zip.sh
   ```

8. The packaged POM includes `url` and `scm`.
9. The zip includes `.pom`, `.aar`, `-sources.jar`, and `-javadoc.jar`.
10. `git status --short` has no missing release files, untracked resources needed by layouts, or stale staged files.
