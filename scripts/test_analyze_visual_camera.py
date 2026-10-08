import unittest
from analyze_visual_camera import parse

VALID = ("ROW_CAMERA previousMs=100 currentMs=200 scopeValid=true accepted=true "
         "uncertain=false horizontal=false pixelTimeKnown=true frameMilliY=100000 eventFrameMilliY=60000 "
         "correctionMilliY=40000 cameraMilliY=120000")


class VisualCameraParserTest(unittest.TestCase):
    def test_units_and_raw_parity(self):
        result = parse("I Probe: " + VALID)
        self.assertTrue(result["complete"])
        self.assertEqual(result["records"][0]["correctionY"], 40)
        self.assertFalse(result["renderingAuthority"])

    def test_unknown_and_invalid_records_fail_closed(self):
        for line in (VALID + " future=1", VALID.replace("horizontal=false", "horizontal=true"),
                     VALID.replace("previousMs=100", "previousMs=200"),
                     VALID.replace("accepted=true", "accepted=false")):
            result = parse(line)
            self.assertEqual(result["rawRecords"], 1)
            self.assertFalse(result["complete"])

    def test_uncertainty_is_not_lost(self):
        result = parse(VALID.replace("uncertain=false", "uncertain=true"))
        self.assertTrue(result["complete"])
        self.assertEqual(result["uncertainRecords"], 1)
        self.assertFalse(parse("")["complete"])

    def test_legacy_provenance_is_unknown_and_receipt_cannot_be_accepted(self):
        legacy = parse(VALID.replace(" pixelTimeKnown=true", ""))
        self.assertTrue(legacy["complete"])
        self.assertFalse(legacy["provenanceComplete"])
        self.assertIsNone(legacy["records"][0]["pixelTimeKnown"])
        self.assertFalse(parse(VALID.replace("pixelTimeKnown=true", "pixelTimeKnown=false"))["complete"])


if __name__ == "__main__":
    unittest.main()
