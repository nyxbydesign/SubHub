import unittest
from analyze_chrome_geometry import parse

VALID = "CHROME_GEOMETRY sample=1 id=toolbar startMs=100 endMs=102 status=ok visible=true rect=0,50,100,150"


class ChromeGeometryParserTest(unittest.TestCase):
    def test_valid_geometry(self):
        result = parse(VALID)
        self.assertTrue(result["complete"])
        self.assertEqual(result["summary"]["toolbar"]["maxReadMs"], 2)

    def test_missing_and_invisible_are_not_zero_motion(self):
        for value in (VALID.replace("status=ok", "status=missing"), VALID.replace("visible=true", "visible=false"),
                      VALID.replace("rect=0,50,100,150", "rect=0,0,0,0")):
            result = parse(value)
            self.assertTrue(result["complete"])
            self.assertEqual(result["usableRecords"], 0)

    def test_unknown_or_invalid_clock_rejected(self):
        for value in ("", VALID + " extra=1", VALID.replace("endMs=102", "endMs=99")):
            self.assertFalse(parse(value)["complete"])


if __name__ == "__main__":
    unittest.main()
