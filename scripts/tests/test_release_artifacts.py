import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "build_release_artifacts.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseArtifactsTests(unittest.TestCase):
    def test_stable_source_tags(self):
        for version in ["0.0.1", "2.10.1", "12.30.400"]:
            self.assertEqual(version, release.release_version("v" + version))

    def test_non_release_refs_are_rejected(self):
        for tag in ["2.10.1", "main", "source/v2.10.1", "v2.10.1-rc.1", "v2.10.1+build", "v02.10.1", "v2.10.1/other"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.release_version(tag)

    def test_runtime_and_gradle_versions_must_match_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            api = root / "conversational-ai/src/main/java/io/agora/conversational/api/IConversationalAIAPI.kt"
            api.parent.mkdir(parents=True)
            api.write_text('const val ConversationalAIAPI_VERSION = "2.10.1"\n')
            gradle = root / "conversational-ai/build.gradle"
            gradle.write_text('version = findProperty("VERSION") ?: System.getenv("VERSION") ?: "2.10.1"\n')
            release.validate_versions(root, "2.10.1")
            for path in [api, gradle]:
                original = path.read_text()
                path.write_text(original.replace("2.10.1", "2.10.0"))
                with self.subTest(path=path), self.assertRaises(ValueError):
                    release.validate_versions(root, "2.10.1")
                path.write_text(original)

    def test_archive_requires_all_files_and_matching_pom(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "package.zip"
            stem = "agora-agent-client-toolkit/agora-agent-client-toolkit-2.10.1"
            pom = '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>io.agora.agents</groupId><artifactId>agora-agent-client-toolkit</artifactId><version>2.10.1</version></project>'
            for missing, wrong_version in [(False, False), (True, False), (False, True)]:
                with zipfile.ZipFile(archive, "w") as package:
                    package.writestr(stem + ".pom", pom.replace("2.10.1", "2.10.0") if wrong_version else pom)
                    for suffix in [".aar", "-sources.jar", "-javadoc.jar"]:
                        if not (missing and suffix == "-javadoc.jar"):
                            package.writestr(stem + suffix, b"archive fixture")
                if missing or wrong_version:
                    with self.assertRaises(ValueError):
                        release.validate_archive(archive, "2.10.1")
                else:
                    release.validate_archive(archive, "2.10.1")
