import unittest
from analyze_main_thread_samples import analyze


class MainThreadSamplesTest(unittest.TestCase):
    def test_valid_frames_and_raw_parity(self):
        result = analyze("I Probe: MAIN_SAMPLE uptimeMs=100 sampleMs=2 state=WAITING "
                         "stack=java.lang.Object#wait:-2;com.subhub.app.Service#run:42")
        self.assertTrue(result["completeParsing"])
        self.assertEqual(result["raw"], 1)
        self.assertEqual(result["parsed"], 1)
        self.assertEqual(result["sampleCostMaxMs"], 2)
        self.assertEqual(result["firstAppFrames"], {"com.subhub.app.Service#run:42": 1})

    def test_empty_and_new_schema_are_not_zero_work(self):
        self.assertFalse(analyze("")["completeParsing"])
        result = analyze("MAIN_SAMPLE uptimeMs=100 newField=1\n"
                         "MAIN_SAMPLE uptimeMs=100 sampleMs=0 state=RUNNABLE stack=bad")
        self.assertEqual(result["raw"], 2)
        self.assertEqual(result["malformed"], 2)
        self.assertFalse(result["completeParsing"])

    def test_android_synthetic_method(self):
        result = analyze("MAIN_SAMPLE uptimeMs=1 sampleMs=0 state=RUNNABLE "
                         "stack=android.Service#-$$Nest$mdispatch:0")
        self.assertTrue(result["completeParsing"])


if __name__ == "__main__":
    unittest.main()
