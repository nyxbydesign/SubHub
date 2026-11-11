"""Offline workflow invariants for optional native qualification and release gating."""
from pathlib import Path
import re
import unittest

from scripts import run_extra_time_native as native
from scripts import verify_canonical_ci as policy

ROOT = Path(__file__).resolve().parent.parent


def jobs(text):
    """Split the repository's fixed two-space job structure without a YAML dependency."""
    entries = re.split(r"^  ([a-z][a-z0-9_-]*):\n", text.split("\njobs:\n", 1)[1], flags=re.MULTILINE)
    return dict(zip(entries[1::2], entries[2::2]))


class NativeCiPolicyTest(unittest.TestCase):
    def setUp(self):
        self.ci = (ROOT / policy.WORKFLOW).read_text()
        self.jobs = jobs(self.ci)

    def test_only_retained_workflows_exist_and_native_is_manual_opt_in(self):
        self.assertFalse((ROOT / ".github/workflows/dummy-preview-native.yml").exists())
        self.assertFalse((ROOT / ".github/workflows/extra-time-native.yml").exists())
        trigger = self.ci.split("\npermissions:", 1)[0]
        self.assertEqual(["    branches: [master]"], re.findall(r"^  push:\n(    [^\n]+)$", trigger, re.MULTILINE))
        self.assertIn("  pull_request:\n    branches: [master, dev]", trigger)
        self.assertIn("      native_suite:\n", trigger)
        self.assertIn("        required: true\n        default: none\n        type: choice\n", trigger)
        options = re.search(r"^        options: \[(.*)\]$", trigger, re.MULTILINE)[1].split(", ")
        self.assertEqual(["none", *native.SUITES], options)
        self.assertEqual(1, trigger.count("        type:"))
        self.assertEqual({"build", "native"}, set(self.jobs))

    def test_canonical_job_and_receipt_are_never_conditioned_on_native_inputs(self):
        build = self.jobs["build"]
        self.assertNotIn("    if:", build)
        self.assertNotIn("native_suite", build)
        self.assertNotIn("continue-on-error:", self.ci)
        self.assertEqual(1, build.count("testDebugUnitTest lintDebug assembleDebug"))
        self.assertIn("ref: ${{ github.event.pull_request.head.sha || github.sha }}", build)
        self.assertIn("QUALIFIED_HEAD: ${{ github.event.pull_request.head.sha || github.sha }}", build)
        receipt_step = next(step for step in build.split("      - ") if "name: Record exact-source canonical qualification" in step)
        self.assertNotIn("if:", receipt_step)
        self.assertIn("verify_canonical_ci.py record --output build/reports/qualification.json", receipt_step)
        upload = next(step for step in build.split("      - ") if "name: SubHub-canonical-ci" in step)
        self.assertNotIn("if:", upload)
        self.assertIn("path: build/reports/qualification.json", upload)
        self.assertIn("if-no-files-found: error", upload)
        self.assertIn("retention-days: 90", upload)
        self.assertLess(build.index("testDebugUnitTest lintDebug assembleDebug"), build.index("Record exact-source canonical qualification"))
        self.assertLess(build.index("Record exact-source canonical qualification"), build.index("name: SubHub-canonical-ci"))
        for test in ("scripts.test_native_ci_policy", "scripts.test_native_preprovisioned", "scripts.test_run_extra_time_native", "scripts.test_extra_time_native_inventory"):
            self.assertIn(test, build)

    def test_native_requires_successful_canonical_build_and_uses_same_immutable_source(self):
        job = self.jobs["native"]
        self.assertIn("    needs: build\n", job)
        self.assertIn("    if: github.event_name == 'workflow_dispatch' && inputs.native_suite != 'none'\n", job)
        self.assertIn("    runs-on: ubuntu-24.04\n    timeout-minutes: 45\n", job)
        self.assertIn("      EXPECTED_HEAD: ${{ github.sha }}", job)
        self.assertIn("          ref: ${{ github.sha }}", job)
        self.assertIn("          persist-credentials: false", job)
        self.assertIn("      NATIVE_SUITE: ${{ inputs.native_suite }}", job)
        self.assertIn('      EXTRA_TIME_SELECTORS: ""', job)
        self.assertIn('run: python3 scripts/run_extra_time_native.py --suite "$NATIVE_SUITE"', job)
        self.assertNotIn("run: ${{", job)
        self.assertNotIn("|| true", job)
        self.assertNotIn("always()", job.split("      - name: Retain synthetic native evidence", 1)[0])
        for forbidden in ("sudo", "setfacl", "chmod", "udevadm", "--licenses", "--install", "apt-get"):
            self.assertNotIn(forbidden, job)

    def test_native_evidence_packaging_stays_at_original_path_name_and_bounds(self):
        job = self.jobs["native"]
        upload = job.split("      - name: Retain synthetic native evidence, including failures\n", 1)[1]
        self.assertEqual('''        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: SubHub-extra-time-native-${{ github.run_id }}-${{ github.run_attempt }}
          path: build/reports/extra-time-native/
          if-no-files-found: error
          retention-days: 14
''', upload)
        self.assertEqual(16 * native.MIB, native.MAX_MEMBER)
        self.assertEqual(64 * native.MIB, native.MAX_ARCHIVE)
        self.assertEqual(16 * native.MIB, native.MAX_LOG)

    def test_native_runs_are_not_cancelled_by_ordinary_canonical_work(self):
        concurrency = self.ci.split("\nconcurrency:\n", 1)[1].split("\njobs:", 1)[0]
        self.assertIn("github.run_id || 'canonical'", concurrency)
        self.assertIn("cancel-in-progress: ${{ github.event_name != 'workflow_dispatch' || inputs.native_suite == 'none' }}", concurrency)

    def test_native_failure_or_missing_canonical_receipt_never_qualifies_release(self):
        sha, tree = "a" * 40, "b" * 40
        workflow = {"id": 17, "path": policy.WORKFLOW, "state": "active"}
        passing = {"id": 101, "run_number": 12, "run_attempt": 1, "workflow_id": 17,
                   "head_sha": sha, "path": policy.WORKFLOW, "repository": {"id": 23},
                   "head_repository": {"id": 23}, "event": "workflow_dispatch", "status": "completed", "conclusion": "success"}
        failed_native = {**passing, "id": 102, "run_number": 13, "conclusion": "failure"}
        with self.assertRaisesRegex(ValueError, "latest exact-source"):
            policy.require_run([passing, failed_native], workflow, 23, sha)
        run = policy.require_run([passing], workflow, 23, sha)
        native_receipt = {"workflowPath": None, "producerPath": "scripts/run_extra_time_native.py", "state": "selected_native_passed"}
        for absent in (None, {}, native_receipt):
            with self.subTest(receipt=absent), self.assertRaises(ValueError):
                policy.require_receipt(absent, run, sha, tree)


if __name__ == "__main__":
    unittest.main()
