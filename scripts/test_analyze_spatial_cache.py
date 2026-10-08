import unittest
from analyze_spatial_cache import analyze


class SpatialCacheParserTest(unittest.TestCase):
    def test_complete_and_unknown_frames_are_counted(self):
        result = analyze("I Test: SPATIAL_CACHE_FRAME id=1 status=BASELINE known=true cpuUs=100\n"
                         "I Test: SPATIAL_CACHE_FRAME id=2 status=UNMATCHED known=false cpuUs=80\n"
                         "I Test: SPATIAL_CACHE_QUERY id=1 entries=1 inserted=1 candidates=1")
        self.assertTrue(result["complete"])
        self.assertEqual(3, result["rawRecords"])
        self.assertEqual(1, result["knownFrames"])
        self.assertEqual(1, result["queriesWithRegions"])

    def test_applied_coverage_hold_is_not_an_image_registration(self):
        result = analyze("SPATIAL_CACHE_HOLD id=12")
        self.assertTrue(result["complete"])
        self.assertEqual(1, result["holdEvents"])
        self.assertEqual(0, result["knownFrames"])
        self.assertFalse(analyze("SPATIAL_CACHE_HOLD id=-1")["complete"])

    def test_write_rejections_and_actual_admission_are_separate(self):
        result = analyze("SPATIAL_CACHE_WRITE id=1 known=true input=2 crop=1 unconfirmed=1 faces=1 faceCrop=1\n"
                         "SPATIAL_CACHE_APPLIED kind=scene input=2 admitted=0")
        self.assertTrue(result["complete"])
        self.assertEqual(1, result["cropRejectedFaces"])
        self.assertEqual(1, result["inputButNoAdmission"])
        self.assertFalse(analyze("SPATIAL_CACHE_APPLIED kind=scene input=0 admitted=1")["complete"])
        self.assertFalse(analyze("SPATIAL_CACHE_WRITE id=1 known=true input=1 crop=1 unconfirmed=1 faces=1 faceCrop=1")["complete"])

    def test_new_fields_invalid_state_and_unknown_record_are_incomplete(self):
        for record in ("SPATIAL_CACHE_FRAME id=1 status=UNMATCHED known=true cpuUs=1",
                       "SPATIAL_CACHE_FRAME id=1 status=BASELINE known=true cpuUs=1 added=2",
                       "SPATIAL_CACHE_OTHER id=1",
                       "SPATIAL_CACHE_QUERY id=1 entries=1 inserted=2 candidates=1"):
            self.assertFalse(analyze(record)["complete"])
        self.assertFalse(analyze("")["complete"])


if __name__ == "__main__":
    unittest.main()
