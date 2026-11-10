"""Offline API35 permission-dump and exact external-state restoration regressions."""
import copy
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


if __name__ == "__main__":
    unittest.main()
