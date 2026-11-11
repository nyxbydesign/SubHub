"""Offline diagnostic privacy/bounds and actual CI shell exit-contract fixtures."""
import ast
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

    def test_lint_reuses_fixed_compiler_labels_without_messages_or_lines(self):
        for sources in (safe.JAVAC_MAIN_SOURCES, safe.JAVAC_SOURCES):
            source = next(path for path in sources if path not in safe.LINT_SOURCES)
            for prefix in (b"", b"/home/private/PRIVATE_TOKEN/"):
                _, report = self.report(prefix + source + b":741: Error: PRIVATE_TOKEN [NewApi]\n")
                self.assertEqual([sources[source]], report["lintSources"])
                self.assertNotIn("741", json.dumps(report))
                self.assertNotIn("PRIVATE", json.dumps(report))
            _, report = self.report(source + b".unknown:741: Error: PRIVATE_TOKEN [NewApi]\n")
            self.assertEqual([], report["lintSources"])

    def test_lint_source_union_remains_bounded(self):
        data = b"\n".join(path + b":741: Error: PRIVATE_TOKEN [NewApi]"
                          for path in safe.LINT_SOURCE_LABELS)
        code, report = self.report(data)
        self.assertEqual(0, code)
        self.assertEqual(safe.MAX_CATEGORIES, len(report["lintSources"]))
        self.assertTrue(report["truncated"])
        self.assertTrue(set(report["lintSources"]).issubset(set(safe.LINT_SOURCE_LABELS.values())))
        self.assertNotIn("PRIVATE", json.dumps(report))

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



