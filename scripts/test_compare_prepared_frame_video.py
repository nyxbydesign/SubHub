import unittest
import numpy as np
from compare_prepared_frame_video import match_score


class PreparedFrameMatchingTest(unittest.TestCase):
    def test_exact_textured_match_beats_shifted_match(self):
        source = np.random.default_rng(66).integers(0, 256, (80, 80), dtype=np.uint8)
        mask = np.zeros_like(source)
        exact, support = match_score(source, source, mask)
        shifted, _ = match_score(source, np.roll(source, 5, axis=0), mask)
        self.assertEqual(exact, 0)
        self.assertGreater(support, 200)
        self.assertGreater(shifted, 20)

    def test_occluded_or_textureless_image_cannot_prove_alignment(self):
        source = np.zeros((80, 80), dtype=np.uint8)
        self.assertIsNone(match_score(source, source, source)[0])
        texture = np.random.default_rng(66).integers(0, 256, source.shape, dtype=np.uint8)
        self.assertIsNone(match_score(texture, texture, np.full_like(source, 255))[0])


if __name__ == "__main__":
    unittest.main()
