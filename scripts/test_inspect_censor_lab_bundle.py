import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from inspect_censor_lab_bundle import extract_bundle, inspect_bundle


class InspectCensorLabBundleTest(unittest.TestCase):
    def test_validates_and_renders_analyzer_compatible_trace(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / "lab.zip"
            manifest = {
                "schemaVersion": 1,
                "sessionId": "0123456789",
                "startedElapsedNanos": 1_000_000_000,
                "stoppedElapsedNanos": 1_500_000_000,
                "eventCount": 2,
                "droppedEvents": 0,
            }
            events = [
                {
                    "sequence": 1,
                    "elapsedNanos": 1_000_000_000,
                    "wallMillis": 1_788_100_000_000,
                    "thread": "main",
                    "tag": "CensorLab",
                    "message": "SYNC_START session=0123456789",
                },
                {
                    "sequence": 2,
                    "elapsedNanos": 1_250_000_000,
                    "wallMillis": 1_788_100_000_250,
                    "thread": "main",
                    "tag": "CensorMotion",
                    "message": "DRAW seq=1 inputToDrawMs=8 viewportLead=0,0",
                },
            ]
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("manifest.json", json.dumps(manifest))
                archive.writestr("trace.ndjson", "\n".join(map(json.dumps, events)) + "\n")
                archive.writestr("README.txt", "test")

            parsed_manifest, parsed_events, names = inspect_bundle(bundle)
            output = root / "out"
            extract_bundle(bundle, output, parsed_manifest, parsed_events, names)

            trace = (output / "trace.log").read_text(encoding="utf-8")
            self.assertIn("CensorLab: SYNC_START", trace)
            self.assertIn("CensorMotion: DRAW seq=1", trace)
            markers = json.loads((output / "sync-markers.json").read_text(encoding="utf-8"))
            self.assertEqual(0.0, markers[0]["relativeMs"])

    def test_rejects_unknown_archive_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary) / "bad.zip"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("../escape", "bad")
            with self.assertRaises(ValueError):
                inspect_bundle(bundle)

    def test_rejects_oversized_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary) / "oversized.zip"
            with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_STORED) as archive:
                archive.writestr("manifest.json", b"x" * (1024 * 1024 + 1))
                archive.writestr("trace.ndjson", "")
                archive.writestr("README.txt", "test")
            with self.assertRaises(ValueError):
                inspect_bundle(bundle)

    def test_requires_in_app_video_flags_to_match_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary) / "mismatch.zip"
            manifest = {
                "schemaVersion": 1,
                "sessionId": "0123456789",
                "startedElapsedNanos": 1,
                "stoppedElapsedNanos": 2,
                "eventCount": 0,
                "videoAttached": True,
                "recording": {
                    "inAppScreenRecording": True,
                    "videoKind": "mediaprojection-display",
                },
                "privacy": {"pixelCapture": True},
            }
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("manifest.json", json.dumps(manifest))
                archive.writestr("trace.ndjson", "")
                archive.writestr("README.txt", "test")
            with self.assertRaises(ValueError):
                inspect_bundle(bundle)


if __name__ == "__main__":
    unittest.main()
