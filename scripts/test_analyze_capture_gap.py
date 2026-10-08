import unittest
from analyze_capture_gap import analyze


def fixture(extra=""):
    return ("CAPTURE_GAP phase=start nowMs=100 untilMs=5100\n"
            "SPATIAL_CACHE_HOLD id=50\n" + extra +
            "CAPTURE_GAP phase=end nowMs=5120 untilMs=5100\n"
            "CAPTURE_SPAN id=5120 stage=dispatch uptimeMs=5121 requestAgeMs=1\n"
            "CAPTURE_SPAN id=5120 stage=callback-success uptimeMs=5160 requestAgeMs=40\n" +
            "\n".join(f"GAP_GESTURE_START id={i}\nGAP_GESTURE_END id={i}" for i in range(10)))


class CaptureGapParserTest(unittest.TestCase):
    def test_complete_pause_and_recovery(self):
        result = analyze(fixture())
        self.assertTrue(result["complete"])
        self.assertEqual(5020, result["observedGapMs"])
        self.assertEqual(1, result["holdRecordsBetweenGapMarkers"])

    def test_dispatch_during_pause_and_changed_schema_fail(self):
        self.assertFalse(analyze(fixture("CAPTURE_SPAN id=200 stage=dispatch uptimeMs=201 requestAgeMs=1\n"))["complete"])
        self.assertFalse(analyze(fixture().replace("nowMs=100 untilMs=5100", "nowMs=100 untilMs=5100 extra=1"))["complete"])
        self.assertFalse(analyze(fixture().replace("GAP_GESTURE_END id=9", ""))["complete"])

    def test_missing_completion_and_recovery_are_not_zero_work(self):
        self.assertFalse(analyze(fixture().replace("CAPTURE_GAP phase=end nowMs=5120 untilMs=5100", ""))["complete"])
        self.assertFalse(analyze(fixture().replace("stage=callback-success", "stage=callback-failure-1"))["complete"])
        self.assertFalse(analyze(fixture().replace(
            "CAPTURE_SPAN id=5120 stage=dispatch uptimeMs=5121 requestAgeMs=1\n", ""))["complete"])


if __name__ == "__main__":
    unittest.main()
