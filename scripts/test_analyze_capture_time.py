import unittest
from analyze_capture_time import parse

RECEIPT = ("CAPTURE_TIME id=100 kind=WINDOW_CLIENT_RECEIPT requestMs=100 "
           "reportedMs=150 callbackMs=160 valid=true pixelTimeKnown=false")


class CaptureTimeParserTest(unittest.TestCase):
    def test_receipt_time_retains_its_provenance(self):
        result = parse(RECEIPT)
        self.assertTrue(result["complete"])
        self.assertEqual(result["exactPixelTimes"], 0)
        self.assertEqual(result["records"][0]["requestMs"], 100)

    def test_impossible_or_new_records_are_not_silently_accepted(self):
        for record in (RECEIPT + " future=1", RECEIPT.replace("pixelTimeKnown=false", "pixelTimeKnown=true"),
                       RECEIPT.replace("callbackMs=160", "callbackMs=120"), ""):
            self.assertFalse(parse(record)["complete"])


if __name__ == "__main__":
    unittest.main()
