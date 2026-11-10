"""Offline native diagnostics, API35 permission-dump and restoration regressions."""
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from scripts import run_extra_time_native as native


PERMISSION = "android.permission.POST_NOTIFICATIONS"
# Synthetic minimal shape of run 42696888817, 078-permission-state.log:
# requested POST_NOTIFICATIONS, installed User 0, empty runtime section, then Queries.
# Original log SHA-256: f42669ee7afff008cac59f22213773350c974b2a3dd2e96a3017e117d7e3b0e8.
# No captures, APKs, or installation-specific paths are checked in.
FRESH_DUMP = """Packages:
  Package [com.subhub.app] (abc123):
    appId=10209
    versionCode=21 minSdk=26 targetSdk=35
    requested permissions:
      android.permission.POST_NOTIFICATIONS
      android.permission.INTERNET
      android.permission.RECORD_AUDIO
    install permissions:
      android.permission.INTERNET: granted=true
    User 0: installed=true hidden=false stopped=true notLaunched=true enabled=0
      gids=[3003]
      runtime permissions:

Queries:
  system apps queryable: false
"""


def with_runtime(*records):
    return FRESH_DUMP.replace("      runtime permissions:\n", "      runtime permissions:\n"
                              + "".join("        " + record + "\n" for record in records))


def permission_dump(granted, flags, omit_zero=True):
    if not granted and not flags and omit_zero:
        return FRESH_DUMP
    suffix = ", flags=[ " + "|".join(flags) + "]" if flags else ""
    return with_runtime(f"{PERMISSION}: granted={str(granted).lower()}{suffix}")


class NotificationPermissionStateTest(unittest.TestCase):
    def test_captured_fresh_install_shape_is_denied_without_flags(self):
        self.assertEqual({"granted": False, "flags": []}, native.notification_permission_state(FRESH_DUMP))

    def test_explicit_zero_flags_and_omitted_suffix_normalize_identically(self):
        for granted in (False, True):
            for suffix in ("", ", flags=[]", ", flags=[ ]"):
                with self.subTest(granted=granted, suffix=suffix):
                    text = with_runtime(f"{PERMISSION}: granted={str(granted).lower()}{suffix}")
                    self.assertEqual({"granted": granted, "flags": []}, native.notification_permission_state(text))

    def test_flags_are_retained_and_sorted_not_reduced_to_user_flags(self):
        flags = ["USER_SET", "USER_FIXED", "POLICY_FIXED", "USER_SENSITIVE_WHEN_DENIED"]
        for granted in (False, True):
            self.assertEqual({"granted": granted, "flags": sorted(flags)},
                             native.notification_permission_state(permission_dump(granted, flags)))

    def test_other_runtime_permission_does_not_imply_notification_grant(self):
        text = with_runtime("android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SET]")
        self.assertEqual({"granted": False, "flags": []}, native.notification_permission_state(text))

    def test_unknown_missing_uninstalled_foreign_or_shared_identity_is_refused(self):
        cases = ["", "Unable to find package: com.subhub.app\n", "Permission Denial: dumpsys\n",
                 FRESH_DUMP.replace("com.subhub.app", "com.other.app"),
                 FRESH_DUMP.replace("User 0:", "User 10:"),
                 FRESH_DUMP.replace("installed=true", "installed=false"),
                 FRESH_DUMP.replace("    appId=10209", "    sharedUser=SharedUserSetting{abc shared/10209}"),
                 FRESH_DUMP.replace("      " + PERMISSION + "\n", ""),
                 FRESH_DUMP.replace("    requested permissions:\n", "    unexpected permissions:\n"),
                 FRESH_DUMP.replace("      runtime permissions:\n", "")]
        for text in cases:
            with self.subTest(text=text), self.assertRaises(ValueError):
                native.notification_permission_state(text)

    def test_incomplete_dump_cannot_be_interpreted_as_zero_state(self):
        for text in (FRESH_DUMP.split("Queries:")[0],
                     FRESH_DUMP.replace("Queries:", "DUMP TIMEOUT"),
                     FRESH_DUMP.replace("      runtime permissions:", "      runtime permissions"),
                     with_runtime(f"{PERMISSION}: granted=")):
            with self.subTest(text=text), self.assertRaises(ValueError):
                native.notification_permission_state(text)

    def test_duplicate_package_user_section_permission_or_flags_is_refused(self):
        row = f"{PERMISSION}: granted=false, flags=[ USER_SET]"
        cases = [FRESH_DUMP + FRESH_DUMP,
                 FRESH_DUMP.replace("Queries:", "  Package [com.subhub.app] (def456):\nQueries:"),
                 FRESH_DUMP.replace("Queries:", "  Package [com.other.app] (def456):\nQueries:"),
                 FRESH_DUMP.replace("Queries:", "    User 10: installed=true\n      runtime permissions:\nQueries:"),
                 FRESH_DUMP.replace("Queries:", "    User 0: installed=true\n      runtime permissions:\nQueries:"),
                 FRESH_DUMP.replace("Queries:", "      runtime permissions:\nQueries:"),
                 FRESH_DUMP.replace("      " + PERMISSION + "\n", ("      " + PERMISSION + "\n") * 2),
                 with_runtime(row, row),
                 with_runtime(row, f"{PERMISSION}: granted=true"),
                 with_runtime(f"{PERMISSION}: granted=false, flags=[ USER_SET|USER_SET]")]
        for text in cases:
            with self.subTest(text=text), self.assertRaises(ValueError):
                native.notification_permission_state(text)

    def test_malformed_or_displaced_state_is_not_silently_ignored(self):
        rows = [f"{PERMISSION}: granted=unknown", f"{PERMISSION}: granted=False",
                f"{PERMISSION}: granted=false, flags=[ USER_SET", f"{PERMISSION}: granted=false, flags=[ USER_SET|]",
                f"{PERMISSION}: granted=false, flags=[ 0x1234]", f"{PERMISSION}: granted=false extra",
                "unexpected output", "android.permission.RECORD_AUDIO: granted=unknown"]
        cases = [with_runtime(row) for row in rows]
        cases += [FRESH_DUMP.replace("    install permissions:\n", "    install permissions:\n"
                                     + f"      {PERMISSION}: granted=true\n"),
                  FRESH_DUMP.replace("      gids=[3003]\n", f"      {PERMISSION}: granted=true\n")]
        for text in cases:
            with self.subTest(text=text), self.assertRaises(ValueError):
                native.notification_permission_state(text)


