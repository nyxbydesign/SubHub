import unittest
from compare_row_motion_video import accumulate_motion


class AdjacentMotionTest(unittest.TestCase):
    def test_sum_uses_only_edges_inside_requested_interval(self):
        edges = {1: (-3, 100), 2: (-4, 80), 3: (2, 90), 4: (999, 100)}
        self.assertEqual(accumulate_motion(edges, 0, 3), (-5, 80))
        self.assertEqual(accumulate_motion(edges, 1, 3), (-2, 80))

    def test_missing_or_failed_edge_is_not_zero_displacement(self):
        for edges in ({1: (-3, 100)}, {1: (-3, 100), 2: (None, 3)}):
            self.assertEqual(accumulate_motion(edges, 0, 2), (None, 0))
        self.assertEqual(accumulate_motion({}, 1, 1), (None, 0))


if __name__ == "__main__":
    unittest.main()