class JavacDiagnosticTest(unittest.TestCase):
    report = GradleDiagnosticTest.report
    SOURCE = b"app/src/test/java/com/subhub/app/FoundationTest.java"
    SOURCE_LABEL = "unit_foundation"

    def header(self, message=b"cannot find symbol", source=None, line=b"37"):
        return (self.SOURCE if source is None else source) + b":" + line + b": error: " + message + b"\n"

    def test_additive_schema_and_literal_allowlist_cover_all_tracked_unit_sources(self):
        _, result = self.report(b"")
        self.assertEqual(1, result["schema"])
        self.assertEqual([], result["javac"])
        self.assertEqual({"schema", "messagesOmitted", "truncated", "sanitizerFailed",
                          "exceptionSectionSeen", "unknownException", "exceptions", "resources",
                          "tasks", "lint", "lintSources", "javac"}, set(result))
        module = ast.parse((ROOT / "scripts/sanitize_gradle_failure.py").read_text(encoding="utf-8"))
        declaration = next(node.value for node in module.body if isinstance(node, ast.Assign)
                           and any(isinstance(target, ast.Name) and target.id == "JAVAC_SOURCES"
                                   for target in node.targets))
        self.assertIsInstance(declaration, ast.Dict)
        self.assertTrue(all(isinstance(node, ast.Constant) for node in declaration.keys + declaration.values))
        self.assertEqual(len(declaration.keys), len(safe.JAVAC_SOURCES))
        self.assertEqual(safe.JAVAC_SOURCES, ast.literal_eval(declaration))
        tracked = subprocess.check_output(["git", "ls-files", "-z", "app/src/test/**/*.java"], cwd=ROOT)
        self.assertEqual(set(tracked.rstrip(b"\0").split(b"\0")), set(safe.JAVAC_SOURCES))
        self.assertGreater(len(safe.JAVAC_SOURCES), 300)
        self.assertEqual(len(safe.JAVAC_SOURCES), len(set(safe.JAVAC_SOURCES.values())))
        for source, label in safe.JAVAC_SOURCES.items():
            self.assertTrue((ROOT / source.decode("ascii")).is_file())
            self.assertRegex(label, r"^unit_[a-z0-9_]{1,59}$")

    def test_production_integration_labels_are_literals_and_do_not_publish_messages(self):
        module = ast.parse((ROOT / "scripts/sanitize_gradle_failure.py").read_text(encoding="utf-8"))
        declaration = next(node.value for node in module.body if isinstance(node, ast.Assign)
                           and any(isinstance(target, ast.Name) and target.id == "JAVAC_MAIN_SOURCES"
                                   for target in node.targets))
        self.assertIsInstance(declaration, ast.Dict)
        self.assertEqual(safe.JAVAC_MAIN_SOURCES, ast.literal_eval(declaration))
        self.assertEqual(len(declaration.keys), len(safe.JAVAC_MAIN_SOURCES))
        self.assertTrue(all(isinstance(node, ast.Constant) for node in declaration.keys + declaration.values))
        tracked = subprocess.check_output(["git", "ls-files", "-z", "app/src/main/**/*.java"], cwd=ROOT)
        self.assertEqual(set(tracked.rstrip(b"\0").split(b"\0")), set(safe.JAVAC_MAIN_SOURCES))
        self.assertFalse(set(safe.JAVAC_MAIN_SOURCES) & set(safe.JAVAC_SOURCES))
        self.assertEqual(len(safe.JAVAC_MAIN_SOURCES), len(set(safe.JAVAC_MAIN_SOURCES.values())))
        for source, label in safe.JAVAC_MAIN_SOURCES.items():
            self.assertTrue((ROOT / source.decode("ascii")).is_file())
            self.assertRegex(label, r"^main_[a-z0-9_]{1,59}$")
            for prefix in (b"", b"/home/private/PRIVATE_TOKEN/"):
                _, result = self.report(self.header(b"incompatible types: PRIVATE_TOKEN cannot be converted to OtherPRIVATE", source=prefix+source))
                self.assertEqual([{"category": "incompatible_types", "source": label, "line": 37}], result["javac"])
            _, result = self.report(self.header(source=b"/home/private/../"+source))
            self.assertEqual([{"category": "cannot_find_symbol", "source": "unknown", "line": None}], result["javac"])

    def test_common_java_language_errors_have_fixed_categories_only(self):
        cases = [
            (b"local variables referenced from a lambda expression must be final or effectively final", "captured_local_not_final"),
            (b"variable PRIVATE_TOKEN might not have been initialized", "uninitialized_local"),
            (b"variable PRIVATE_TOKEN might already have been assigned", "reassigned_final"),
            (b"exception PRIVATE_TOKEN is never thrown in body of corresponding try statement", "unreachable_catch"),
            (b"Alternatives in a multi-catch statement cannot be related by subclassing", "related_multi_catch"),
            (b"PRIVATE_TOKEN in PRIVATE_CLASS cannot override PRIVATE_METHOD in PRIVATE_CLASS", "invalid_override"),
            (b"cannot infer type arguments for PRIVATE_TOKEN<>", "type_inference"),
            (b"invalid method reference", "invalid_method_reference"),
            (b"unexpected type", "unexpected_type"),
            (b"class PRIVATE_TOKEN is public, should be declared in a file named PRIVATE_TOKEN.java", "public_source_name"),
        ]
        for message, category in cases:
            _, result = self.report(self.header(message))
            self.assertEqual([{"category": category, "source": self.SOURCE_LABEL, "line": 37}], result["javac"])

    def test_every_fixed_source_maps_from_relative_and_linux_absolute_headers(self):
        for source, label in safe.JAVAC_SOURCES.items():
            for prefix in (b"", b"/home/private/PRIVATE_TOKEN/"):
                with self.subTest(source=source, prefix=prefix):
                    _, result = self.report(self.header(source=prefix + source))
                    self.assertEqual([{"category": "cannot_find_symbol", "source": label, "line": 37}],
                                     result["javac"])

    def test_representative_javac_error_categories_never_copy_messages_or_symbols(self):
        cases = (
            (b"local variables referenced from a lambda expression must be final or effectively final", "captured_local_not_final"),
            (b"variable PRIVATE_TOKEN might not have been initialized", "uninitialized_local"),
            (b"variable PRIVATE_TOKEN might already have been assigned", "reassigned_final"),
            (b"exception PRIVATE_TOKEN is never thrown in body of corresponding try statement", "unreachable_catch"),
            (b"Alternatives in a multi-catch statement cannot be related by subclassing", "related_multi_catch"),
            (b"PRIVATE_TOKEN in PRIVATE_CLASS cannot override PRIVATE_METHOD in PRIVATE_CLASS", "invalid_override"),
            (b"cannot infer type arguments for PRIVATE_TOKEN<>", "type_inference"),
            (b"invalid method reference", "invalid_method_reference"),
            (b"unexpected type", "unexpected_type"),
            (b"class PRIVATE_TOKEN is public, should be declared in a file named PRIVATE_TOKEN.java", "public_source_name"),
            (b"cannot find symbol", "cannot_find_symbol"),
            (b"package private.PRIVATE_TOKEN does not exist", "missing_package"),
            (b"incompatible types: PRIVATE_TOKEN cannot be converted to OtherPRIVATE", "incompatible_types"),
            (b"method PRIVATE_TOKEN in class OtherPRIVATE cannot be applied to given types;", "argument_mismatch"),
            (b"constructor PRIVATE_TOKEN in class OtherPRIVATE cannot be applied to given types;", "argument_mismatch"),
            (b"no suitable method found for PRIVATE_TOKEN(PRIVATE_TOKEN)", "no_suitable_overload"),
            (b"no suitable constructor found for PRIVATE_TOKEN(PRIVATE_TOKEN)", "no_suitable_overload"),
            (b"method does not override or implement a method from a supertype", "invalid_override"),
            (b"reference to PRIVATE_TOKEN is ambiguous", "ambiguous_reference"),
            (b"PRIVATE_TOKEN has private access in OtherPRIVATE", "inaccessible_member"),
            (b"PRIVATE_TOKEN has protected access in OtherPRIVATE", "inaccessible_member"),
            (b"PRIVATE_TOKEN is not public in OtherPRIVATE; cannot be accessed from outside package", "inaccessible_type"),
            (b"non-static method PRIVATE_TOKEN() cannot be referenced from a static context", "non_static_reference"),
            (b"unreported exception PRIVATE_TOKEN; must be caught or declared to be thrown", "unreported_exception"),
            (b"variable PRIVATE_TOKEN is already defined in method OtherPRIVATE()", "duplicate_definition"),
            (b"PRIVATE_TOKEN is not abstract and does not override abstract method OtherPRIVATE() in ThirdPRIVATE", "missing_implementation"),
            (b"PRIVATE_TOKEN is abstract; cannot be instantiated", "abstract_instantiation"),
            (b"cannot access PRIVATE_TOKEN", "cannot_access_class"),
            (b"missing return statement", "missing_return"),
            (b"illegal start of expression", "syntax"),
            (b"illegal start of type", "syntax"),
            (b"not a statement", "syntax"),
            (b"reached end of file while parsing", "syntax"),
            (b"';' expected", "syntax"),
            (b"')' expected", "syntax"),
            (b"'}' expected", "syntax"),
            (b"']' expected", "syntax"),
            (b"'(' expected", "syntax"),
            (b"'{' expected", "syntax"),
            (b"<identifier> expected", "syntax"),
        )
        self.assertEqual({label for _, label in safe.JAVAC_ERRORS}, {label for _, label in cases})
        for message, category in cases:
            with self.subTest(category=category, message=message):
                _, result = self.report(self.header(message))
                self.assertEqual([{"category": category, "source": self.SOURCE_LABEL, "line": 37}],
                                 result["javac"])

    def test_multiline_symbol_overload_and_bad_class_details_are_omitted(self):
        data = (b"> Task :app:compileDebugUnitTestJavaWithJavac\n"
                + self.header()
                + b"    PRIVATE_TOKEN.call(PRIVATE_TOKEN);\n                   ^\n"
                  b"  symbol:   method PRIVATE_TOKEN(PRIVATE_TOKEN)\n"
                  b"  location: variable PRIVATE_TOKEN of type PRIVATE_TOKEN\n"
                + self.header(b"method PRIVATE_TOKEN in class OtherPRIVATE cannot be applied to given types;", line=b"49")
                + b"    PRIVATE_TOKEN.call(PRIVATE_TOKEN);\n                 ^\n"
                  b"  required: PRIVATE_TOKEN\n  found:    OtherPRIVATE\n"
                  b"  reason: actual and formal argument lists differ in length\n"
                + self.header(b"cannot access PRIVATE_TOKEN", line=b"63")
                + b"  bad class file: /home/private/PRIVATE_TOKEN.class\n"
                  b"    class file has wrong version 65.0, should be 61.0\n"
                  b"    Please remove or make sure it appears in the correct subdirectory of the classpath.\n"
                  b"3 errors\n> Task :app:compileDebugUnitTestJavaWithJavac FAILED\n")
        _, result = self.report(data)
        self.assertEqual([{"category": category, "source": self.SOURCE_LABEL, "line": number}
                          for category, number in (("cannot_find_symbol", 37), ("argument_mismatch", 49),
                                                   ("cannot_access_class", 63))], result["javac"])
        self.assertEqual({"unit_java_compile": "failed"}, result["tasks"])

    def test_unknown_category_keeps_fixed_source_and_unknown_source_omits_line(self):
        for message in (PRIVATE, b"cannot find symbol PRIVATE_TOKEN", b"cannot find symbol\x1b[0m", b"", b"\xff\xfe"):
            _, result = self.report(self.header(message))
            self.assertEqual([{"category": "unknown", "source": self.SOURCE_LABEL, "line": 37}], result["javac"])
        _, result = self.report(self.header(source=b"/home/private/PRIVATE_TOKEN.java"))
        self.assertEqual([{"category": "cannot_find_symbol", "source": "unknown", "line": None}], result["javac"])
        _, result = self.report(self.header(PRIVATE, source=b"/home/private/PRIVATE_TOKEN.java"))
        self.assertEqual([{"category": "unknown", "source": "unknown", "line": None}], result["javac"])

    def test_paths_cannot_spoof_a_source_with_traversal_controls_urls_or_suffixes(self):
        for source in (
            b"PRIVATE_TOKEN" + self.SOURCE, b"./" + self.SOURCE, b"../" + self.SOURCE,
            b"/private/../" + self.SOURCE, b"/private/./" + self.SOURCE, b"/private//" + self.SOURCE,
            b"https://private.invalid/" + self.SOURCE, b"file:///private/" + self.SOURCE,
            b"C:\\private\\" + self.SOURCE, b"/private/\x00/" + self.SOURCE,
            b"/private/\t/" + self.SOURCE, b"/private/\x1b[0m/" + self.SOURCE,
            b"/private/\xff/" + self.SOURCE, b"/private/%2e%2e/" + self.SOURCE,
            b"/private/" + self.SOURCE + b".PRIVATE.java", self.SOURCE.replace(b"/test/", b"/main/"),
            self.SOURCE.replace(b"FoundationTest.java", b"PRIVATE_TOKEN.java"),
        ):
            with self.subTest(source=source):
                _, result = self.report(self.header(source=source))
                self.assertEqual([{"category": "cannot_find_symbol", "source": "unknown", "line": None}], result["javac"])

    def test_line_numbers_are_positive_bounded_and_never_attached_to_unknown_sources(self):
        for number, expected in ((b"1", 1), (b"999999", 999999), (b"0", None), (b"01", None),
                                 (b"1000000", None), (b"9" * 4000, None)):
            with self.subTest(length=len(number), expected=expected):
                _, result = self.report(self.header(line=number))
                self.assertEqual(expected, result["javac"][0]["line"])
                _, result = self.report(self.header(source=b"PRIVATE_TOKEN.java", line=number))
                self.assertIsNone(result["javac"][0]["line"])
        for number in (b"-1", b"+1", b"1.0", b"PRIVATE_TOKEN", b"", b" 1", b"\xff"):
            _, result = self.report(self.header(line=number))
            self.assertEqual([], result["javac"])

    def test_warnings_notes_indented_code_and_exception_section_do_not_become_javac_records(self):
        data = (self.header().replace(b": error: ", b": warning: ")
                + self.header().replace(b": error: ", b": Error: ")
                + self.header().replace(b": error: ", b": note: ")
                + b"    " + self.header() + b"\t" + self.header()
                + b"* Exception is:\n" + self.header()
                + b"java.lang.NullPointerException: " + PRIVATE + b"\n")
        _, result = self.report(data)
        self.assertEqual([], result["javac"])
        self.assertEqual(["null_pointer"], result["exceptions"])
        _, result = self.report(data + b"* Try:\n" + self.header())
        self.assertEqual(1, len(result["javac"]))

    def test_javac_byte_parser_handles_invalid_utf8_crlf_chunks_and_unterminated_line(self):
        data = (self.header(b"incompatible types: \xff\xfePRIVATE_TOKEN")
                + b"    PRIVATE_TOKEN\xff();\n         ^\n" + self.header(line=b"43").rstrip(b"\n"))
        data = data.replace(b"\n", b"\r\n")
        for size in (1, 7, safe.CHUNK_BYTES):
            with self.subTest(size=size), mock.patch.object(safe, "CHUNK_BYTES", size):
                code, result = self.report(data)
                self.assertEqual(0, code)
                self.assertEqual(["incompatible_types", "cannot_find_symbol"],
                                 [record["category"] for record in result["javac"]])
                self.assertFalse(result["truncated"])

    def test_overlong_headers_and_source_lines_are_discarded_before_later_errors(self):
        lines = (self.header(PRIVATE * safe.MAX_LINE_BYTES),
                 self.header(source=b"/" + b"x" * safe.MAX_LINE_BYTES + b"/" + self.SOURCE),
                 b"    " + PRIVATE * safe.MAX_LINE_BYTES + b"\n")
        for oversized in lines:
            with self.subTest(prefix=oversized[:20]):
                source = io.BytesIO(oversized + self.header())
                result = safe.summarize(source)
                self.assertEqual(len(source.getvalue()), source.tell())
                self.assertTrue(result["truncated"])
                self.assertEqual([{"category": "cannot_find_symbol", "source": self.SOURCE_LABEL, "line": 37}], result["javac"])
        _, result = self.report(self.header(PRIVATE * safe.MAX_LINE_BYTES).rstrip(b"\n"))
        self.assertTrue(result["truncated"])
        self.assertEqual([], result["javac"])

    def test_exact_line_byte_boundary_is_inclusive(self):
        prefix = self.header(b"").rstrip(b"\n")
        for delta in (0, 1):
            _, result = self.report(prefix + b"x" * (safe.MAX_LINE_BYTES - len(prefix) + delta) + b"\n")
            self.assertEqual(bool(delta), result["truncated"])
            self.assertEqual(0 if delta else 1, len(result["javac"]))

    def test_javac_limit_deduplicates_and_drains_without_losing_other_categories(self):
        data = self.header() * 50
        _, result = self.report(data)
        self.assertEqual(1, len(result["javac"]))
        self.assertFalse(result["truncated"])
        data += b"".join(self.header(line=str(i).encode("ascii")) for i in range(1, 50))
        data += (b"> Task :app:compileDebugUnitTestJavaWithJavac FAILED\n* Exception is:\n"
                 b"java.io.IOException: No space left on device\n")
        source = io.BytesIO(data)
        result = safe.summarize(source)
        self.assertEqual(len(data), source.tell())
        self.assertEqual(safe.MAX_JAVAC_DIAGNOSTICS, len(result["javac"]))
        self.assertTrue(result["truncated"])
        self.assertEqual(["io"], result["exceptions"])
        self.assertEqual(["no_space"], result["resources"])
        self.assertEqual({"unit_java_compile": "failed"}, result["tasks"])

    def test_javac_parser_fault_is_private_and_keeps_draining(self):
        source, output = io.BytesIO(self.header() + PRIVATE * safe.MAX_LINE_BYTES), io.StringIO()
        with mock.patch.object(safe.Diagnostics, "accept_javac", side_effect=ValueError("PRIVATE_TOKEN")):
            self.assertEqual(safe.FAILURE_EXIT, safe.emit(source, output))
        self.assertEqual(len(source.getvalue()), source.tell())
        self.assertNotIn("PRIVATE", output.getvalue())
        self.assertTrue(json.loads(output.getvalue())["gradleDiagnostic"]["sanitizerFailed"])

    def test_transient_read_fault_drains_and_returns_only_the_fixed_fallback(self):
        class FaultyReader(io.BytesIO):
            failed = False

            def read(self, size=-1):
                if not self.failed:
                    self.failed = True
                    raise UnicodeDecodeError("utf-8", b"PRIVATE_TOKEN", 0, 1, "PRIVATE_TOKEN")
                return super().read(size)

        source, output = FaultyReader(self.header()), io.StringIO()
        self.assertEqual(safe.FAILURE_EXIT, safe.emit(source, output))
        self.assertEqual(len(source.getvalue()), source.tell())
        self.assertEqual({"gradleDiagnostic": {"schema": 1, "messagesOmitted": True, "sanitizerFailed": True}},
                         json.loads(output.getvalue()))

    def test_output_fault_returns_failure_without_forwarding_error_text(self):
        output = mock.Mock()
        output.write.side_effect = ValueError("PRIVATE_TOKEN")
        source = io.BytesIO(self.header())
        self.assertEqual(safe.FAILURE_EXIT, safe.emit(source, output))
        self.assertEqual(len(source.getvalue()), source.tell())
        self.assertNotIn("PRIVATE", output.write.call_args.args[0])

    def test_worst_case_combined_summary_stays_small_with_javac_at_its_limit(self):
        data = b"\n".join(b"> Task " + task + b" UP-TO-DATE" for task in safe.TASKS)
        data += b"\n" + b"\n".join(path + b":1: Error: " + PRIVATE + b" [" + issue + b"]"
                                  for path in safe.LINT_SOURCES for issue in safe.LINT_ISSUES)
        sources = sorted(safe.JAVAC_SOURCES, key=lambda path: len(safe.JAVAC_SOURCES[path]), reverse=True)
        for path in sources[:safe.MAX_JAVAC_DIAGNOSTICS]:
            data += b"\n" + self.header(b"PRIVATE_TOKEN is not abstract and does not override abstract method PRIVATE_TOKEN()",
                                        source=path, line=b"999999")
        data += b"\n* Exception is:\n" + b"\n".join(name + b": " + PRIVATE for name in safe.EXCEPTIONS)
        data += b"\n" + b"\n".join(b"java.io.IOException: " + phrase for phrase in safe.RESOURCE_MESSAGES)
        code, result = self.report(data)
        self.assertEqual(0, code)
        self.assertEqual(safe.MAX_JAVAC_DIAGNOSTICS, len(result["javac"]))
        self.assertEqual(len(safe.TASKS), len(result["tasks"]))
        self.assertEqual(len(safe.LINT_SOURCES), len(result["lintSources"]))
        self.assertEqual(len(safe.RESOURCE_MESSAGES), len(result["resources"]))


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
                            "print('app/src/test/java/com/subhub/app/FoundationTest.java:37: error: cannot find symbol', flush=True)\n"
                            "print('    PRIVATE_TOKEN.call();\\n      ^\\n  symbol: PRIVATE_TOKEN', flush=True)\n"
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
                self.assertEqual([{"category": "cannot_find_symbol", "source": "unit_foundation", "line": 37}], result["javac"])
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
