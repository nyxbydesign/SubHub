"""Offline diagnostic privacy/bounds and actual CI shell exit-contract fixtures."""
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from unittest import mock

from scripts import sanitize_gradle_failure as safe


ROOT = Path(__file__).resolve().parent.parent
PRIVATE = b"PRIVATE_TOKEN https://private.invalid/secret /home/private/key"


class GradleDiagnosticTest(unittest.TestCase):
    def report(self, data):
        output = io.StringIO()
        code = safe.emit(io.BytesIO(data), output)
        text = output.getvalue()
        self.assertLessEqual(len(text.encode("utf-8")), safe.MAX_OUTPUT_BYTES)
        for value in ("PRIVATE", "https://", "/home/", "Traceback", "Caused by:"):
            self.assertNotIn(value, text)
        return code, json.loads(text)["gradleDiagnostic"]

    def test_known_cause_categories_and_fixed_task_statuses_only(self):
        data = (b"> Task :app:testDebugUnitTest\n> Task :app:packageDebug FAILED\n"
                b"* Exception is:\norg.gradle.api.tasks.TaskExecutionException: " + PRIVATE + b"\n"
                b"Caused by: java.lang.NullPointerException: " + PRIVATE + b"\n"
                b"\tat private.Secret.read(/home/private/key.java:91)\n"
                b"Suppressed: private.PRIVATE_TOKENException: " + PRIVATE + b"\n")
        code, report = self.report(data)
        self.assertEqual(0, code)
        self.assertEqual(["task_execution", "null_pointer"], report["exceptions"])
        self.assertEqual({"unit_tests": "seen", "package": "failed"}, report["tasks"])
        self.assertTrue(report["unknownException"])
        self.assertTrue(report["messagesOmitted"])
        self.assertTrue(report["exceptionSectionSeen"])

    def test_every_exact_allowlisted_exception_is_recognized(self):
        for name, category in safe.EXCEPTIONS.items():
            with self.subTest(name=name):
                _, result = self.report(b"* Exception is:\n" + name + b": " + PRIVATE)
                self.assertEqual([category], result["exceptions"])
                self.assertFalse(result["unknownException"])

    def test_early_compile_and_resource_failures_emit_only_fixed_categories(self):
        for task, label, exception, category in (
            (b":app:compileDebugJavaWithJavac", "java_compile",
             b"org.gradle.api.internal.tasks.compile.CompilationFailedException", "java_compilation"),
            (b":app:mergeDebugResources", "merge_resources",
             b"com.android.builder.internal.aapt.v2.Aapt2Exception", "android_resources"),
            (b":app:checkDebugAarMetadata", "dependency_metadata",
             b"org.gradle.internal.resolve.ModuleVersionResolveException", "dependency_resolution"),
        ):
            with self.subTest(task=task):
                _, report = self.report(b"> Task " + task + b" FAILED\n* Exception is:\n"
                                        + exception + b": " + PRIVATE + b"\n")
                self.assertEqual({label: "failed"}, report["tasks"])
                self.assertEqual([category], report["exceptions"])

    def test_every_allowlisted_task_uses_only_fixed_status_labels(self):
        for task, label in safe.TASKS.items():
            for status, emitted in safe.STATUSES.items():
                with self.subTest(task=task, status=status):
                    _, report = self.report(b"> Task " + task + (b" " + status if status else b"") + b"\n")
                    self.assertEqual({label: emitted}, report["tasks"])

    def test_lint_reports_only_exact_issue_and_source_labels(self):
        source = next(iter(safe.LINT_SOURCES))
        for issue, label in safe.LINT_ISSUES.items():
            for prefix in (b"", b"/home/private/PRIVATE_TOKEN/"):
                with self.subTest(issue=issue, prefix=prefix):
                    _, report = self.report(prefix + source + b":123: Error: " + PRIVATE + b" [" + issue + b"]\n")
                    self.assertEqual([label], report["lint"])
                    self.assertEqual([safe.LINT_SOURCES[source]], report["lintSources"])
        _, report = self.report(b"/home/private/secret.java:1: Error: " + PRIVATE + b" [NewApi]\n")
        self.assertEqual(["new_api"], report["lint"])
        self.assertEqual([], report["lintSources"])

    def test_unknown_or_malformed_lint_and_exception_text_cannot_emit_source(self):
        source = next(iter(safe.LINT_SOURCES))
        data = (source + b":1: Error: PRIVATE_TOKEN [PRIVATE_TOKEN]\n"
                + source + b":1: Error: PRIVATE_TOKEN [NewApi] trailing\n"
                + source + b":PRIVATE_TOKEN: Error: PRIVATE_TOKEN [NewApi]\n"
                + source + b":1: Warning: PRIVATE_TOKEN [NewApi]\n"
                + b"* Exception is:\n" + source + b":1: Error: PRIVATE_TOKEN [NewApi]\n")
        _, report = self.report(data)
        self.assertEqual([], report["lint"])
        self.assertEqual([], report["lintSources"])

    def test_compile_stages_and_failures_are_fixed_labels_without_diagnostic_text(self):
        data = (b"> Task :app:processDebugResources UP-TO-DATE\n"
                b"> Task :app:compileDebugJavaWithJavac FAILED\n"
                b"> Task :app:compileDebugUnitTestJavaWithJavac FAILED PRIVATE_TOKEN\n"
                b"/home/private/PRIVATE.java:17: error: PRIVATE_TOKEN\n"
                b"* Exception is:\norg.gradle.api.tasks.TaskExecutionException: " + PRIVATE + b"\n"
                b"Caused by: org.gradle.api.internal.tasks.compile.CompilationFailedException: "
                + PRIVATE + b"\n")
        _, result = self.report(data)
        self.assertEqual({"process_resources": "up_to_date", "java_compile": "failed"}, result["tasks"])
        self.assertEqual(["task_execution", "java_compilation"], result["exceptions"])
        self.assertFalse(result["unknownException"])

    def test_exception_text_cannot_overwrite_task_status_and_failure_is_monotonic(self):
        data = (b"> Task :app:packageDebug FAILED\n* Exception is:\n"
                b"java.io.IOException: PRIVATE_TOKEN\n> Task :app:packageDebug\n"
                b"> Task :app:lintDebug UP-TO-DATE\n* Try:\n> Task :app:packageDebug\n")
        _, result = self.report(data)
        self.assertEqual({"package": "failed"}, result["tasks"])

    def test_resource_phrases_are_fixed_reported_categories_not_message_copies(self):
        for phrase, category in safe.RESOURCE_MESSAGES.items():
            for message in (phrase, PRIVATE + b": " + phrase, PRIVATE + b" (" + phrase + b")"):
                with self.subTest(category=category, message=message):
                    code, result = self.report(b"* Exception is:\nCaused by: java.io.IOException: " + message + b"\n")
                    self.assertEqual(0, code)
                    self.assertEqual([category], result["resources"])
        for name, message in ((b"private.PRIVATE_TOKENException", b"No space left on device"),
                              (b"java.lang.IllegalStateException", b"No space left on device"),
                              (b"java.io.IOException", b"No space left on device PRIVATE_TOKEN")):
            _, result = self.report(b"* Exception is:\n" + name + b": " + message)
            self.assertEqual([], result["resources"])

    def test_untrusted_lines_controls_and_messages_are_not_forwarded(self):
        data = (PRIVATE + b"\njava.lang.OutOfMemoryError: PRIVATE_TOKEN\n"
                b"> Task :private:PRIVATE_TOKEN FAILED\n> Task :app:packageDebug FAILED PRIVATE_TOKEN\n"
                b"* Exception is:\n\x1b[31mjava.io.IOException: PRIVATE_TOKEN\x1b[0m\n"
                b"Caused by: java.lang.OutOfMemoryError: PRIVATE_TOKEN\n"
                b"* Try:\njava.io.IOException: No space left on device\n")
        _, result = self.report(data)
        self.assertEqual(["out_of_memory"], result["exceptions"])
        self.assertEqual({}, result["tasks"])
        self.assertEqual([], result["resources"])

    def test_crlf_partial_chunks_and_final_unterminated_line(self):
        data = b"* Exception is:\r\nCaused by: java.util.zip.ZipException: " + PRIVATE
        for chunk_size in (1, 7, safe.CHUNK_BYTES):
            with self.subTest(chunk_size=chunk_size), mock.patch.object(safe, "CHUNK_BYTES", chunk_size):
                _, result = self.report(data)
                self.assertEqual(["zip"], result["exceptions"])

    def test_oversized_line_is_discarded_and_later_cause_still_parsed(self):
        source = io.BytesIO(b"* Exception is:\njava.io.IOException: "
                            + PRIVATE * safe.MAX_LINE_BYTES + b"\n"
                            b"Caused by: java.io.EOFException: PRIVATE_TOKEN\n")
        result = safe.summarize(source)
        self.assertEqual(len(source.getvalue()), source.tell())
        self.assertTrue(result["truncated"])
        self.assertEqual(["unexpected_eof"], result["exceptions"])

    def test_fixed_chunk_and_category_limits_drain_the_complete_stream(self):
        class BoundedReader(io.BytesIO):
            def read(self, size=-1):
                self.assert_valid(size)
                return super().read(size)

            def assert_valid(self, size):
                if size != safe.CHUNK_BYTES:
                    raise AssertionError("Unbounded read")

        data = b"* Exception is:\n" + b"\n".join(name + b": " + PRIVATE for name in safe.EXCEPTIONS)
        source = BoundedReader(data)
        with mock.patch.object(safe, "MAX_CATEGORIES", 2):
            result = safe.summarize(source)
        self.assertEqual(len(data), source.tell())
        self.assertEqual(2, len(result["exceptions"]))
        self.assertTrue(result["truncated"])

    def test_parser_fault_is_private_fails_closed_and_continues_draining(self):
        data = b"* Exception is:\n" + PRIVATE * safe.MAX_LINE_BYTES
        source, output = io.BytesIO(data), io.StringIO()
        with mock.patch.object(safe.Diagnostics, "accept", side_effect=ValueError("PRIVATE_TOKEN")):
            self.assertEqual(safe.FAILURE_EXIT, safe.emit(source, output))
        self.assertEqual(len(data), source.tell())
        self.assertNotIn("PRIVATE", output.getvalue())
        self.assertTrue(json.loads(output.getvalue())["gradleDiagnostic"]["sanitizerFailed"])

    def test_summary_overflow_or_serialization_fault_never_leaks_an_error(self):
        for patch in (mock.patch.object(safe.json, "dumps", return_value="PRIVATE_TOKEN" * safe.MAX_OUTPUT_BYTES),
                      mock.patch.object(safe.json, "dumps", side_effect=ValueError("PRIVATE_TOKEN"))):
            with patch:
                code, result = self.report(b"")
                self.assertEqual(safe.FAILURE_EXIT, code)
                self.assertTrue(result["sanitizerFailed"])

    def test_unexpected_parser_fault_also_drains_without_exposing_its_message(self):
        source, output = io.BytesIO(PRIVATE * safe.MAX_LINE_BYTES), io.StringIO()
        with mock.patch.object(safe, "summarize", side_effect=ValueError("PRIVATE_TOKEN")):
            self.assertEqual(safe.FAILURE_EXIT, safe.emit(source, output))
        self.assertEqual(len(source.getvalue()), source.tell())
        self.assertNotIn("PRIVATE", output.getvalue())

    def test_all_fixed_categories_and_task_states_have_bounded_output(self):
        data = b"\n".join(b"> Task " + task + b" FAILED" for task in safe.TASKS)
        data += b"\n* Exception is:\n" + b"\n".join(name + b": " + PRIVATE for name in safe.EXCEPTIONS)
        data += b"\n" + b"\n".join(b"java.io.IOException: " + phrase for phrase in safe.RESOURCE_MESSAGES)
        data += b"\n* Try:\n" + b"\n".join(path + b":1: Error: " + PRIVATE + b" [" + issue + b"]"
                                                  for path in safe.LINT_SOURCES for issue in safe.LINT_ISSUES)
        _, result = self.report(data)
        self.assertLessEqual(len(result["exceptions"]), safe.MAX_CATEGORIES)
        self.assertEqual(len(safe.TASKS), len(result["tasks"]))


