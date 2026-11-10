import unittest
from analyze_prepared_frame_capture import parse


class PreparedFrameParserTest(unittest.TestCase):
    def test_statuses_are_not_silently_dropped(self):
        text = "\n".join(f"ROW_FRAME timestampMs={index} status={status} width=144 height=320"
                         for index, status in enumerate(("saved", "dropped", "failed")))
        result = parse(text)
        self.assertTrue(result["complete"])
        self.assertEqual((result["saved"], result["dropped"], result["failed"]), (1, 1, 1))

    def test_unknown_duplicate_and_invalid_schema_rejected(self):
        valid = "ROW_FRAME timestampMs=1 status=saved width=144 height=320"
        for text in ("", valid + " unknown=1", valid.replace("width=144", "width=0"), valid + "\n" + valid):
            self.assertFalse(parse(text)["complete"])


if __name__ == "__main__":
    unittest.main()
