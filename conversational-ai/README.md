# Conversational AI Android Library

This module contains the standalone Android library prepared for Maven Central release.

Module directory:

- `conversational-ai/`

Exported packages:

- `io.agora.conversational.api.*`
- `io.agora.conversational.api.transcript.*`

Not included in this library:

- demo-only stream-message subtitle implementation previously used by `subRender/v1`

## Published Coordinates

Release coordinates:

```groovy
implementation 'io.github.agoraio-conversational-ai:agora-agent-client-toolkit:<version>'
```

## Dependency Model

This library keeps Agora RTC and RTM as `compileOnly` dependencies on purpose.

Why:

- the host app is expected to already integrate Agora RTC/RTM
- the host app owns RTC initialization, RTM login state, and lifecycle management
- the library consumes existing `RtcEngine` and `RtmClient` instances through `ConversationalAIAPIConfig`
- the generated AAR does not bundle RTC/RTM transitively

Because of that, consumers must add RTC and RTM dependencies themselves:

```groovy
dependencies {
    implementation 'io.agora.rtc:full-sdk:4.5.1'
    implementation 'io.agora:agora-rtm:2.2.3'
}
```

Before creating `ConversationalAIAPIImpl`, make sure:

- RTC is initialized and available
- RTM is logged in
- those RTC/RTM instances outlive the lifecycle of this library component

## Maven Central Prerequisites

Before the first real release, prepare:

1. A verified Sonatype Central Portal namespace: `io.github.agoraio-conversational-ai`
2. A Central Portal publishing token
3. A local GPG keyring available to `gpg` / `gpg-agent`
4. A non-`SNAPSHOT` release version

Recommended local Gradle configuration:

`~/.gradle/gradle.properties`

```properties
centralUsername=...
centralPassword=...
centralNamespace=io.github.agoraio-conversational-ai
# Optional, only use when multiple open repositories need manual disambiguation:
# centralRepositoryKey=...
signing.gnupg.keyName=...
# Optional, defaults are usually enough:
# signing.gnupg.executable=gpg
# signing.gnupg.useLegacyGpg=false
# signing.gnupg.homeDir=/Users/your-name/.gnupg
# signing.gnupg.passphrase=...
```

Notes:

- `useGpgCmd()` uses your local `gpg` / `gpg-agent`, so you no longer need `signingKey`, `signingKeyId`, or `signingPassword` in project properties
- CI runners should preload the GPG keyring; do not store the private key itself in CI secrets
- if `signing.gnupg.passphrase` is omitted, Gradle will ask `gpg-agent` for the passphrase
- `centralNamespace` defaults to `io.github.agoraio-conversational-ai`; only override it if your Central namespace changes
- `publishToMavenCentral` now searches Central for the unique `open` repository under your namespace, then calls `upload/repository/<key>` automatically
- if you ever have multiple `open` repositories, set `centralRepositoryKey` temporarily to the exact repository key returned by Sonatype
- do not commit tokens or private keys into the repository
- the default version is defined in `lib/version.properties` with the key `CONVOAI_API_VERSION`
- `CONVOAI_API_VERSION` can be overridden with `-PCONVOAI_API_VERSION=<version>` or the `CONVOAI_API_VERSION` environment variable

## Central Release Flow

1. Update `lib/version.properties` or pass `-PCONVOAI_API_VERSION=<release>`.
2. Verify the local publication first:

```bash
gpg --list-secret-keys --keyid-format LONG
./gradlew :conversational-ai:publishReleasePublicationToMavenLocal
```

3. Run the single release command:

```bash
./gradlew :conversational-ai:publishToMavenCentral
```

This task will:

- upload `release` artifacts to Sonatype Central's OSSRH Staging API compatibility endpoint
- search `manual/search/repositories?ip=any&profile_id=<namespace>` automatically after upload
- call `manual/upload/repository/<key>?publishing_type=automatic` for the unique `open` repository
- fail fast if Central credentials are missing

4. Check the deployment result in the Central Portal UI.

## What This Module Publishes

The Gradle configuration is prepared to upload:

- release `.aar`
- generated `-sources.jar`
- placeholder `-javadoc.jar` containing this module README, which satisfies Central validation
- signed publication files through local `gpg/gpg-agent`
