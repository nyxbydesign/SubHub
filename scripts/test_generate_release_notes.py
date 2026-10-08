import importlib.util
import sys
import json
import subprocess
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("generate_release_notes.py")
SPEC = importlib.util.spec_from_file_location("generate_release_notes", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class ReleaseNotesTest(unittest.TestCase):
    def test_categorizes_subjects_and_uses_a_concise_body_sentence(self):
        values = MODULE.entries([
            MODULE.Commit("a" * 40, "[feat] Add a velvet dashboard",
                          "Give Home a deliberate hierarchy. Keep the second sentence out."),
            MODULE.Commit("b" * 40, "[fix] Restore update handoff",
                          "Checks: gradle\n\nAndroid now opens the verified installer."),
        ])
        notes = MODULE.changelog_markdown("0.6.0", values)
        self.assertIn("### New", notes)
        self.assertIn("**Add a velvet dashboard** — Give Home a deliberate hierarchy.", notes)
        self.assertIn("### Fixed", notes)
        self.assertIn("Android now opens the verified installer.", notes)
        self.assertNotIn("second sentence", notes)

    def test_repairs_literal_newlines_from_historical_powershell_commits(self):
        values = MODULE.entries([
            MODULE.Commit("c" * 40, "[fix] Respect protection state",
                          "Stop limits when protection stops.\\n\\nChecks: gradle"),
        ])
        self.assertEqual("Stop limits when protection stops.", values[0].detail)
        self.assertEqual(values[0].detail, values[0].detail.rstrip())

    def test_read_commits_preserves_an_empty_final_body(self):
        original = MODULE.run_git
        MODULE.run_git = lambda arguments: "a" * 40 + "\0[fix] Final fix\0\0\n"
        try:
            commits = MODULE.read_commits("v0.1.0")
        finally:
            MODULE.run_git = original
        self.assertEqual(1, len(commits))
        self.assertEqual("", commits[0].body)

    def test_omits_release_bump_noise_but_keeps_other_maintenance(self):
        values = MODULE.entries([
            MODULE.Commit("d" * 40, "[chore] Bump SubHub to 0.6.0", ""),
            MODULE.Commit("f" * 40, "[build] Release SubHub 0.6.0", ""),
            MODULE.Commit("e" * 40, "[chore] Refresh bundled model metadata", ""),
        ])
        self.assertEqual(["Refresh bundled model metadata"], [value.title for value in values])

    def test_rejects_untyped_release_commits(self):
        with self.assertRaisesRegex(ValueError, "supported \\[type\\] prefix"):
            MODULE.entries([MODULE.Commit("f" * 40, "misc update", "")])

    def test_release_keeps_download_guidance_separate_from_changelog(self):
        changelog = MODULE.changelog_markdown(
            "0.6.0", [MODULE.Entry("feat", "Add release notes", "")]
        )
        release = MODULE.release_markdown(
            "0.6.0", changelog, "bozo-industries/SubHub", "v0.5.2", "v0.6.0"
        )
        self.assertLess(release.index("## What’s new"), release.index("## Choose your APK"))
        self.assertIn("compare/v0.5.2...v0.6.0", release)

    def test_legacy_history_keeps_useful_untyped_commits(self):
        values = MODULE.entries([
            MODULE.Commit("a" * 40, "feat: add Dom and Sub modes", "Two clear views."),
            MODULE.Commit("b" * 40, "Polish an early control", "Keep the layout coherent."),
        ], strict=False)
        self.assertEqual(["feat", "refactor"], [value.kind for value in values])
        self.assertEqual("add Dom and Sub modes", values[0].title)

    def test_previous_release_base_excludes_automatic_dev_tags(self):
        original = MODULE.run_git
        calls = []
        def run(arguments):
            calls.append(arguments)
            return "v0.6.4\n"
        MODULE.run_git = run
        try:
            self.assertEqual("v0.6.4", MODULE.previous_tag("HEAD"))
        finally:
            MODULE.run_git = original
        self.assertIn("--exclude", calls[0])
        self.assertIn("v*-dev.*", calls[0])

    def test_development_notes_use_the_actual_push_range(self):
        original = MODULE.run_git
        before = "a" * 40
        calls = []
        def run(arguments):
            calls.append(arguments)
            return ""
        MODULE.run_git = run
        try:
            self.assertEqual(before, MODULE.development_base(before))
        finally:
            MODULE.run_git = original
        self.assertEqual([["merge-base", "--is-ancestor", before, "HEAD"]], calls)

    def test_imported_history_base_requires_exact_published_tree_identity(self):
        original = MODULE.run_git
        def run(arguments):
            if arguments[0] == "describe":
                raise subprocess.CalledProcessError(128, arguments)
            if arguments[0] == "merge-base":
                return ""
            self.assertEqual("rev-parse", arguments[0])
            return "tree-identity\ntree-identity\n"
        MODULE.run_git = run
        try:
            self.assertEqual(MODULE.SOURCE_HISTORY_EQUIVALENTS["v0.6.3"], MODULE.previous_tag("HEAD"))
        finally:
            MODULE.run_git = original

    def test_imported_history_base_rejects_a_different_tree(self):
        original = MODULE.run_git
        MODULE.run_git = lambda arguments: "old-tree\nchanged-tree\n" if arguments[0] == "rev-parse" else ""
        try:
            with self.assertRaisesRegex(ValueError, "published tree"):
                MODULE.equivalent_source_base("HEAD")
        finally:
            MODULE.run_git = original

    def test_new_and_rewritten_branches_do_not_replay_unrelated_legacy_history(self):
        original = MODULE.run_git
        def run(arguments):
            if arguments[0] == "merge-base":
                raise subprocess.CalledProcessError(1, arguments)
            self.assertEqual(["rev-parse", "HEAD^"], arguments)
            return "b" * 40 + "\n"
        MODULE.run_git = run
        try:
            for before in ("", "0" * 40, "a" * 40):
                self.assertEqual("b" * 40, MODULE.development_base(before))
        finally:
            MODULE.run_git = original

    def test_history_outputs_collapsible_markdown_and_app_json(self):
        records = [MODULE.ReleaseRecord(
            "0.5.3", "v0.5.3", "## What’s new\n\n- Better Home\n",
            "2026-08-26T12:00:00+00:00",
        )]
        markdown = MODULE.history_markdown(records)
        self.assertIn("<details open>", markdown)
        self.assertIn("SubHub 0.5.3", markdown)
        payload = json.loads(MODULE.history_json(records, "bozo-industries/SubHub"))
        self.assertEqual("v0.5.3", payload[0]["tag"])
        self.assertIn("Better Home", payload[0]["notes"])


if __name__ == "__main__":
    unittest.main()
