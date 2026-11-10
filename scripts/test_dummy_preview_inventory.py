"""The dummy preview has its own bounded native suite without changing older suites."""
import os
from pathlib import Path
import unittest
from unittest import mock
from scripts import run_extra_time_native as native


class DummyPreviewInventoryTest(unittest.TestCase):
    def test_shared_pr_subset_cannot_reduce_the_fixed_dummy_suite(self):
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "pull_request",
                "GITHUB_EVENT_PATH": "/not-read-by-the-fixed-dummy-suite",
                "EXTRA_TIME_SELECTORS": native.ALL_SELECTORS[-1]}):
            selected, source = native.selected_methods("dummy-preview")
            self.assertEqual(native.DUMMY_PREVIEW_SELECTORS, selected)
            self.assertEqual("suite_default", source)

    def test_receipts_identify_the_actual_suite_workflow(self):
        self.assertEqual(".github/workflows/dummy-preview-native.yml", native.native_workflow_path("dummy-preview"))
        for suite in ("extra-time", "subhub-pass", "reconnect", "battery", "device-pass"):
            self.assertEqual(".github/workflows/extra-time-native.yml", native.native_workflow_path(suite))
        with self.assertRaises(ValueError):
            native.native_workflow_path("unknown")

    def test_all_four_methods_are_explicit_and_present(self):
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "", "GITHUB_EVENT_PATH": ""}):
            selected = native.selectors("", "dummy-preview")
            self.assertEqual(4, len(selected))
            self.assertEqual(4, len(set(selected)))
            inventory = native.source_inventory("dummy-preview")
            self.assertEqual(1, len(inventory))
            self.assertEqual(4, len(next(iter(inventory.values()))["selectedMethods"]))
            self.assertTrue(set(selected).isdisjoint(native.DEVICE_PASS_SELECTORS))
            self.assertEqual(21, len(native.suite_selectors("extra-time")))
            self.assertEqual(54, len(native.suite_selectors("device-pass")))

    def test_workflow_runs_on_pr_head_without_labels_or_publication(self):
        source = (Path(__file__).parent.parent / ".github/workflows/dummy-preview-native.yml").read_text()
        self.assertIn("EXTRA_TIME_SUITE: dummy-preview", source)
        self.assertIn("ref: ${{ github.event.pull_request.head.sha }}", source)
        self.assertIn("contents: read", source)
        self.assertNotIn("labeled", source)
        self.assertNotIn("pull_request_target", source)
        self.assertNotIn("push:", source)
        self.assertNotIn("cancel-in-progress", source)
        self.assertEqual(1, source.count("run: python3 scripts/run_extra_time_native.py"))


if __name__ == "__main__":
    unittest.main()