class ExternalStateRestoreTest(unittest.TestCase):
    def setUp(self):
        self.notification = {"granted": False, "flags": []}
        self.settings = {("secure", "enabled_accessibility_services"): "null",
                         ("secure", "accessibility_enabled"): "0", ("system", "font_scale"): "1.0"}
        self.producer = native.Producer.__new__(native.Producer)
        self.producer.record = {}
        self.producer.shell = mock.Mock(side_effect=self.shell)

    def shell(self, *args, **kwargs):
        if args == ("dumpsys", "package", "com.subhub.app"):
            return permission_dump(**self.notification)
        if args[:2] == ("settings", "get"):
            return self.settings[args[2:4]] + "\n"
        if args[:2] == ("settings", "delete"):
            self.settings[args[2:4]] = "null"
        elif args[:2] == ("settings", "put"):
            self.settings[args[2:4]] = "" if args[4] == "''" else args[4]
        elif args[:1] == ("pm",):
            if args[2:4] != ("com.subhub.app", PERMISSION):
                raise AssertionError(f"Unexpected permission target: {args}")
            if args[1] in ("grant", "revoke"):
                self.notification["granted"] = args[1] == "grant"
            elif args[1] in ("clear-permission-flags", "set-permission-flags"):
                flags = set(self.notification["flags"])
                for flag in args[4:]:
                    flag = flag.upper().replace("-", "_")
                    if args[1] == "set-permission-flags":
                        flags.add(flag)
                    else:
                        flags.discard(flag)
                self.notification["flags"] = sorted(flags)
            else:
                raise AssertionError(f"Unexpected permission mutation: {args}")
        else:
            raise AssertionError(f"Unexpected external operation: {args}")
        return ""

    def test_observer_is_read_only_and_preserves_normalized_schema(self):
        result = self.producer.external_state()
        self.assertEqual({"postNotifications": {"granted": False, "flags": []},
                          "fontScale": "1.0", "a11Enabled": "0", "a11Services": "null"}, result)
        self.assertEqual([("dumpsys", "package", "com.subhub.app"),
                          ("settings", "get", "secure", "enabled_accessibility_services"),
                          ("settings", "get", "secure", "accessibility_enabled"),
                          ("settings", "get", "system", "font_scale")],
                         [call.args for call in self.producer.shell.call_args_list])

    def test_restore_rechecks_exact_original_grant_all_flags_and_settings(self):
        for granted, flags, services in ((False, [], "null"), (False, ["USER_FIXED", "USER_SET"], ""),
                                         (True, [], "com.fixture/.Service")):
            with self.subTest(granted=granted, flags=flags, services=services):
                self.notification = {"granted": granted, "flags": flags}
                self.settings[("secure", "enabled_accessibility_services")] = services
                self.producer.original = self.producer.external_state()
                original = copy.deepcopy(self.producer.original)
                self.notification = {"granted": not granted, "flags": ["USER_SET"]}
                self.settings = {key: "changed" for key in self.settings}
                self.producer.restore()
                self.assertEqual(original, self.producer.record["finalExternalState"])
                self.assertEqual(original, self.producer.original)

    def test_unchanged_zero_state_does_not_mutate_notification_permission(self):
        self.producer.original = self.producer.external_state()
        self.producer.shell.reset_mock()
        self.producer.restore()
        self.assertFalse(any(call.args[0] == "pm" for call in self.producer.shell.call_args_list))

    def test_unrestored_non_user_flag_remains_a_failure(self):
        self.producer.original = self.producer.external_state()
        self.notification = {"granted": True, "flags": ["POLICY_FIXED", "USER_SET"]}
        with self.assertRaisesRegex(RuntimeError, "Exact font/accessibility/notification state"):
            self.producer.restore()
        self.assertEqual(["POLICY_FIXED"], self.producer.record["finalExternalState"]["postNotifications"]["flags"])

    def test_ambiguous_final_read_cannot_pass_restoration(self):
        self.producer.original = self.producer.external_state()
        original_shell = self.shell
        dumps = 0

        def shell(*args, **kwargs):
            nonlocal dumps
            if args == ("dumpsys", "package", "com.subhub.app"):
                dumps += 1
                if dumps == 2:
                    return FRESH_DUMP.split("Queries:")[0]
            return original_shell(*args, **kwargs)

        self.producer.shell.side_effect = shell
        with self.assertRaises(ValueError):
            self.producer.restore()
        self.assertNotIn("finalExternalState", self.producer.record)


