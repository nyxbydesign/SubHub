import unittest
from analyze_spatial_cpu import parse

VALID = ("SPATIAL_SOURCE_CPU samples=20 accepted=10 firstCpuUs=100 medianCpuUs=20 maxCpuUs=100 "
         "medianWallUs=25 maxWallUs=150 movingSamples=5 movingMedianCpuUs=40 movingMaxCpuUs=80")


class SpatialCpuParserTest(unittest.TestCase):
    def test_valid_probe(self):
        result = parse(VALID)
        self.assertTrue(result["complete"])
        self.assertFalse(result["pipelineLatency"])

    def test_inconsistent_and_unknown_records_rejected(self):
        for value in ("", VALID + " future=1", VALID.replace("accepted=10", "accepted=30"),
                      VALID.replace("maxCpuUs=100", "maxCpuUs=10")):
            self.assertFalse(parse(value)["complete"])


if __name__ == "__main__":
    unittest.main()
