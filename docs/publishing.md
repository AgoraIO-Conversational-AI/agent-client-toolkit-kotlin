# Releasing

This is the public maintainer checklist for preparing and verifying Android
SDK releases. Publishing credentials and service-specific operations belong
in private maintainer documentation.

## Release Contract

- Source development and release PRs target `main`.
- Source release tags use `vX.Y.Z`.
- The Maven coordinate is `io.agora.agents:agora-agent-client-toolkit:X.Y.Z`.
- [CI](../.github/workflows/ci.yml) runs for PRs targeting `main`, pushes to
  `main`, manual dispatch, and tags matched by `*` (names without `/`, including
  `vX.Y.Z`). These runs test and build sources; they do not publish Maven
  packages or create GitHub Releases.
- [Docker checks](../.github/workflows/docker.yml) run on PRs only.

Replace `X.Y.Z` in every example with the same unused stable SemVer version.
Never move or reuse a release tag, or overwrite a published Maven version.
If a published release needs a fix, prepare a new version.

## Prepare the Release PR

1. Check the repository's tags and Maven Central for the proposed version.
   If the version already exists, choose another one. An HTTP 404 from the
   exact artifact URL means it is absent; network errors are not evidence
   that the version is available.

   ```bash
   git ls-remote --tags https://github.com/AgoraIO-Conversational-AI/agent-client-toolkit-kotlin.git refs/tags/vX.Y.Z
   curl --fail --show-error --location https://repo.maven.apache.org/maven2/io/agora/agents/agora-agent-client-toolkit/X.Y.Z/agora-agent-client-toolkit-X.Y.Z.pom
   ```

2. Align the build version with the SDK diagnostic version:

   | Location | Required value |
   |----------|----------------|
   | `conversational-ai/build.gradle` | Effective publication `version` must be `X.Y.Z` |
   | `conversational-ai/src/main/java/io/agora/conversational/api/IConversationalAIAPI.kt` | `ConversationalAIAPI_VERSION` must be `X.Y.Z` |

   The publication version resolves from Gradle `-PVERSION`, then the
   `VERSION` environment variable, then the build file's fallback. Always
   pass `-PVERSION=X.Y.Z` for release builds. A tag does not set either version
   automatically, and overriding Gradle's version does not update the SDK
   constant. The demo's `versionName` / `versionCode` are independent of the
   SDK version.

3. Add a dated entry to [CHANGELOG.md](../CHANGELOG.md) and update affected
   examples in the root README, `conversational-ai/README.md`, and the
   published-dependency example in `app/build.gradle.kts`. Review public API,
   default behavior, callback timing, package identity, and minimum platform
   changes for compatibility. Use patch versions for compatible fixes, minor
   versions for compatible additions, and major versions for breaking
   changes. Document behavior changes, the compatibility rationale, and any
   required migration or opt-in settings in the release notes.

4. Open the release PR against `main`. All backend, Android, and Docker PR
   checks must pass. To run the backend and Android checks locally, use
   Python 3.10+, JDK 21, and Android SDK 36:

   ```bash
   python3 -m venv server/.venv
   server/.venv/bin/python -m pip install -r server/requirements.txt -r server/requirements-dev.txt
   server/.venv/bin/python -m pytest server/tests -q
   ./gradlew :conversational-ai:testDebugUnitTest :app:testDebugUnitTest
   ./gradlew :conversational-ai:lintDebug :app:lintDebug :app:assembleDebug
   ```

   Validate voice startup, transcripts, agent state, messaging, interrupt,
   mute, manual SOS/EOS, and cleanup on a physical Android phone.

## Tag the Validated Source

After the release PR merges, wait for successful `main` CI on that exact
commit. Use a clean checkout. These examples assume `upstream` points to
`AgoraIO-Conversational-AI/agent-client-toolkit-kotlin` and that you have tag
push permission.

```bash
git fetch upstream main
git switch --detach upstream/main
git status --short
git rev-parse HEAD
```

Confirm that the checkout has no changes and the printed SHA is the commit
whose CI passed. Then create and push the source tag:

```bash
git tag vX.Y.Z
git push upstream refs/tags/vX.Y.Z
```

Wait for that tag's CI to pass. Build release artifacts from the tagged commit
in a clean checkout, passing the same explicit version. Publication is a
separate maintainer operation; a successful tag run alone does not mean that
the Maven package is available.

## Build and Inspect the Maven Artifacts

From the validated source checkout, the standard Gradle publication tasks
produce the AAR, POM, sources, and documentation archives without uploading:

```bash
./gradlew :conversational-ai:assembleRelease \
  :conversational-ai:generatePomFileForReleasePublication \
  :conversational-ai:androidSourcesJar \
  :conversational-ai:androidJavadocsJar -PVERSION=X.Y.Z
```

Inspect `conversational-ai/build/outputs/aar/conversational-ai-release.aar`,
`conversational-ai/build/publications/release/pom-default.xml`, and the
`-sources.jar` / `-javadoc.jar` files under `conversational-ai/build/libs/`.
The documentation archive currently contains the component README.

The POM must use group `io.agora.agents`, artifact
`agora-agent-client-toolkit`, and version `X.Y.Z`, with the name, description,
project URL, license, developer, SCM, and runtime dependency metadata intact.
The host app supplies Agora RTC and RTM, which are `compileOnly` dependencies
of this library. Validate the final artifacts in a sample or clean consumer
app before publication using the maintainer release process.

## Verify the Published Release

1. Confirm the exact version's POM and AAR can be downloaded from Maven
   Central. Confirm any GitHub Release notes identify the source tag and
   accurately report the package's publication status.

2. In a clean Android app, use `google()` and `mavenCentral()` and pin the
   toolkit version:

   ```kotlin
   implementation("io.agora.agents:agora-agent-client-toolkit:X.Y.Z")
   implementation("io.agora.rtc:full-sdk:4.5.1")
   implementation("io.agora:agora-rtm-lite:2.2.3")
   ```

   Use the published dependency instead of a project dependency or
   `mavenLocal()` substitute. Resolve and build it:

   ```bash
   ./gradlew :app:dependencyInsight --dependency agora-agent-client-toolkit --configuration debugRuntimeClasspath
   ./gradlew :app:assembleDebug
   ```

3. Check that dependency resolution selects `X.Y.Z` and SDK diagnostics report
   the same version. Repeat the physical-device smoke checks with the
   published artifact. Record the source SHA, tag, Maven coordinate, and
   validation result in the release record.
