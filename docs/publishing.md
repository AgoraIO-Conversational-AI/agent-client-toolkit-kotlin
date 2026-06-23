# Publishing

This document is for maintainers who need to package the Android library for Maven / AAR publishing.

## Artifact Coordinates

```text
io.agora.agents:agora-agent-client-toolkit:<version>
```

The release version comes from `lib/version.properties`:

```properties
CONVOAI_API_VERSION=1.0.0
```

You can override the version for a local packaging run with:

```bash
./gradlew :conversational-ai:packageMavenReleaseZip -PCONVOAI_API_VERSION=<version>
```

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

1. `CONVOAI_API_VERSION` is a non-SNAPSHOT release version.
2. The package task succeeds.
3. The packaged POM includes `url` and `scm`.
4. The zip includes `.pom`, `.aar`, `-sources.jar`, and `-javadoc.jar`.
5. Public README files do not include internal publishing URLs or platform-specific release instructions.
