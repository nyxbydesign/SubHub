import unittest

from scripts.dev_release_cycle import cycle_sequence, stable_at, verify_install_order
from scripts.release_version import build_version


class CiReleaseVersionTest(unittest.TestCase):
    def test_local_and_ordinary_private_candidates_keep_source_identity(self):
        self.assertEqual((20, "0.6.4"), build_version(20, "0.6.4"))

    def test_private_replacement_candidate_can_update_installed_dev_in_place(self):
        installed, _ = build_version(21, "0.6.5", 126, True, 1)
        candidate, name = build_version(21, "0.6.5", 128, False)
        self.assertGreater(candidate, installed)
        self.assertEqual((2_100_128, "0.6.5"), (candidate, name))

    def test_development_identity_is_unique_and_in_source_bucket(self):
        self.assertEqual((2_200_260, "0.7.0-dev.1"), build_version(22, "0.7.0", 260, True, 1))
        self.assertEqual((2_200_263, "0.7.0-dev.2"), build_version(22, "0.7.0", 263, True, 2))

    def test_newer_stable_can_replace_dev_without_downgrade(self):
        dev_code, _ = build_version(20, "0.6.4", 123, True, 1)
        stable_code, name = build_version(20, "0.6.4", 124)
        self.assertGreater(stable_code, dev_code)
        self.assertEqual("0.6.4", name)

    def test_old_branch_cannot_replace_next_source_version(self):
        old_code, _ = build_version(20, "0.6.4", 99999, True, 2)
        next_code, _ = build_version(21, "0.6.5", 1, True, 1)
        self.assertGreater(next_code, old_code)

    def test_retries_reuse_identity_without_consuming_a_new_tag(self):
        self.assertEqual(build_version(20, "0.6.4", 123, True, 1),
                         build_version(20, "0.6.4", 123, True, 1))

    def test_invalid_sequences_and_android_overflow_fail_closed(self):
        for run in (0, -1, 100000):
            with self.assertRaises(ValueError):
                build_version(20, "0.6.4", run, True)
        with self.assertRaises(ValueError):
            build_version(21000, "0.6.4", 1, True)
        with self.assertRaises(ValueError):
            build_version(20, "0.6.4", dev=True)
        with self.assertRaises(ValueError):
            build_version(20, "0.6.4-beta.1", 1, True)
        for invalid in (None, 0, 2):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                build_version(20, "0.6.4", 1, True, invalid)
        with self.assertRaises(ValueError):
            build_version(20, "0.6.4", 1, False, 1)


class DevelopmentCycleTest(unittest.TestCase):
    @staticmethod
    def workflow_run(number, second, branch="dev", event="push"):
        return {"id": number + 1000, "run_number": number, "event": event,
                "head_branch": branch, "created_at": f"2026-11-12T12:00:{second:02d}Z"}

    def test_cutover_keeps_legacy_slots_and_uses_global_code_for_install(self):
        stable = self.workflow_run(257, 1, "v0.7.0")
        first = self.workflow_run(260, 4)
        self.assertEqual(1, cycle_sequence(first, stable, "2026-11-12T12:00:02Z", [first]))
        self.assertGreater(build_version(22, "0.7.0", 260, True, 1)[0], 2_200_259)

    def test_dev_source_can_target_the_next_version_in_the_current_stable_cycle(self):
        verify_install_order((23, "0.7.1"), 260, 2_200_257)
        verify_install_order((22, "0.7.1"), 260, 2_200_257)
        with self.assertRaisesRegex(ValueError, "Bump dev VERSION_CODE"):
            verify_install_order((21, "0.7.1"), 260, 2_200_257)

    def test_later_stable_resets_display_count_and_excludes_private_and_prepublication_pushes(self):
        stable = self.workflow_run(264, 10, "v0.7.1")
        earlier = self.workflow_run(265, 11)
        private = self.workflow_run(266, 12, event="workflow_dispatch")
        first = self.workflow_run(267, 14)
        second = self.workflow_run(268, 14)
        published = "2026-11-12T12:00:13Z"
        history = [second, first, private, earlier]
        self.assertEqual(1, cycle_sequence(first, stable, published, history))
        self.assertEqual(2, cycle_sequence(second, stable, published, history))
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            cycle_sequence(second, stable, published, [second, first, earlier])

    def test_original_run_selects_historical_stable_when_a_retry_runs_later(self):
        earlier = {"id": 7, "tag_name": "v0.7.0", "published_at": "2026-11-12T12:00:10Z",
                   "draft": False, "prerelease": False, "immutable": True}
        newer = {**earlier, "id": 8, "tag_name": "v0.7.1", "published_at": "2026-11-12T12:00:30Z"}
        self.assertEqual(earlier, stable_at([newer, earlier], "2026-11-12T12:00:20Z"))
        self.assertEqual(newer, stable_at([newer, earlier], "2026-11-12T12:00:31Z"))


if __name__ == "__main__":
    unittest.main()
