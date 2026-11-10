import unittest
import json
from analyze_person_coverage import parse

MODEL = "PersonInference: PERSON_MODEL v=1 run=4 totalMs=23 prepMs=3 runtimeMs=18 postMs=2 cancelled=false success=true"
PROVISIONAL = "Service: PERSON_PROVISIONAL v=1 source=100 captureAgeMs=80"
PUBLISH = "Service: PERSON_PUBLISH v=1 run=4 source=100 captureAgeMs=106 applied=true submitted=1 dropped=0 preemptions=3 denied=0"


class PersonCoverageTraceTest(unittest.TestCase):
    def test_native_lab_ndjson_envelope_uses_the_same_strict_schema(self):
        lines = [json.dumps(dict(sequence=i+1, elapsedNanos=1000000+i,
                    observedUptimeMillis=i, tag="fixture", message=line))
                 for i, line in enumerate((PROVISIONAL, MODEL, PUBLISH))]
        result = parse("\n".join(lines))
        self.assertTrue(result["complete"])
        self.assertEqual(3, result["rawRecords"])
        self.assertEqual(3, result["parsedRecords"])

    def test_complete_fixture_reports_both_model_and_render_timing(self):
        result = parse("\n".join((PROVISIONAL, MODEL, PUBLISH)))
        self.assertTrue(result["complete"])
        self.assertEqual(3, result["rawRecords"])
        self.assertEqual(3, result["parsedRecords"])
        self.assertEqual(1, result["appliedRefinements"])
        self.assertEqual(18, result["distributions"]["MODEL.runtimeMs"]["median"])

    def test_unknown_fields_versions_and_kinds_are_incomplete(self):
        for line in (MODEL + " future=1", MODEL.replace("v=1", "v=2"), "PERSON_FUTURE v=1"):
            result = parse(line)
            self.assertFalse(result["complete"])
            self.assertEqual(1, result["rawRecords"])
            self.assertEqual(0, result["parsedRecords"])

    def test_missing_model_and_duplicate_runs_are_incomplete(self):
        self.assertFalse(parse(PUBLISH)["complete"])
        self.assertFalse(parse(MODEL + "\n" + MODEL)["complete"])

    def test_empty_trace_has_no_fake_zero_timing(self):
        result = parse("unrelated")
        self.assertFalse(result["complete"])
        self.assertIsNone(result["distributions"]["MODEL.runtimeMs"])

    def test_invalid_ndjson_message_is_incomplete_not_a_crash(self):
        result = parse(json.dumps(dict(tag="PERSON_MODEL", message=None)))
        self.assertEqual(1, result["rawRecords"])
        self.assertFalse(result["complete"])

    def test_malformed_records_are_counted_not_ignored(self):
        for line in ("PERSON_MODEL", MODEL.replace("runtimeMs=18", "runtimeMs=oops"), MODEL + " v=1"):
            result = parse(line)
            self.assertEqual(1, result["rawRecords"])
            self.assertFalse(result["complete"])


if __name__ == "__main__":
    unittest.main()
