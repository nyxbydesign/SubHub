import unittest
import numpy as np
from compare_spatial_registration import phase_displacement


class SourcePhaseComparisonTest(unittest.TestCase):
    def test_translated_texture_has_expected_sign(self):
        source = np.random.default_rng(69).integers(0, 256, (128, 96), dtype=np.uint8)
        result = phase_displacement(source, np.roll(source, 7, axis=0))
        self.assertTrue(result["supported"])
        self.assertAlmostEqual(result["dy"], 7, delta=.3)

    def test_blank_is_not_valid_zero_motion(self):
        blank = np.zeros((128, 96), dtype=np.uint8)
        self.assertFalse(phase_displacement(blank, blank)["supported"])


if __name__ == "__main__":
    unittest.main()