class CanonicalShellTest(unittest.TestCase):
    def build_script(self):
        workflow = (ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
        block = workflow.split("      - name: Test, lint, and build universal APK\n", 1)[1]
        block = block.split("      - name: Record exact-source canonical qualification\n", 1)[0]
        self.assertTrue(block.startswith("        shell: bash\n        run: |\n"))
        self.assertEqual(1, workflow.count("./gradlew "))
        self.assertIn("scripts.test_sanitize_gradle_failure", workflow)
        self.assertNotIn("continue-on-error", block)
        return textwrap.dedent(block.split("        run: |\n", 1)[1])

    def run_workflow(self, code, reporter_fault=None, producer_signal=False,
                     script_file=False, parent_notice=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            shutil.copyfile(ROOT / "scripts/sanitize_gradle_failure.py", root / "scripts/sanitize_gradle_failure.py")
            if reporter_fault:
                # Inject faults into a temporary reporter; exercise the real shell drainer.
                path = root / "scripts/sanitize_gradle_failure.py"
                source = path.read_text(encoding="utf-8")
                if reporter_fault == "parser":
                    source = source.replace('if __name__ == "__main__":',
                                            'def fail(self, line):\n    raise ValueError("PRIVATE_TOKEN")\n'
                                            'Diagnostics.accept = fail\n\nif __name__ == "__main__":')
                elif reporter_fault == "startup":
                    source = 'raise RuntimeError("PRIVATE_TOKEN /home/private/key")\n' + source
                elif reporter_fault in ("signal", "terminate", "kill"):
                    signal_name = {"signal": "SIGINT", "terminate": "SIGTERM", "kill": "SIGKILL"}[reporter_fault]
                    source = f'import os, signal\nos.kill(os.getpid(), signal.{signal_name})\n' + source
                else:
                    self.fail("Unknown synthetic reporter fault")
                path.write_text(source, encoding="utf-8")
            fake = root / "gradlew"
            fake.write_text("#!/usr/bin/env python3\nimport json, os, signal, sys\nfrom pathlib import Path\n"
                            "with Path('calls.jsonl').open('a') as output:\n"
                            "    output.write(json.dumps(sys.argv[1:]) + '\\n')\n"
                            "print('* Exception is:', flush=True)\n"
                            "sys.stderr.write('Caused by: java.lang.NullPointerException: PRIVATE_TOKEN\\n')\n"
                            "sys.stderr.flush()\n"
                            "sys.stdout.write('PRIVATE_TOKEN' * 100000 + '\\n')\n"
                            "sys.stdout.flush()\n"
                            "Path('producer-drained').write_text('complete')\n"
                            + ("os.kill(os.getpid(), signal.SIGTERM)\n" if producer_signal else f"sys.exit({code})\n"),
                            encoding="utf-8")
            fake.chmod(0o700)
            script = self.build_script()
            if parent_notice:
                # Bash versions differ between a terse signal notice and a full
                # "Done ... pipeline" message. Model that parent-shell stderr,
                # after capturing PIPESTATUS so the fixture cannot alter it.
                capture = 'pipeline_status=("${PIPESTATUS[@]}")'
                self.assertEqual(1, script.count(capture))
                script = script.replace(capture, capture +
                                        '\nprintf "%s\\n" "Done PRIVATE_TOKEN /home/private/key" >&2')
            command = ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail"]
            if script_file:
                path = root / "workflow-step.sh"
                path.write_text(script, encoding="utf-8")
                command.append(str(path))
            else:
                command.extend(["-c", script])
            actual = subprocess.run(command,
                                    cwd=root, capture_output=True, text=True, timeout=20)
            self.assertTrue((root / "producer-drained").is_file())
            calls = (root / "calls.jsonl").read_text(encoding="utf-8").splitlines()
            self.assertEqual(1, len(calls))
            self.assertEqual(["--stacktrace", "--console=plain", "testDebugUnitTest", "lintDebug", "assembleDebug"],
                             json.loads(calls[0]))
            self.assertEqual("", actual.stderr)
            self.assertNotIn("PRIVATE", actual.stdout)
            self.assertLessEqual(len(actual.stdout.encode("utf-8")), safe.MAX_OUTPUT_BYTES)
            return actual

    def test_single_full_invocation_preserves_gradle_exit_and_drains_large_output(self):
        for code in (0, 1, 23, 143):
            with self.subTest(code=code):
                actual = self.run_workflow(code)
                self.assertEqual(code, actual.returncode)
                result = json.loads(actual.stdout)["gradleDiagnostic"]
                self.assertEqual(["null_pointer"], result["exceptions"])
                self.assertTrue(result["truncated"])

    def test_reporter_fault_keeps_original_failure_or_fails_closed_after_success(self):
        for code in (0, 1, 23):
            with self.subTest(code=code):
                actual = self.run_workflow(code, reporter_fault="parser")
                self.assertEqual(code or safe.FAILURE_EXIT, actual.returncode)
                self.assertTrue(json.loads(actual.stdout)["gradleDiagnostic"]["sanitizerFailed"])

    def test_reporter_startup_error_and_actual_signals_keep_pipe_open_and_stderr_private(self):
        for fault, reporter_code in (("startup", 1), ("signal", 130), ("terminate", 143), ("kill", 137)):
            for code in (0, 23):
                for script_file in (False, True):
                    with self.subTest(fault=fault, code=code, script_file=script_file):
                        actual = self.run_workflow(code, reporter_fault=fault, script_file=script_file)
                        self.assertEqual(code or reporter_code, actual.returncode)
                        self.assertEqual("", actual.stdout)

    def test_actual_producer_sigterm_status_is_preserved(self):
        for script_file in (False, True):
            with self.subTest(script_file=script_file):
                actual = self.run_workflow(143, producer_signal=True, script_file=script_file)
                self.assertEqual(143, actual.returncode)
                self.assertEqual(["null_pointer"], json.loads(actual.stdout)["gradleDiagnostic"]["exceptions"])

    def test_parent_shell_pipeline_notices_cannot_escape_to_stderr(self):
        for code, producer_signal in ((0, False), (23, False), (143, True)):
            for script_file in (False, True):
                with self.subTest(code=code, script_file=script_file):
                    actual = self.run_workflow(code, producer_signal=producer_signal,
                                               script_file=script_file, parent_notice=True)
                    self.assertEqual(code, actual.returncode)
                    self.assertEqual(["null_pointer"], json.loads(actual.stdout)["gradleDiagnostic"]["exceptions"])


if __name__ == "__main__":
    unittest.main()
