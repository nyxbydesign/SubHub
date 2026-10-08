import unittest
from analyze_world_cache_writes import analyze

BASE = "WORLD_CACHE_QUERY source=ACTIVE_FAST entries=1 inserted=0 updated=0 evicted=0 viewportReset=false candidates=1 camera=0,0 documentEpoch=1"


class CacheWriteTest(unittest.TestCase):
    def test_known_write_skip_is_distinct_from_an_empty_query(self):
        result = analyze(BASE + " cacheWriteAccepted=false sourceGeneration=9")
        self.assertTrue(result["complete"])
        self.assertEqual(1, result["skippedWrites"])
        self.assertEqual(0, result["admittedWrites"])

    def test_legacy_status_is_unknown_not_a_zero_write_count(self):
        result = analyze(BASE)
        self.assertTrue(result["complete"])
        self.assertFalse(result["writeStatusKnown"])
        self.assertIsNone(result["skippedWrites"])

    def test_skipped_mutations_and_unknown_fields_are_rejected(self):
        row = BASE + " cacheWriteAccepted=false sourceGeneration=9"
        for invalid in (row.replace("inserted=0", "inserted=1"), row + " extra=1",
                        row.replace("cacheWriteAccepted=false", "cacheWriteAccepted=unknown")):
            self.assertFalse(analyze(invalid)["complete"])


if __name__ == "__main__":
    unittest.main()