class InstrumentationFailureDiagnosticsTest(unittest.TestCase):
    cls = "com.subhub.app.fixtures.SafeNativeTest"
    selector = cls + "#failsSafely"
    source = "app/src/androidTest/java/com/subhub/app/fixtures/SafeNativeTest.java"

    def setUp(self):
        self.inventory = {self.cls: {"path": self.source, "lineCount": 100}}

    def bundle(self, code, stack=None):
        output = (f"INSTRUMENTATION_STATUS: class={self.cls}\n"
                  "INSTRUMENTATION_STATUS: test=failsSafely\n"
                  "INSTRUMENTATION_STATUS: numtests=1\n"
                  "INSTRUMENTATION_STATUS: current=1\n")
        if stack is not None:
            output += "INSTRUMENTATION_STATUS: stack=" + stack + "\n"
        if code is not None:
            output += f"INSTRUMENTATION_STATUS_CODE: {code}\n"
        return output

    def failure(self, stack=None):
        if stack is None:
            stack = ("java.lang.AssertionError: PRIVATE_ASSERTION expected:<PRIVATE_EXPECTED> but was:<PRIVATE_ACTUAL>\n"
                     "\tat org.junit.Assert.assertEquals(Assert.java:12)\n"
                     f"\tat {self.cls}.helper(SafeNativeTest.java:42)\n"
                     f"\tat {self.cls}.failsSafely(SafeNativeTest.java:60)")
        return (self.bundle(1) + self.bundle(-2, stack)
                + "INSTRUMENTATION_RESULT: stream=\nFAILURES!!!\nTests run: 1, Failures: 1\n"
                + "INSTRUMENTATION_CODE: -1\n")

    def summary(self, text=None, code=0):
        return native.instrumentation_failure_summary(self.failure() if text is None else text,
                                                      self.selector, code, self.inventory)

    def test_failure_shows_only_known_exception_assertion_operation_and_source_locations(self):
        result = self.summary()
        self.assertTrue(result["protocolComplete"])
        self.assertEqual([1, -2], result["statusCodes"])
        self.assertEqual([-1], result["finalCodes"])
        self.assertEqual(["failure_summary"], result["failureMarkers"])
        self.assertEqual(["java.lang.AssertionError"], result["exceptions"])
        self.assertEqual(["org.junit.Assert.assertEquals"], result["assertionOperations"])
        self.assertEqual([{"path": self.source, "line": 42}, {"path": self.source, "line": 60}], result["sourceFrames"])
        self.assertTrue(result["messagesOmitted"])
        self.assertNotIn("PRIVATE_", json.dumps(result))

    def test_exception_causes_are_allowlisted_and_no_values_paths_or_stream_are_copied(self):
        stack = ("androidx.test.espresso.PerformException: PRIVATE_VIEW_TEXT\n"
                 "Caused by: java.lang.AssertionError: PRIVATE_TOKEN\n"
                 "Suppressed: private.PERSONAL_IDENTIFIERException: PRIVATE_PASSWORD\n"
                 "\tat private.PERSONAL_IDENTIFIERClass.method(SECRET_FILE.java:321)\n"
                 "\tat org.junit.Assert.PRIVATE_METHOD(Assert.java:12)\n"
                 f"\tat {self.cls}.helper(/private/path/SafeNativeTest.java:42)\n"
                 f"\tat {self.cls}.helper(SafeNativeTest.java:101)\n"
                 f"\tat {self.cls}.helper(SafeNativeTest.java:0)\n"
                 f"\tat {self.cls}.helper(Other.java:42)\n"
                 f"\tat {self.cls}$1.PRIVATE_METHOD(SafeNativeTest.java:42)")
        text = self.failure(stack).replace("FAILURES!!!", "PRIVATE_RAW_STREAM\nFAILURES!!!")
        result = self.summary(text)
        self.assertEqual(["androidx.test.espresso.PerformException", "java.lang.AssertionError", "other"], result["exceptions"])
        self.assertEqual([], result["assertionOperations"])
        self.assertEqual([{"path": self.source, "line": 42}], result["sourceFrames"])
        for sensitive in ("PRIVATE_", "PERSONAL_IDENTIFIER", "SECRET_FILE", "/private/path", "Other.java"):
            self.assertNotIn(sensitive, json.dumps(result))

    def test_foreign_ambiguous_or_wrong_count_bundles_do_not_attribute_stacks(self):
        cases = [self.failure().replace(f"class={self.cls}", "class=private.OtherTest"),
                 self.failure().replace("test=failsSafely", "test=otherMethod"),
                 self.failure().replace("numtests=1", "numtests=2"),
                 self.failure().replace("current=1", "current=2"),
                 self.failure().replace("INSTRUMENTATION_STATUS: stack=", "INSTRUMENTATION_STATUS: test=failsSafely\nINSTRUMENTATION_STATUS: stack=")]
        for text in cases:
            with self.subTest(text=text):
                result = self.summary(text)
                self.assertFalse(result["protocolComplete"])
                self.assertEqual([], result["exceptions"])
                self.assertEqual([], result["sourceFrames"])
                with self.assertRaises(ValueError):
                    native.instrumentation_result(text, self.selector, 0)

    def test_missing_or_unverified_inventory_omits_source_locations(self):
        for source in ({}, {"path": "private/path.java", "lineCount": 100},
                       {"path": self.source}, {"path": self.source, "lineCount": "100"}):
            with self.subTest(source=source):
                self.inventory[self.cls] = source
                self.assertEqual([], self.summary()["sourceFrames"])

    def test_incomplete_output_retains_safe_stack_but_never_claims_completion(self):
        text = self.failure().split("INSTRUMENTATION_STATUS_CODE: -2")[0]
        result = self.summary(text)
        self.assertFalse(result["protocolComplete"])
        self.assertEqual([1], result["statusCodes"])
        self.assertEqual(["java.lang.AssertionError"], result["exceptions"])
        self.assertEqual(2, len(result["sourceFrames"]))
        with self.assertRaises(ValueError):
            native.instrumentation_result(text, self.selector, 0)
        for text in ("", "INSTRUMENTATION_FAILED: PRIVATE_DETAILS\n",
                     self.failure().replace("INSTRUMENTATION_CODE: -1\n", "")):
            self.assertFalse(self.summary(text)["protocolComplete"])

    def test_unknown_status_numbers_are_not_copied_and_final_order_is_checked(self):
        text = self.failure().replace("INSTRUMENTATION_STATUS_CODE: -2", "INSTRUMENTATION_STATUS_CODE: 123456789")
        result = self.summary(text)
        self.assertEqual([1, "unknown"], result["statusCodes"])
        self.assertFalse(result["protocolComplete"])
        self.assertNotIn("123456789", json.dumps(result))
        for text in ("INSTRUMENTATION_CODE: -1\n" + self.failure(),
                     self.failure().replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 987654321")):
            self.assertFalse(self.summary(text)["protocolComplete"])
            self.assertNotIn("987654321", json.dumps(self.summary(text)))

    def test_framework_failure_markers_omit_private_details_and_remain_incomplete(self):
        for prefix, marker in (("INSTRUMENTATION_FAILED:", "failed"),
                               ("INSTRUMENTATION_ABORTED:", "aborted"),
                               ("INSTRUMENTATION_RESULT: shortMsg=", "short_message")):
            with self.subTest(marker=marker):
                result = self.summary(self.failure() + prefix + "PRIVATE_DETAILS\n")
                self.assertFalse(result["protocolComplete"])
                self.assertIn(marker, result["failureMarkers"])
                self.assertNotIn("PRIVATE_", json.dumps(result))

    def test_character_line_and_item_bounds_are_explicit_and_small(self):
        frames = "\n".join(f"\tat {self.cls}.helper(SafeNativeTest.java:{line})" for line in range(1, 101))
        cases = [self.failure(frames), self.failure() * (native.MAX_FAILURE_ITEMS + 1),
                 self.bundle(1) + "x" * (native.MAX_FAILURE_CHARS + 1),
                 "\n" * (native.MAX_FAILURE_LINES + 1) + self.failure(),
                 self.failure("java.lang.AssertionError: " + "x" * native.MAX_FAILURE_LINE)]
        for text in cases:
            with self.subTest(size=len(text)):
                result = self.summary(text)
                self.assertTrue(result["truncated"])
                self.assertFalse(result["protocolComplete"])
                for key in ("exceptions", "assertionOperations", "sourceFrames", "statusCodes", "finalCodes"):
                    self.assertLessEqual(len(result[key]), native.MAX_FAILURE_ITEMS)
                self.assertLess(len(json.dumps(result)), 4096)

    def test_success_parser_is_unchanged_and_diagnostic_completion_is_not_a_pass(self):
        passed = self.bundle(1) + self.bundle(0) + "OK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        self.assertEqual([1, 0], native.instrumentation_result(passed, self.selector, 0)["statusCodes"])
        for text, code in ((self.failure(), 0), (passed, 1), (passed.replace("OK (1 test)", ""), 0),
                           (passed.replace("STATUS_CODE: 0", "STATUS_CODE: -3"), 0)):
            with self.subTest(code=code), self.assertRaises(ValueError):
                native.instrumentation_result(text, self.selector, code)

    def producer(self, output):
        producer = native.Producer.__new__(native.Producer)
        producer.sdk, producer.serial, producer.run = Path("sdk"), "emulator-5554", "synthetic-run"
        producer.selected = [self.selector, self.cls + "#notRun"]
        producer.record = {"methods": {}, "remaining": list(producer.selected), "inventory": self.inventory}
        producer.save = mock.Mock()
        producer.settings = mock.Mock()
        producer.command = mock.Mock(return_value=output)
        return producer

    def test_failed_method_prints_safe_diagnostic_once_and_keeps_failure_and_remaining_work(self):
        producer = self.producer((self.failure(), 0))
        with contextlib.redirect_stdout(io.StringIO()) as output, self.assertRaises(ValueError):
            producer.run_tests()
        producer.command.assert_called_once()
        self.assertEqual([producer.selected[1]], producer.record["remaining"])
        self.assertEqual("failed", producer.record["methods"][self.selector]["state"])
        console = json.loads(output.getvalue())
        self.assertEqual("instrumentation_validation", console["failureDiagnostic"]["stage"])
        self.assertEqual(self.selector, console["method"])
        self.assertNotIn("PRIVATE_", output.getvalue())
        self.assertEqual(console["failureDiagnostic"], producer.record["methods"][self.selector]["failureDiagnostic"])

    def test_passed_method_console_and_receipt_are_unchanged(self):
        passed = self.bundle(1) + self.bundle(0) + "OK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        producer = self.producer((passed, 0))
        with contextlib.redirect_stdout(io.StringIO()) as output:
            producer.method(self.selector, 1)
        producer.command.assert_called_once()
        self.assertEqual({"method": self.selector, "state": "passed"}, json.loads(output.getvalue()))
        self.assertNotIn("failureDiagnostic", producer.record["methods"][self.selector])
        self.assertEqual([1, 0], producer.record["methods"][self.selector]["actualInstrumentation"]["statusCodes"])

    def test_command_timeout_does_not_copy_exception_message_or_retry(self):
        producer = self.producer(None)
        failure = TimeoutError("PRIVATE_TIMEOUT_DETAILS")
        producer.command.side_effect = failure
        with contextlib.redirect_stdout(io.StringIO()) as output, self.assertRaises(TimeoutError) as caught:
            producer.run_tests()
        self.assertIs(failure, caught.exception)
        producer.command.assert_called_once()
        diagnostic = json.loads(output.getvalue())["failureDiagnostic"]
        self.assertEqual("command", diagnostic["stage"])
        self.assertFalse(diagnostic["outputAvailable"])
        self.assertFalse(diagnostic["protocolComplete"])
        self.assertNotIn("PRIVATE_", output.getvalue())

    def test_diagnostic_failure_cannot_mask_original_failure(self):
        producer = self.producer((self.failure(), 0))
        with mock.patch.object(native, "instrumentation_failure_summary", side_effect=RuntimeError("PRIVATE_DETAILS")), \
                contextlib.redirect_stdout(io.StringIO()) as output, self.assertRaisesRegex(ValueError, "Instrumentation failed"):
            producer.method(self.selector, 1)
        self.assertNotIn("PRIVATE_", output.getvalue())
        self.assertFalse(json.loads(output.getvalue())["failureDiagnostic"]["outputAvailable"])

    def test_main_still_finishes_cleanup_and_exits_unsuccessfully_after_failure(self):
        producer = self.producer((self.failure(), 0))
        for name in ("source", "prepare_sdk", "build", "boot", "install"):
            setattr(producer, name, mock.Mock())
        producer.finish = mock.Mock()
        with tempfile.TemporaryDirectory() as folder:
            producer.out = Path(folder)
            with mock.patch.object(native, "Producer", return_value=producer), \
                    mock.patch.object(sys, "argv", ["run_extra_time_native.py"]), \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as caught:
                native.main()
            self.assertEqual(1, caught.exception.code)
            self.assertTrue((producer.out / "failure.txt").is_file())
        producer.finish.assert_called_once()
        producer.command.assert_called_once()
        self.assertEqual("failed_preserved", producer.record["state"])
        self.assertEqual([producer.selected[1]], producer.record["remaining"])


if __name__ == "__main__":
    unittest.main()
