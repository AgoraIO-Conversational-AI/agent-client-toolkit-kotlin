#!/usr/bin/env python3
"""Build a versioned Maven input archive without publishing it."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parent.parent


def release_version(tag):
    match = re.fullmatch(r"v((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))", tag)
    if not match:
        raise ValueError("Release tags must be stable versions such as v2.10.1")
    return match[1]


def validate_versions(root, version):
    checks = {
        "conversational-ai/src/main/java/io/agora/conversational/api/IConversationalAIAPI.kt":
            r'const val ConversationalAIAPI_VERSION = "([^"]+)"',
        "conversational-ai/build.gradle": r'^version = .*\?: "([^"]+)"$',
    }
    for name, pattern in checks.items():
        match = re.search(pattern, (root / name).read_text(), re.MULTILINE)
        if not match or match[1] != version:
            raise ValueError(f"{name}: expected version {version}, found {match[1] if match else 'no version'}")


def validate_archive(archive, version):
    stem = f"agora-agent-client-toolkit-{version}"
    prefix = "agora-agent-client-toolkit/"
    expected = {prefix + stem + suffix for suffix in [".aar", ".pom", "-sources.jar", "-javadoc.jar"]}
    with zipfile.ZipFile(archive) as package:
        if package.testzip() is not None or {n for n in package.namelist() if not n.endswith("/")} != expected:
            raise ValueError("Maven input archive must contain the AAR, POM, sources and javadoc")
        pom = ET.fromstring(package.read(prefix + stem + ".pom"))
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        for key, value in {"groupId": "io.agora.agents", "artifactId": "agora-agent-client-toolkit", "version": version}.items():
            if pom.findtext("m:" + key, namespaces=ns) != value:
                raise ValueError(f"Unexpected POM {key}; expected {value}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag", help="Source release tag, e.g. v2.10.1")
    parser.add_argument("--check", action="store_true", help="Validate the tag and source versions only")
    args = parser.parse_args()
    version = release_version(args.tag)
    validate_versions(ROOT, version)
    if args.check:
        print(version)
        return

    env = dict(os.environ, VERSION=version)
    subprocess.run(["bash", "scripts/build_rehoboam_maven_input_zip.sh"], cwd=ROOT, env=env, check=True)
    name = f"agora-agent-client-toolkit-{version}-maven-rehoboam-input.zip"
    archive = ROOT / "conversational-ai/build/distributions" / name
    validate_archive(archive, version)
    output = ROOT / "build/release" / version
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(archive, output / name)
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    manifest = {
        "version": version,
        "source_tag": args.tag,
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).strip(),
        "archives": {name: checksum},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output / "SHA256SUMS").write_text(f"{checksum}  {name}\n")
    print(f"Release artifacts: {output}")


if __name__ == "__main__":
    main()
