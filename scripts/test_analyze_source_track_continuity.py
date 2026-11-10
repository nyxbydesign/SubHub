import unittest
from analyze_source_track_continuity import analyze


class SourceTrackTest(unittest.TestCase):
    def test_v2_separates_global_local_and_cpu(self):
        result = analyze("SOURCE_TRACK v=2 id=1 enabled=true corrected=3 global=1 local=2 pairs=4 maxAbsExtraDy=170 cpuUs=950")
        self.assertTrue(result["complete"])
        self.assertTrue(result["classificationComplete"])
        self.assertEqual(1, result["globalTracks"])
        self.assertEqual(2, result["localTracks"])
        self.assertEqual(950, result["correctionCpuMedianUs"])

    def test_v2_rejects_inconsistent_counts_and_budget(self):
        row = "SOURCE_TRACK v=2 id=1 enabled=true corrected=1 global=0 local=1 pairs=2 maxAbsExtraDy=170 cpuUs=950"
        for invalid in (row.replace("corrected=1", "corrected=2"), row.replace("pairs=2", "pairs=17"),
                        row.replace("pairs=2", "pairs=0"), row.replace("enabled=true", "enabled=false"),
                        row.replace("cpuUs=950", "cpuUs=-1"), row + " extra=1"):
            self.assertFalse(analyze(invalid)["complete"])
        self.assertTrue(analyze("SOURCE_TRACK v=2 id=1 enabled=true corrected=0 global=0 local=0 pairs=17 maxAbsExtraDy=0 cpuUs=50")["complete"])

    def test_legacy_records_do_not_invent_local_or_cpu_measurements(self):
        result = analyze("SOURCE_TRACK v=1 id=1 enabled=true corrected=2 extraDy=-170")
        self.assertFalse(result["classificationComplete"])
        self.assertIsNone(result["localTracks"])
        self.assertIsNone(result["correctionCpuMedianUs"])

    def test_counts_updates_not_visible_frames(self):
        result = analyze("SOURCE_TRACK v=1 id=1 enabled=true corrected=2 extraDy=-170")
        self.assertTrue(result["complete"])
        self.assertEqual(2, result["correctedTracks"])
        self.assertEqual(1, result["correctedUpdates"])
        self.assertEqual(170, result["maxAbsExtraDy"])
        self.assertFalse(result["visualAcceptance"])

    def test_disabled_control_has_no_correction(self):
        self.assertTrue(analyze("SOURCE_TRACK v=1 id=1 enabled=false corrected=0 extraDy=0")["complete"])
        self.assertFalse(analyze("SOURCE_TRACK v=1 id=1 enabled=false corrected=1 extraDy=1")["complete"])

    def test_malformed_and_inconsistent_records_are_incomplete(self):
        for row in ("SOURCE_TRACK v=1 id=1 enabled=true corrected=1 extraDy=0",
                    "SOURCE_TRACK v=1 id=1 enabled=true corrected=0 extraDy=1",
                    "SOURCE_TRACK v=2 id=1 enabled=true corrected=1 extraDy=1",
                    "SOURCE_TRACK v=1 id=1 enabled=true corrected=1 extraDy=1 extra=1"):
            self.assertFalse(analyze(row)["complete"])


if __name__ == "__main__":
    unittest.main()
