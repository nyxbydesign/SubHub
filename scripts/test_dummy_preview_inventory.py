"""The dummy preview has its own bounded native suite without changing older suites."""
import os
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

    def test_preview_and_real_editor_methods_are_explicit_and_present(self):
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "", "GITHUB_EVENT_PATH": ""}):
            selected = native.selectors("", "dummy-preview")
            self.assertEqual(224, len(selected))
            self.assertEqual(224, len(set(selected)))
            inventory = native.source_inventory("dummy-preview")
            self.assertEqual(27, len(inventory))
            self.assertEqual(17, len(inventory["com.subhub.app.service.AccessibilityServiceConnectionTest"]["selectedMethods"]))
            self.assertEqual(3, len(inventory["com.subhub.app.capture.ScreenCaptureTerminalPoolAndroidTest"]["selectedMethods"]))
            self.assertEqual(4, len(inventory["com.subhub.app.overlay.ManagedDeviceRendererAndroidTest"]["selectedMethods"]))
            self.assertEqual(3, len(inventory["com.subhub.app.overlay.LegacyRenderDependenciesAndroidTest"]["selectedMethods"]))
            self.assertEqual(7, len(inventory["com.subhub.app.runtime.DeviceStartResultRegistryAndroidTest"]["selectedMethods"]))
            self.assertEqual(15, len(inventory["com.subhub.app.service.RuntimeCaptureHostAndroidTest"]["selectedMethods"]))
            self.assertEqual(24, len(inventory["com.subhub.app.people.DummySubPreviewAndroidTest"]["selectedMethods"]))
            preview = inventory["com.subhub.app.people.DummySubPreviewAndroidTest"]["selectedMethods"]
            for section in ("censor", "limits", "wallet", "atmosphere", "apps", "duration"):
                self.assertIn(section + "EditorRecreatesAndRejectsForgedTargets", preview)
            self.assertIn("profileAuthorityRejectsForgeryAndLiveEditorRevocation", preview)
            self.assertNotIn("nativeEditorsRecreateWithoutAutosaveAndRejectStaleOrForgedTargets", preview)
            self.assertEqual(6, len(inventory["com.subhub.app.remote.RemoteEditorAndroidTest"]["selectedMethods"]))
            self.assertEqual(3, len(inventory["com.subhub.app.people.PersonRefreshAndroidTest"]["selectedMethods"]))
            self.assertEqual([
                "constructorFailureRestoresRealAdaptersAndDeletesOwnedStore",
                "failedCloseWitnessStillRestoresRealAdaptersAndDeletesOwnedStore",
            ], inventory["com.subhub.app.remote.DummyPreviewEndpointFixtureAndroidTest"]["selectedMethods"])
            self.assertEqual({"com.subhub.app.remote.RemoteFeatureSchedulingAndroidTest#recordedUsageAndStatsWakeButRejectedUsageAndTelemetryReadsDoNot"},
                    set(selected) & set(native.DEVICE_PASS_SELECTORS))
            self.assertEqual(38, len(inventory["com.subhub.app.internal.OwnerAccountingAndroidTest"]["selectedMethods"]))
            self.assertNotIn("com.subhub.app.censor.android.InternalCensorAndroidTest", inventory)
            self.assertEqual(21, len(native.suite_selectors("extra-time")))
            self.assertEqual(57, len(native.suite_selectors("device-pass")))

if __name__ == "__main__":
    unittest.main()
