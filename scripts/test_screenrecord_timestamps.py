import struct
import unittest
from screenrecord_timestamps import MAGIC, parse


def fixture(version=2, stamps=(100, 150), count=2):
    return MAGIC + struct.pack("<IqI", version, 1000, count) + struct.pack(f"<{len(stamps)}Q", *stamps)


class ScreenrecordTimestampsTest(unittest.TestCase):
    def test_v2_integer_clocks_and_count(self):
        result = parse(b"mp4prefix" + fixture() + b"trailing-atoms")
        self.assertEqual(result["frameCount"], 2)
        self.assertEqual(result["epochNs"], [1100, 1150])

    def test_malformed_metadata_rejected(self):
        for data in (b"", MAGIC, fixture(version=3), fixture(count=3), fixture(count=0),
                     fixture(stamps=(150, 100)), fixture(stamps=(100, 100)),
                     fixture() + fixture()):
            with self.assertRaises(ValueError):
                parse(data)


if __name__ == "__main__":
    unittest.main()
