import unittest
from pathlib import Path
from unittest.mock import patch

import cv2
import numpy as np

from analyze_scroll_alignment import (
    analyze, detect_censors, local_background_motion, magenta_mask, match_box_pairs, page_motion,
)


class AnalyzeScrollAlignmentTest(unittest.TestCase):
    @staticmethod
    def labeled_censors(shift=0, border=(182, 94, 144)):
        frame = np.full((700, 480, 3), 220, dtype=np.uint8)
        for x, y in [(15, 35), (455, 35), (15, 650), (455, 650)]:
            cv2.rectangle(frame, (x - 4, y - 4), (x + 4, y + 4), (30, 30, 30), -1)
        for x, y in [(40, 90), (260, 90), (40, 285), (260, 285), (40, 480), (260, 480)]:
            cv2.rectangle(frame, (x, y + shift), (x + 175, y + 155 + shift), (0, 0, 0), -1)
            cv2.rectangle(frame, (x, y + shift), (x + 175, y + 155 + shift), border, 2)
            cv2.putText(frame, "BLOCKED", (x + 14, y + 82 + shift),
                        cv2.FONT_HERSHEY_SIMPLEX, .65, (255, 255, 255), 2, cv2.LINE_AA)
        return frame

    def test_moving_white_labels_cannot_supply_background_motion(self):
        previous, current = self.labeled_censors(), self.labeled_censors(12)
        previous_gray = cv2.cvtColor(previous, cv2.COLOR_BGR2GRAY)
        current_gray = cv2.cvtColor(current, cv2.COLOR_BGR2GRAY)
        # Demonstrate why masking only border-colored pixels is not independent ground truth.
        contaminated, _ = page_motion(previous_gray, current_gray,
                                      magenta_mask(previous), magenta_mask(current))
        self.assertAlmostEqual(contaminated, 12.0, delta=.1)
        previous_mask, previous_boxes = detect_censors(previous, 0)
        current_mask, current_boxes = detect_censors(current, 0)
        self.assertEqual(len(previous_boxes), 6)
        self.assertEqual(len(current_boxes), 6)
        measured, _ = page_motion(previous_gray, current_gray, previous_mask, current_mask)
        self.assertIsNone(measured)  # Not enough independent page texture: fail closed.
        self.assertEqual(previous_mask[172, 70], 255)

    def test_supported_purple_and_magenta_censors_are_detected(self):
        for border in [(182, 94, 144), (210, 80, 190)]:
            with self.subTest(border=border):
                _, boxes = detect_censors(self.labeled_censors(border=border), 25)
                self.assertEqual(len(boxes), 6)
                self.assertTrue(all(box[1] > 25 for box in boxes))

    def test_colorful_art_and_unbordered_black_tiles_are_rejected(self):
        frame = np.full((240, 360, 3), 220, dtype=np.uint8)
        cv2.rectangle(frame, (10, 10), (150, 210), (210, 70, 190), -1)
        cv2.rectangle(frame, (180, 10), (340, 210), (0, 0, 0), -1)
        excluded, boxes = detect_censors(frame, 0)
        self.assertEqual(boxes, [])
        self.assertFalse(np.any(excluded))

    def test_flow_destinations_inside_censors_are_rejected(self):
        gray = np.zeros((100, 100), np.uint8)
        previous_mask = np.zeros_like(gray)
        current_mask = np.zeros_like(gray)
        current_mask[50:80] = 255
        points = np.array([[[10 + i * 5, 20]] for i in range(12)], np.float32)
        moved = points + np.array([0, 40], np.float32)
        with patch("analyze_scroll_alignment.cv2.goodFeaturesToTrack", return_value=points), \
                patch("analyze_scroll_alignment.cv2.calcOpticalFlowPyrLK",
                      return_value=(moved, np.ones((12, 1), np.uint8), None)):
            measured, count = page_motion(gray, gray, previous_mask, current_mask)
        self.assertIsNone(measured)
        self.assertEqual(count, 0)

    def test_alignment_threshold_uses_original_pixels(self):
        frames = iter([(True, np.zeros((240, 960, 3), np.uint8))] * 2 + [(False, None)])
        with patch("analyze_scroll_alignment.cv2.VideoCapture") as factory, \
                patch("analyze_scroll_alignment.detect_censors", side_effect=[
                    (np.zeros((100, 480), np.uint8), [(50, 50, 40, 40)]),
                    (np.zeros((100, 480), np.uint8), [(50, 53, 40, 40)]),
                ]), patch("analyze_scroll_alignment.page_motion", return_value=(1.5, 30)):
            capture = factory.return_value
            capture.isOpened.return_value = True
            capture.get.side_effect = lambda prop: {cv2.CAP_PROP_FPS: 30,
                cv2.CAP_PROP_FRAME_WIDTH: 960, cv2.CAP_PROP_FRAME_HEIGHT: 240}[prop]
            capture.read.side_effect = lambda: next(frames)
            result = analyze(Path("synthetic.mp4"), 1, .16)
        self.assertEqual(result["absoluteResidualPx"]["p50"], 3.0)
        self.assertEqual(result["alignmentRateWithin2px"], 0.0)
        self.assertEqual(result["alignmentRateWithin5px"], 1.0)

    def test_constant_spatial_offset_is_invisible_to_motion_residual(self):
        # Targets move 160 -> 120, while both censor positions are incorrectly 100px below.
        previous = [(100.0, 260.0, 42.0, 48.0)]
        current = [(100.0, 220.0, 42.0, 48.0)]
        pairs = match_box_pairs(previous, current, -40.0)
        self.assertEqual(pairs, [(0, 0)])
        residual = current[0][1] - previous[0][1] - (-40.0)
        self.assertEqual(residual, 0.0)
        self.assertEqual(current[0][1] - 120.0, 100.0)

    def test_bounded_diagnostic_is_labeled_and_not_a_release_gate(self):
        with patch("analyze_scroll_alignment.cv2.VideoCapture") as factory:
            capture = factory.return_value
            capture.isOpened.return_value = True
            capture.get.side_effect = lambda prop: 60 if prop == cv2.CAP_PROP_FPS else 64
            capture.read.return_value = (True, np.zeros((64, 64, 3), dtype=np.uint8))
            result = analyze(Path("diagnostic.mp4"), 1, .16, 3)
            self.assertEqual(capture.read.call_count, 3)
            capture.release.assert_called_once()
        self.assertTrue(result["sampleLimitReached"])
        self.assertEqual(result["sampledFrames"], 3)
        self.assertFalse(result["measurementContract"]["absoluteTargetAlignmentMeasured"])
        self.assertFalse(result["measurementContract"]["releaseGateEligible"])
        self.assertEqual(result["evidenceStatus"], "no-supported-censors")
        self.assertIsNone(result["alignmentRateWithin2px"])
        self.assertIsNone(result["correctedAlignment"]["alignmentRateWithin2px"])

    def test_invalid_sample_budget_rejected_before_opening_capture(self):
        with self.assertRaises(ValueError):
            analyze(Path("missing.mp4"), 1, .16, 0)

    def test_match_box_pairs_keeps_one_to_one_identity(self):
        previous = [(100.0, 160.0, 42.0, 48.0), (260.0, 220.0, 50.0, 52.0)]
        current = [(101.0, 120.0, 43.0, 48.0), (259.0, 180.0, 50.0, 52.0)]

        self.assertEqual(sorted(match_box_pairs(previous, current, -40.0)), [(0, 0), (1, 1)])

    def test_local_background_follows_large_translation(self):
        rng = np.random.default_rng(25)
        previous = rng.integers(0, 256, size=(420, 420), dtype=np.uint8)
        previous = cv2.GaussianBlur(previous, (5, 5), 0)
        current = cv2.warpAffine(
            previous,
            np.float32([[1.0, 0.0, 0.0], [0.0, 1.0, -86.0]]),
            (420, 420),
            borderMode=cv2.BORDER_REPLICATE,
        )
        previous_mask = np.zeros_like(previous, dtype=np.uint8)
        current_mask = np.zeros_like(previous, dtype=np.uint8)
        previous_mask[130:190, 170:230] = 255
        current_mask[44:104, 170:230] = 255

        evidence = local_background_motion(
            previous,
            current,
            previous_mask,
            current_mask,
            (200.0, 160.0, 60.0, 60.0),
            (0.0, -86.0),
            0,
            1.0,
        )

        self.assertIsNotNone(evidence)
        self.assertTrue(evidence["accepted"])
        self.assertAlmostEqual(evidence["dy"], -86.0, delta=4.0)


if __name__ == "__main__":
    unittest.main()
