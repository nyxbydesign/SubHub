import unittest
from analyze_face_geometry import analyze

ROW = ("FACE_GEOMETRY v=1 id=1 source=100x200 viewport=100x200 cameras=0,0,0,0,0,0 "
       "obsTotal=1 obsEncoded=1 observations=1,0,20,100,40,40 "
       "tracksTotal=1 tracksEncoded=1 tracks=1,1,0,0,20,100,40,40,20,70,40,40")


class GeometryTest(unittest.TestCase):
    def test_registered_motion_explains_event_residual_without_claiming_pixel_time(self):
        first = ROW.replace("v=1", "v=2") + " spatial=1,2,3,4,0.0"
        second = (first.replace("id=1", "id=2").replace("cameras=0,0", "cameras=0,7")
                  .replace("observations=1,0,20,100", "observations=1,0,20,-77")
                  .replace("spatial=1,2,3,4,0.0", "spatial=1,2,3,4,176.0"))
        result = analyze(first + "\n" + second)
        self.assertTrue(result["complete"])
        candidate = result["singleFaceMotionCandidates"][0]
        self.assertEqual(-170, candidate["faceMinusEventDy"])
        self.assertEqual(-1, candidate["faceMinusImageDy"])
        self.assertFalse(result["pixelTimeKnown"])

    def test_spatial_unknown_and_scope_geometry_horizontal_changes_do_not_bridge(self):
        first = ROW.replace("v=1", "v=2") + " spatial=1,2,3,4,0.0"
        second = first.replace("id=1", "id=2")
        for changed in (second.replace("spatial=1,2,3,4,0.0", "spatial=-"),
                        second.replace("spatial=1,2,3,4", "spatial=1,3,3,4"),
                        second.replace("spatial=1,2,3,4", "spatial=1,2,3,5"),
                        second.replace("source=100x200", "source=100x400"),
                        second.replace("cameras=0,0", "cameras=1,0"), first):
            with self.subTest(changed=changed):
                result = analyze(first + "\n" + changed)
                self.assertTrue(result["complete"])
                self.assertEqual([], result["singleFaceMotionCandidates"])
        unknown = second.replace("spatial=1,2,3,4,0.0", "spatial=-")
        self.assertEqual([], analyze(first + "\n" + unknown + "\n" + second.replace("id=2", "id=3"))
                         ["singleFaceMotionCandidates"])

    def test_malformed_or_truncated_records_disable_motion_comparisons(self):
        first = ROW.replace("v=1", "v=2") + " spatial=1,2,3,4,0.0"
        second = first.replace("id=1", "id=2")
        for bad in (ROW.replace("v=1", "v=2"), ROW + " spatial=-",
                    first.replace("obsTotal=1", "obsTotal=9"), first + " extra=1"):
            result = analyze(first + "\n" + bad + "\n" + second)
            self.assertFalse(result["complete"])
            self.assertEqual([], result["singleFaceMotionCandidates"])

    def test_raw_filtered_difference_is_not_absolute_alignment(self):
        result = analyze(ROW)
        self.assertTrue(result["complete"])
        self.assertEqual(30, result["freshRawToFilteredYMaxPx"])
        self.assertFalse(result["absoluteAlignmentMeasured"])

    def test_schema_and_truncated_data_are_explicitly_incomplete(self):
        self.assertFalse(analyze(ROW + " extra=1")["complete"])
        self.assertFalse(analyze(ROW.replace("obsTotal=1", "obsTotal=9"))["complete"])
        self.assertFalse(analyze(ROW.replace("tracks=1,1,0", "tracks=1,3,0"))["complete"])

    def test_missing_track_is_not_counted_as_a_fresh_measurement(self):
        result = analyze(ROW.replace("tracks=1,1,0", "tracks=1,1,2"))
        self.assertTrue(result["complete"])
        self.assertEqual(0, result["freshTracks"])
        self.assertIsNone(result["freshRawToFilteredYMaxPx"])


if __name__ == "__main__":
    unittest.main()
