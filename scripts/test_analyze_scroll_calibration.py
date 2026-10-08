import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("analyze_scroll_calibration.py")
SPEC = importlib.util.spec_from_file_location("scroll_calibration", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class CalibrationLearnerTest(unittest.TestCase):
    def test_recovers_gain_and_latency_on_held_out_windows(self):
        events = []
        visual = []
        gain = 1.25
        latency = 0.10
        tau = 0.04
        interval = 0.02
        for gesture in range(8):
            burst_start = 1.0 + gesture * 1.0
            direction = -1.0 if gesture % 4 < 2 else 1.0
            gesture_events = []
            for event_index in range(6):
                event_time = burst_start + event_index * 0.045
                event = {
                    "time": event_time,
                    "dx": 0.0,
                    "dy": direction * (7.0 + event_index),
                    "gesture": gesture,
                }
                events.append(event)
                gesture_events.append(event)
            for frame_index in range(32):
                end = burst_start - 0.08 + frame_index * interval
                start = end - interval
                predicted_dy = 0.0
                for event in gesture_events:
                    center = event["time"] + latency
                    end_cdf = MODULE.causal_exponential_cdf((end - center) / tau)
                    start_cdf = MODULE.causal_exponential_cdf((start - center) / tau)
                    predicted_dy += event["dy"] * (end_cdf - start_cdf)
                visual.append({
                    "traceTime": end,
                    "intervalMs": interval * 1000.0,
                    "dx": 0.0,
                    "dy": predicted_dy * gain,
                    "accepted": True,
                    "confidence": 0.95,
                    "gesture": gesture,
                })

        model = MODULE.fit_profile(visual, events)

        self.assertIsNotNone(model)
        self.assertAlmostEqual(gain, model["gain"], delta=0.12)
        self.assertAlmostEqual(latency * 1000.0, model["latencyMs"], delta=30.0)
        self.assertAlmostEqual(tau * 1000.0, model["tauMs"], delta=20.0)

    def test_stationary_empty_trace_cannot_create_profile(self):
        self.assertIsNone(MODULE.fit_profile([], []))

    def test_causal_kernel_cannot_consume_future_event(self):
        pairs = MODULE.build_kernel_pairs(
            [{
                "traceTime": 1.0,
                "intervalMs": 16.0,
                "dx": 1.0,
                "dy": 0.0,
                "accepted": True,
                "confidence": 1.0,
                "gesture": 0,
            }],
            [{"time": 1.010, "dx": 1000.0, "dy": 0.0}],
            0.0,
            0.015,
        )

        self.assertEqual(1, len(pairs))
        self.assertEqual(0.0, pairs[0]["ex"])

    def test_presentation_kernel_cannot_use_source_time_before_receipt(self):
        pairs = MODULE.build_kernel_pairs(
            [{
                "traceTime": 1.0,
                "intervalMs": 16.0,
                "dx": 1.0,
                "dy": 0.0,
                "accepted": True,
                "confidence": 1.0,
                "gesture": 0,
            }],
            [{
                "time": 1.010,
                "fit_time": 0.900,
                "dx": 1000.0,
                "dy": 0.0,
            }],
            0.0,
            0.015,
        )

        self.assertEqual(1, len(pairs))
        self.assertEqual(0.0, pairs[0]["ex"])

    def test_percentile_is_interpolated(self):
        self.assertEqual(2.5, MODULE.percentile([1.0, 2.0, 3.0, 4.0], 50))

    def test_scroll_parser_accepts_extended_reordered_fields(self):
        message = (
            "SCROLL_EVENT id=7 source=accessibility-authoritative eventAgeMs=23 "
            "rawDx=0 rawDy=-42 dx=0 dy=-42 evidence=EXPLICIT adjustedPx=0 "
            "amplified=false sourceUptimeMs=1000 receivedUptimeMs=1023 "
            "surfaceToken=abc surfaceConfidence=3 surfaceCacheable=true "
            "observedSurfaceConfidence=1 surfaceDecision=REUSE_ACTIVE "
            "motionToken=def touchId=9 touchActive=true documentEpoch=4"
        )

        parsed = MODULE.parse_scroll_message(message, 5.0)

        self.assertEqual(4.977, parsed["fit_time"])
        self.assertEqual("abc", parsed["surface"])
        self.assertEqual(3, parsed["surface_confidence"])
        self.assertEqual(1, parsed["observed_surface_confidence"])
        self.assertEqual("REUSE_ACTIVE", parsed["surface_decision"])
        self.assertEqual("def", parsed["motion_token"])
        self.assertEqual(7, parsed["scroll_id"])
        self.assertEqual(9, parsed["gesture"])
        self.assertEqual(9, parsed["touch_id"])
        self.assertTrue(parsed["touch_active"])

    def test_scroll_parser_preserves_legacy_minimum(self):
        parsed = MODULE.parse_scroll_message(
            "SCROLL_EVENT id=2 source=accessibility rawDx=0 rawDy=8 dx=0 dy=8 "
            "evidence=ABSOLUTE amplified=false",
            3.0,
        )

        self.assertEqual("unknown", parsed["surface"])
        self.assertEqual(3.0, parsed["fit_time"])

    def test_touch_parser_uses_source_time(self):
        parsed = MODULE.parse_touch_message(
            "CALIBRATION_TOUCH id=3 phase=start sourceUptimeMs=900 "
            "receivedUptimeMs=930 eventAgeMs=30 durationMs=0",
            2.0,
        )

        self.assertEqual(1.97, parsed["fit_time"])
        self.assertEqual("start", parsed["phase"])

    def test_stop_marker_uses_rising_edge_of_final_run(self):
        candidates = [
            {"time": 0.40}, {"time": 0.43},
            {"time": 78.20}, {"time": 78.40}, {"time": 78.60},
            {"time": 78.80}, {"time": 79.00}, {"time": 79.20},
        ]

        self.assertEqual(0.40, MODULE.marker_anchor(candidates, "start", 80.0))
        self.assertEqual(78.20, MODULE.marker_anchor(candidates, "stop", 80.0))

    def test_scene_parser_validates_numeric_box_counts(self):
        scene = MODULE.parse_scene_message(
            "CALIBRATION_SCENE id=1:2 size=1344x2992 trackCamera=0,20 "
            "currentCamera=0,40 liveCount=1 cachedCount=0 encodedLive=1 "
            "encodedCached=0 liveBoxes=10,20,30,40,7 cachedBoxes=",
            4.0,
        )

        self.assertEqual(7, scene["live"][0]["trackId"])
        self.assertEqual([], scene["cached"])

        with self.assertRaises(ValueError):
            MODULE.parse_scene_message(
                "CALIBRATION_SCENE id=1 size=1344x2992 trackCamera=0,0 "
                "currentCamera=0,0 encodedLive=2 encodedCached=0 "
                "liveBoxes=10,20,30,40,7 cachedBoxes=",
                4.0,
            )
        with self.assertRaises(ValueError):
            MODULE.parse_scene_message(
                "CALIBRATION_SCENE id=1 size=0x2992 trackCamera=0,0 "
                "currentCamera=0,0 encodedLive=1 encodedCached=0 "
                "liveBoxes=10,20,30,40,7 cachedBoxes=",
                4.0,
            )

    def test_validator_requires_real_time_touch_coverage_and_frame_cadence(self):
        touches = [
            {"gesture": 1, "phase": "start", "fit_time": 1.0},
            {"gesture": 1, "phase": "end", "fit_time": 2.0},
        ]
        scrolls = [{
            "surface": "abc",
            "surface_cacheable": True,
            "surface_confidence": 2,
            "touch_id": 1,
            "dx": 0,
            "dy": 10,
        } for _ in range(24)]
        trace = {
            "validation": {
                "records": 30,
                "sequenceMonotonic": True,
                "elapsedMonotonic": True,
                "uptimeMonotonic": True,
                "duplicateSequences": 0,
                "privacyViolations": 0,
            },
            "invalidLines": 0,
            "touches": touches,
            "scrolls": scrolls,
            "sceneParseErrors": 0,
        }
        samples = [{
            "intervalMs": 16.667,
            "accepted": True,
            "agreeingBands": 4,
            "dx": 0.0,
            "dy": 2.0,
        } for _ in range(30)]
        manifest = {
            "eventCount": 30,
            "droppedEvents": 0,
            "capture": {"overlayAppearance": {
                "showBorder": True,
                "borderColor": 0xFFFF0080,
            }},
        }

        valid = MODULE.validate_inputs(
            manifest, {"samples": samples}, trace, {"eligible": True})
        slow = MODULE.validate_inputs(
            manifest,
            {"samples": [dict(item, intervalMs=22.3) for item in samples]},
            trace,
            {"eligible": True},
        )

        self.assertTrue(valid["evaluationEligible"])
        self.assertFalse(valid["promotionEligible"])
        self.assertIn("teacher-sampling-below-50fps", slow["evaluationBlockedReasons"])


if __name__ == "__main__":
    unittest.main()
