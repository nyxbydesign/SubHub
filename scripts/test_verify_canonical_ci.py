"""Offline fixtures for public release qualification; no GitHub or Gradle calls."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import verify_canonical_ci as policy


class CanonicalCiTest(unittest.TestCase):
    def setUp(self):
        self.sha, self.tree = "a" * 40, "b" * 40
        self.workflow = {"id": 17, "path": policy.WORKFLOW, "state": "active"}
        self.run = {"id": 101, "run_number": 12, "run_attempt": 1, "workflow_id": 17,
                    "head_sha": self.sha, "path": policy.WORKFLOW,
                    "repository": {"id": 23}, "head_repository": {"id": 23},
                    "event": "pull_request", "status": "completed", "conclusion": "success"}
        self.receipt = {"schema": 1, "workflowPath": policy.WORKFLOW,
                        "sourceCommit": self.sha, "sourceTree": self.tree,
                        "profile": dict(policy.PROFILE), "runId": 101, "runAttempt": 1}

    def require_run(self, runs):
        return policy.require_run(runs, self.workflow, 23, self.sha)

    def test_exact_source_and_default_profile_qualify(self):
        run = self.require_run([self.run])
        policy.require_receipt(self.receipt, run, self.sha, self.tree)

    def test_no_missing_other_head_or_other_workflow_qualification(self):
        for change in ({"head_sha": "c" * 40}, {"workflow_id": 18}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.require_run([{**self.run, **change}])
        with self.assertRaises(ValueError):
            self.require_run([])

    def test_canonical_workflow_must_be_active_at_expected_path(self):
        for change in ({"path": ".github/workflows/other.yml"}, {"state": "disabled_manually"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                policy.require_run([self.run], {**self.workflow, **change}, 23, self.sha)

    def test_untrusted_run_metadata_is_refused(self):
        for change in ({"path": ".github/workflows/other.yml"},
                       {"repository": {"id": 99}}, {"head_repository": {"id": 99}},
                       {"head_repository": None}, {"event": "push"}):
            run = {**self.run, **change}
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.require_run([run])

    def test_newer_pending_or_failed_run_cannot_fall_back_to_old_green(self):
        outcomes = [("queued", None), ("in_progress", None), ("waiting", None)]
        outcomes += [("completed", value) for value in
                     ("failure", "cancelled", "skipped", "timed_out", "action_required", "stale")]
        for status, conclusion in outcomes:
            newer = {**self.run, "id": 102, "run_number": 13,
                     "status": status, "conclusion": conclusion}
            with self.subTest(status=status, conclusion=conclusion), self.assertRaises(ValueError):
                self.require_run([self.run, newer])

    def test_newer_rerun_attempt_cannot_reuse_first_attempt(self):
        rerun = {**self.run, "run_attempt": 2, "status": "in_progress", "conclusion": None}
        with self.assertRaises(ValueError):
            self.require_run([self.run, rerun])
        rerun.update(status="completed", conclusion="success")
        with self.assertRaises(ValueError):
            policy.require_receipt(self.receipt, self.require_run([self.run, rerun]), self.sha, self.tree)

    def test_receipt_binds_head_tree_workflow_attempt_and_configuration(self):
        changes = [{"sourceCommit": "c" * 40}, {"sourceTree": "c" * 40},
                   {"workflowPath": "other.yml"}, {"runId": 100}, {"runAttempt": 2},
                   {"schema": 2}, {"schema": True}, {"extra": True},
                   {"profile": {**policy.PROFILE, "replacementLabCandidate": True}},
                   {"profile": {**policy.PROFILE, "immediateQualityExperiment": True}},
                   {"profile": {**policy.PROFILE, "immediateQualityExperiment": 0}}]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                policy.require_receipt({**self.receipt, **change}, self.run, self.sha, self.tree)

    def test_record_checks_actual_clean_source_and_generated_flags(self):
        config = "boolean IMMEDIATE_QUALITY_EXPERIMENT = false; boolean REPLACEMENT_LAB_CANDIDATE = false;"
        env = {"QUALIFIED_HEAD": self.sha, "GITHUB_RUN_ID": "101", "GITHUB_RUN_ATTEMPT": "1"}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "qualification.json"
            with mock.patch.dict(os.environ, env), mock.patch.object(policy, "git", side_effect=[self.sha, "", self.tree]), \
                    mock.patch.object(Path, "read_text", return_value=config):
                policy.record(output)
            self.assertEqual(self.receipt, json.loads(output.read_text(encoding="utf-8")))
            with mock.patch.dict(os.environ, env), mock.patch.object(policy, "git", side_effect=[self.sha, "M app/file"]):
                with self.assertRaises(ValueError):
                    policy.record(output)
            with mock.patch.dict(os.environ, env), mock.patch.object(policy, "git", side_effect=[self.sha, ""]), \
                    mock.patch.object(Path, "read_text", return_value=config.replace("= false", "= true", 1)):
                with self.assertRaises(ValueError):
                    policy.record(output)

    def test_workflow_publication_reuses_canonical_checks_but_keeps_release_variant(self):
        root = Path(__file__).resolve().parent.parent
        ci = (root / policy.WORKFLOW).read_text(encoding="utf-8")
        release = (root / ".github/workflows/release.yml").read_text(encoding="utf-8")
        trigger = ci.split("permissions:", 1)[0]
        self.assertNotIn("push:", trigger)
        self.assertIn("pull_request:", trigger)
        self.assertIn("workflow_dispatch:", trigger)
        self.assertIn("ref: ${{ github.event.pull_request.head.sha || github.sha }}", ci)
        self.assertIn("testDebugUnitTest lintDebug assembleDebug", ci)
        self.assertIn("scripts.test_verify_canonical_ci", ci)
        self.assertIn("scripts.test_analyze_replacement_trace", ci)
        public = release.split("      - name: Build and verify signed release APKs", 1)[1]
        public = public.split('if [[ "$PUBLISH_RELEASE" == "true" ]]; then', 1)[1]
        public = public.split("          else", 1)[0]
        self.assertIn("lintRelease assembleRelease", public)
        self.assertNotIn("testDebugUnitTest", public)
        self.assertNotIn("lintDebug", public)
        self.assertIn("verify_canonical_ci.py verify", release)
        self.assertIn("testDebugUnitTest lintDebug lintRelease assembleDebug assembleDebugAndroidTest assembleRelease", release)
        self.assertIn("CI_RUN_NUMBER: ${{ github.run_number }}", release)
        self.assertIn("Verify release signatures", release)


if __name__ == "__main__":
    unittest.main()
