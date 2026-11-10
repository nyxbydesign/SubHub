import unittest
from analyze_capture_spans import analyze


def span(stage, now, request=100):
    return f"prefix CAPTURE_SPAN stage={stage} requestAgeMs={now-request} id={request} uptimeMs={now}"


class CaptureSpanTest(unittest.TestCase):
    def full(self):
        return [span("accepted", 100), span("dispatch", 110), span("callback-success", 200),
                span("prepare-start", 205),
                "CAPTURE_PREPARE readbackUs=25000 id=100 hardwareReadback=true scaleUs=5000",
                span("prepare-end", 235), span("scene-begun", 238), span("callback-exit", 250)]

    def test_reordered_fields_and_complete_timeline(self):
        report = analyze(self.full())
        self.assertEqual(1, report["counts"]["sceneBegun"])
        self.assertEqual(90, report["timings"]["dispatchToCallbackMs"]["p50"])
        self.assertEqual(30, report["timings"]["prepareMs"]["p50"])
        self.assertEqual(25, report["timings"]["explicitReadbackMs"]["p50"])
        self.assertFalse(report["acceptanceEligible"])

    def test_missing_records_are_not_zero_latency(self):
        report = analyze([span("accepted", 100), span("dispatch", 110)])
        self.assertEqual(1, report["counts"]["partialRequests"])
        self.assertIsNone(report["timings"]["dispatchToCallbackMs"])

    def test_failed_capture_keeps_wait_age(self):
        report = analyze([span("accepted", 100), span("dispatch", 110),
                          span("callback-failure-1", 5200)])
        self.assertEqual(5100, report["timings"]["failureAgeMs"]["p50"])
        self.assertEqual(1, report["counts"]["failedRequests"])

    def test_orphan_exit_is_partial_not_complete(self):
        report = analyze([span("accepted", 100), span("callback-exit", 200)])
        self.assertEqual(1, report["counts"]["partialRequests"])
        self.assertNotIn("completedCallbacks", report["counts"])

    def test_failure_cannot_contain_preparation(self):
        report = analyze([span("accepted", 100), span("dispatch", 110),
                          span("prepare-start", 120), span("callback-failure-1", 150)])
        self.assertEqual(1, report["counts"]["invalidRequests"])

    def test_duplicate_and_inconsistent_timestamps_are_rejected(self):
        report = analyze(self.full() + [span("accepted", 100)])
        self.assertEqual(1, report["counts"]["invalidRequests"])
        report = analyze([span("accepted", 100), span("callback-exit", 99)])
        self.assertEqual(1, report["malformedRecords"])

    def test_out_of_order_stage_times_are_rejected(self):
        report = analyze([span("accepted", 100), span("dispatch", 200),
                          span("callback-success", 150), span("callback-exit", 250)])
        self.assertEqual(1, report["counts"]["invalidRequests"])

    def test_async_publication_stages_have_their_own_order(self):
        # Worker may finish before callback-exit; no false cross-thread total order.
        report = analyze(self.full() + [span("fast-ready", 239), span("geometry-ready", 245),
                         span("publication-post", 248), span("publication-main", 270),
                         span("publication-tick", 280)])
        self.assertEqual(0, report["malformedRecords"])
        self.assertEqual(1, report["counts"]["completedCallbacks"])
        self.assertEqual(22, report["timings"]["publicationQueueMs"]["p50"])
        self.assertEqual(10, report["timings"]["publicationMainToTickMs"]["p50"])
        self.assertEqual(6, report["timings"]["fastToGeometryMs"]["p50"])

    def test_bad_or_unknown_publication_stages_still_fail_closed(self):
        for extra in ([span("publication-post", 280), span("publication-main", 270)],
                      [span("future-publication", 270)]):
            report = analyze(self.full() + extra)
            self.assertEqual(1, report["counts"]["invalidRequests"])

    def test_markers_scope_and_require_unambiguous_order(self):
        report = analyze(["START", *self.full(), "END", span("accepted", 100)], "START", "END")
        self.assertEqual(1, report["counts"]["sceneBegun"])
        for lines in (["END", "START"], ["START", "START", "END"], ["START"]):
            with self.assertRaises(ValueError):
                analyze(lines, "START", "END")


if __name__ == "__main__":
    unittest.main()
