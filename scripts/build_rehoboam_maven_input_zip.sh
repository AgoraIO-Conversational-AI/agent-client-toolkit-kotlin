#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${VERSION:-}"

if [[ -z "$VERSION" ]]; then
  echo "Unable to resolve version. Pass VERSION=2.9.0-rc.1." >&2
  exit 1
fi

if [[ ! "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
  echo "Invalid version: $VERSION" >&2
  exit 1
fi

if [[ "$VERSION" == *"-SNAPSHOT" ]]; then
  echo "Rehoboam Maven input zip requires a non-SNAPSHOT version: $VERSION" >&2
  exit 1
fi

cd "$ROOT_DIR"

./gradlew :conversational-ai:packageMavenReleaseZip -PVERSION="$VERSION"

ZIP_PATH="$ROOT_DIR/conversational-ai/build/distributions/agora-agent-client-toolkit-$VERSION-maven-rehoboam-input.zip"
if [[ ! -f "$ZIP_PATH" ]]; then
  echo "Missing generated Maven input zip: $ZIP_PATH" >&2
  exit 1
fi

/usr/bin/unzip -t "$ZIP_PATH" >/dev/null

for entry in \
  "agora-agent-client-toolkit/agora-agent-client-toolkit-$VERSION.pom" \
  "agora-agent-client-toolkit/agora-agent-client-toolkit-$VERSION.aar" \
  "agora-agent-client-toolkit/agora-agent-client-toolkit-$VERSION-sources.jar" \
  "agora-agent-client-toolkit/agora-agent-client-toolkit-$VERSION-javadoc.jar"
do
  if ! /usr/bin/unzip -l "$ZIP_PATH" "$entry" >/dev/null; then
    echo "Missing expected Maven zip entry: $entry" >&2
    exit 1
  fi
done

echo "Rehoboam Maven input zip: $ZIP_PATH"
