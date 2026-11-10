"""Offline native suite admission/selection fixtures; no SDK, guest, or network."""
import contextlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import unittest
from unittest import mock

from scripts import run_extra_time_native as native


class NativeSuiteInventoryTest(unittest.TestCase):
    def setUp(self):
        # Unit fixtures must not consume the real Actions PR event/subset.
        event = mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "", "GITHUB_EVENT_PATH": ""})
        event.start()
        self.addCleanup(event.stop)

    def test_default_inventory_stays_at_the_original_21(self):
        self.assertEqual(native.selectors(""), native.ALL_SELECTORS)
        self.assertEqual(21, len(native.selectors("")))
        self.assertEqual({
            "ExtraTimeRequestAndroidTest": 4, "ExtraTimeTributeAndroidTest": 2,
            "ExtraTimeWireAndroidTest": 7, "ExtraTimeUiAndroidTest": 5,
            "TributeSettingsRestoreAndroidTest": 3,
        }, {cls.rsplit(".", 1)[1]: len(methods) for cls, methods in native.NATIVE.items()})
        self.assertEqual(native.LARGE_FONT, native.selectors("")[-1])

    def test_pass_adds_31_exact_methods_without_repeating_the_new_extra_time_case(self):
        selected = native.selectors("", "subhub-pass")
        self.assertEqual(52, len(selected))
        self.assertEqual(52, len(set(selected)))
        self.assertTrue(set(native.ALL_SELECTORS) < set(selected))
        self.assertEqual({
            "DurationChoicesAndroidTest": 2, "ServiceDurationRetentionAndroidTest": 3,
            "PersonRefreshAndroidTest": 3, "RemoteAppIconPresentationAndroidTest": 1,
            "RemoteConfigurationAndroidTest": 5, "RemoteDurationAndroidTest": 1,
            "RemoteEditorAndroidTest": 5, "RemoteSessionControlsAndroidTest": 3,
            "LazySessionDurationAndroidTest": 5, "PageScrollViewAndroidTest": 3,
        }, {cls.rsplit(".", 1)[1]: len(methods) for cls, methods in native.SUBHUB_PASS_NATIVE.items()})
        already_selected = "com.subhub.app.appmode.ExtraTimeUiAndroidTest#remoteRequestIconArrivesWithoutPollingAndRevocationClearsIt"
        self.assertEqual(1, selected.count(already_selected))
        self.assertEqual(native.ALL_SELECTORS[:-1], selected[:20])
        self.assertEqual(native.LARGE_FONT, selected[-1])

    def test_subsets_follow_suite_order_not_user_order(self):
        selected = native.suite_selectors("subhub-pass")
        raw = f" {selected[-1]} ,\n{selected[20]}\n{selected[0]}, "
        self.assertEqual([selected[0], selected[20], selected[-1]], native.selectors(raw, "subhub-pass"))

    def test_reconnect_selects_only_six_reviewed_contracts_and_keeps_original_suites(self):
        selected = native.selectors("", "reconnect")
        self.assertEqual(6, len(selected))
        self.assertEqual(6, len(set(selected)))
        self.assertEqual({"RemoteFeatureSchedulingAndroidTest": 2, "RemoteConfigurationAndroidTest": 4},
                         {cls.rsplit(".", 1)[1]: len(methods) for cls, methods in native.RECONNECT_NATIVE.items()})
        self.assertTrue(set(selected).isdisjoint(native.SUBHUB_PASS_SELECTORS))
        self.assertEqual([selected[0], selected[-1]], native.selectors(selected[-1] + "," + selected[0], "reconnect"))
        for cls, methods in native.RECONNECT_SOURCE_ONLY.items():
            for method in methods:
                with self.subTest(method=method), self.assertRaisesRegex(ValueError, "unique subset"):
                    native.selectors(f"{cls}#{method}", "reconnect")

    def test_reconnect_missing_or_duplicate_members_fail_closed(self):
        values = native.SUITES["reconnect"]
        for changed in (values[:-1], values[:-1] + [values[0]]):
            with mock.patch.dict(native.SUITES, {"reconnect": changed}), self.assertRaisesRegex(ValueError, "6 unique"):
                native.suite_selectors("reconnect")

    def test_battery_selects_all_scheduler_methods_real_responder_and_deadline(self):
        selected = native.selectors("", "battery")
        self.assertEqual(31, len(selected))
        self.assertEqual(31, len(set(selected)))
        self.assertEqual({"RemoteFeatureSchedulingAndroidTest": 29,
                          "RemoteBackgroundResponderAndroidTest": 1, "PairingLifecycleAndroidTest": 1},
                         {cls.rsplit(".", 1)[1]: len(methods) for cls, methods in native.BATTERY_NATIVE.items()})
        self.assertIn("com.subhub.app.remote.RemoteBackgroundResponderAndroidTest#delayedAuthenticatedInboundExchangesAfterOutgoingHasFinished", selected)
        self.assertIn("com.subhub.app.remote.PairingLifecycleAndroidTest#productionDeadlineProducerRunsWhileOtherWorkQueuesAreBlocked", selected)
        self.assertEqual([selected[0], selected[-1]], native.selectors(selected[-1] + "," + selected[0], "battery"))
        for cls, methods in native.BATTERY_SOURCE_ONLY.items():
            for method in methods:
                with self.subTest(method=method), self.assertRaisesRegex(ValueError, "unique subset"):
                    native.selectors(f"{cls}#{method}", "battery")

    def test_battery_missing_or_duplicate_members_fail_closed(self):
        values = native.SUITES["battery"]
        for changed in (values[:-1], values[:-1] + [values[0]]):
            with mock.patch.dict(native.SUITES, {"battery": changed}), self.assertRaisesRegex(ValueError, "31 unique"):
                native.suite_selectors("battery")

    def test_battery_selected_and_source_only_drift_fail_closed(self):
        for cls, method in (("com.subhub.app.remote.RemoteBackgroundResponderAndroidTest",
                             "delayedAuthenticatedInboundExchangesAfterOutgoingHasFinished"),
                            ("com.subhub.app.remote.PairingLifecycleAndroidTest",
                             "cheapLeaseDeadlinePublishesClosureWithoutSqlMutation")):
            with self.subTest(method=method), self.source_copy() as root:
                self.edit_source(root, cls, method, "unreviewedRenamedMethod")
                with self.assertRaisesRegex(ValueError, "Native inventory changed"):
                    native.source_inventory("battery")

    def test_device_pass_contains_battery_and_lifecycle_ui_without_duplicates(self):
        selected = native.suite_selectors("device-pass")
        self.assertEqual(54, len(selected))
        self.assertEqual(54, len(set(selected)))
        self.assertTrue(set(native.BATTERY_SELECTORS) < set(selected))
        self.assertEqual({"completedCounterTracksAcceptedOnlyAcrossRolesAndRecreation",
                          "domOverviewCompletedCounterRefreshesForAcceptedReviews",
                          "domTasksOverviewOpensTheSelectedProfileAndReturnsFromItsList"},
                         set(native.DEVICE_PASS_NATIVE["com.subhub.app.tasks.TasksAndroidTest"]))
        self.assertEqual(["local-session-app-time-active", "local-session-app-time-idle"],
                         native.UI_SCREENSHOTS["com.subhub.app.people.ProfileStatsActivityAndroidTest#profileAppTimeSharesSessionCardAcrossRefreshAndRecreation"])
        self.assertIn("com.subhub.app.journal.JournalUiAndroidTest#previewStopsPollingWhileHiddenAndRefreshesOnReturnAndReattachment", selected)
        values = native.SUITES["device-pass"]
        with mock.patch.dict(native.SUITES, {"device-pass": values[:-1]}), self.assertRaisesRegex(ValueError, "54 unique"):
            native.suite_selectors("device-pass")

    def test_profile_screenshots_accept_exact_or_configuration_suffix_but_not_failures(self):
        expected = ["local-session-app-time-active", "editor-avatar-action-row"]
        complete = [{"path": "synthetic-ui/run/local-session-app-time-active-1.0-411.png"},
                    {"path": "synthetic-ui/run/editor-avatar-action-row.png"}]
        self.assertEqual([], native.missing_screenshots(expected, complete))
        self.assertEqual(expected, native.missing_screenshots(expected, [
            {"path": "run/local-session-app-time-active-failure-42.png"},
            {"path": "run/editor-avatar-action-row.json"}]))

    def test_default_rejects_an_integration_method(self):
        with self.assertRaisesRegex(ValueError, "unique subset"):
            native.selectors(native.SUBHUB_PASS_SELECTORS[20])

    def test_manual_subsets_cannot_admit_classes_wildcards_or_unknown_methods(self):
        for suite in native.SUITES:
            for value in ("*", "com.subhub.app.remote.RemoteEditorAndroidTest", "class#*",
                          "class#method", "com.subhub.app.remote.RemoteFlowAndroidTest#domPairsEditsAppliesEndsAndQueuesWhileSubIsOffline"):
                with self.subTest(suite=suite, selector=value), self.assertRaisesRegex(ValueError, "unique subset"):
                    native.selectors(value, suite)

    def test_source_only_methods_are_not_supported_manual_selectors(self):
        for cls, methods in native.SUBHUB_PASS_SOURCE_ONLY.items():
            for method in methods:
                with self.subTest(selector=f"{cls}#{method}"), self.assertRaisesRegex(ValueError, "unique subset"):
                    native.selectors(f"{cls}#{method}", "subhub-pass")

    def test_duplicate_manual_selectors_fail_closed(self):
        value = native.SUBHUB_PASS_SELECTORS[20]
        with self.assertRaisesRegex(ValueError, "unique subset"):
            native.selectors(f"{value}, {value}", "subhub-pass")

    def test_unknown_suite_fails_closed(self):
        for suite in ("", "all", "SubHub-pass"):
            with self.subTest(suite=suite), self.assertRaisesRegex(ValueError, "Unknown native suite"):
                native.selectors("", suite)

    def test_missing_or_duplicate_suite_members_fail_admission(self):
        values = native.SUITES["subhub-pass"]
        for changed in (values[:-1], values[:-2] + [values[0], values[-1]]):
            with mock.patch.dict(native.SUITES, {"subhub-pass": changed}), self.assertRaisesRegex(ValueError, "52 unique"):
                native.suite_selectors("subhub-pass")

    def test_global_font_case_cannot_precede_other_cases(self):
        values = native.SUITES["subhub-pass"]
        with mock.patch.dict(native.SUITES, {"subhub-pass": values[-1:] + values[:-1]}):
            with self.assertRaisesRegex(ValueError, "large-font contract must run last"):
                native.suite_selectors("subhub-pass")

    def test_current_sources_reconcile_exactly_with_both_suites(self):
        for suite, count in native.SUITE_COUNTS.items():
            with self.subTest(suite=suite):
                inventory = native.source_inventory(suite)
                selected = set()
                for cls, item in inventory.items():
                    self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
                    self.assertEqual(set(item["methods"]), set(item["selectedMethods"] + item["sourceOnlyMethods"]))
                    selected.update(f"{cls}#{name}" for name in item["selectedMethods"])
                    for details in item["methods"].values():
                        self.assertGreater(details["line"], 0)
                self.assertEqual(count, len(selected))
                self.assertEqual(set(native.suite_selectors(suite)), selected)

    @contextlib.contextmanager
    def source_copy(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for cls in (*native.NATIVE, *native.SUBHUB_PASS_NATIVE, *native.RECONNECT_NATIVE, *native.BATTERY_NATIVE, *native.DEVICE_PASS_NATIVE):
                relative = Path("app/src/androidTest/java") / (cls.replace(".", "/") + ".java")
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(native.ROOT / relative, path)
            with mock.patch.object(native, "ROOT", root):
                yield root

    def edit_source(self, root, cls, before, after):
        path = root / "app/src/androidTest/java" / (cls.replace(".", "/") + ".java")
        source = path.read_text()
        self.assertIn(before, source)
        path.write_text(source.replace(before, after, 1))

    def test_missing_selected_method_fails_source_admission(self):
        cls = "com.subhub.app.commitment.DurationChoicesAndroidTest"
        method = native.SUBHUB_PASS_NATIVE[cls][0]
        with self.source_copy() as root:
            self.edit_source(root, cls, method, "renamedSelectedMethod")
            with self.assertRaisesRegex(ValueError, "Native inventory changed"):
                native.source_inventory("subhub-pass")

    def test_unreviewed_older_method_change_still_fails_source_admission(self):
        cls = "com.subhub.app.remote.RemoteDurationAndroidTest"
        method = native.SUBHUB_PASS_SOURCE_ONLY[cls][0]
        with self.source_copy() as root:
            self.edit_source(root, cls, method, "renamedSourceOnlyMethod")
            with self.assertRaisesRegex(ValueError, "Native inventory changed"):
                native.source_inventory("subhub-pass")

    def test_default_class_drift_guard_remains_strict(self):
        cls = next(iter(native.NATIVE))
        with self.source_copy() as root:
            self.edit_source(root, cls, native.NATIVE[cls][0], "renamedDefaultMethod")
            with self.assertRaisesRegex(ValueError, "Native inventory changed"):
                native.source_inventory()

    def test_duplicate_source_method_fails_admission(self):
        cls = "com.subhub.app.commitment.DurationChoicesAndroidTest"
        first, second = native.SUBHUB_PASS_NATIVE[cls]
        with self.source_copy() as root:
            self.edit_source(root, cls, second, first)
            with self.assertRaisesRegex(ValueError, "Duplicate native method"):
                native.source_inventory("subhub-pass")

    def test_wrong_source_package_fails_admission(self):
        cls = "com.subhub.app.commitment.DurationChoicesAndroidTest"
        with self.source_copy() as root:
            self.edit_source(root, cls, "package com.subhub.app.commitment;", "package wrong.package;")
            with self.assertRaisesRegex(ValueError, "Wrong selected native class"):
                native.source_inventory("subhub-pass")

    def test_selected_eligibility_and_fully_qualified_sdk_annotations(self):
        cls = "com.subhub.app.commitment.DurationChoicesAndroidTest"
        for annotation, accepted in (
            ("@androidx.test.filters.SdkSuppress(minSdkVersion = 29)", True),
            ("@SdkSuppress(minSdkVersion = 36)", False),
            ("@androidx.test.filters.SdkSuppress(maxSdkVersion = 34)", False),
            ("@RequiresApi(29)", False), ("@org.junit.Ignore", False),
        ):
            with self.subTest(annotation=annotation), self.source_copy() as root:
                self.edit_source(root, cls, "@Test public void", f"@Test {annotation} public void")
                if accepted:
                    self.assertIn(cls, native.source_inventory("subhub-pass"))
                else:
                    with self.assertRaisesRegex(ValueError, "not eligible at API 35"):
                        native.source_inventory("subhub-pass")

    def test_receipt_retains_suite_complete_selection_and_remaining_subset(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            selected = [native.SUBHUB_PASS_SELECTORS[20], native.LARGE_FONT]
            env = {"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
                   "RUNNER_TEMP": folder, "ANDROID_HOME": str(root / "sdk"),
                   "EXTRA_TIME_SELECTORS": ",".join(reversed(selected))}
            with mock.patch.dict(os.environ, env), mock.patch.object(native, "ROOT", root), mock.patch.object(sys, "platform", "linux"):
                producer = native.Producer("subhub-pass")
            receipt = json.loads((producer.out / "receipt.json").read_text())
            self.assertEqual("subhub-pass", receipt["suite"])
            self.assertEqual(native.SUBHUB_PASS_SELECTORS, receipt["suiteSelectors"])
            self.assertEqual(selected, receipt["selected"])
            self.assertEqual(selected, receipt["remaining"])
            self.assertEqual(50, len(receipt["notSelected"]))
            self.assertEqual(35 * 60, receipt["producerSoftTimeoutSeconds"])
            self.assertTrue(receipt["hostedFreshGuest"])

    def test_one_pass_invokes_each_selected_method_once_with_large_font_last(self):
        producer = native.Producer.__new__(native.Producer)
        producer.selected = native.suite_selectors("subhub-pass")
        producer.record = {}
        events = []
        producer.save = mock.Mock()
        producer.settings = lambda *args: events.append(("settings", *args))
        producer.method = lambda selector, index: events.append(("method", selector, index))
        producer.run_tests()
        methods = [value[1] for value in events if value[0] == "method"]
        self.assertEqual(producer.selected, methods)
        self.assertEqual(("settings", "system", "font_scale", "1.0"), events[0])
        self.assertEqual(("settings", "system", "font_scale", "1.5"), events[-2])
        self.assertEqual(("method", native.LARGE_FONT, 52), events[-1])
        self.assertEqual("selected_native_passed", producer.record["state"])

    def test_failed_method_is_not_retried_and_remaining_work_does_not_run(self):
        producer = native.Producer.__new__(native.Producer)
        producer.selected = native.suite_selectors("subhub-pass")
        producer.record = {}
        producer.save = mock.Mock()
        producer.settings = mock.Mock()
        producer.method = mock.Mock(side_effect=[None, RuntimeError("preserved failure")])
        with self.assertRaisesRegex(RuntimeError, "preserved failure"):
            producer.run_tests()
        self.assertEqual(2, producer.method.call_count)
        self.assertEqual("selected_native_running", producer.record["state"])

    def test_cli_check_uses_dispatch_environment_and_honors_exact_subset(self):
        selected = native.SUBHUB_PASS_SELECTORS[20]
        with mock.patch.dict(os.environ, {"EXTRA_TIME_SUITE": "subhub-pass", "EXTRA_TIME_SELECTORS": selected}), \
                mock.patch.object(sys, "argv", ["run_extra_time_native.py", "--check-inventory"]), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            native.main()
        value = json.loads(output.getvalue())
        self.assertEqual("subhub-pass", value["suite"])
        self.assertEqual([selected], value["selectors"])
        self.assertEqual(52, len(value["suiteSelectors"]))

    def test_cli_suite_argument_overrides_environment(self):
        with mock.patch.dict(os.environ, {"EXTRA_TIME_SUITE": "subhub-pass", "EXTRA_TIME_SELECTORS": ""}), \
                mock.patch.object(sys, "argv", ["run_extra_time_native.py", "--check-inventory", "--suite", "extra-time"]), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            native.main()
        self.assertEqual(21, len(json.loads(output.getvalue())["selectors"]))


class NativePrSubsetTest(unittest.TestCase):
    def setUp(self):
        event = mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "", "GITHUB_EVENT_PATH": "", "EXTRA_TIME_SELECTORS": ""})
        event.start()
        self.addCleanup(event.stop)
        self.chosen = [native.SUBHUB_PASS_SELECTORS[20], native.LARGE_FONT]

    def block(self, values=None):
        return native.PR_SUBSET_START + "\n" + json.dumps(self.chosen if values is None else values) + "\n" + native.PR_SUBSET_END

    @contextlib.contextmanager
    def event(self, body=None, labels=None, payload=None, raw=None, extra_selectors=""):
        if payload is None:
            payload = {"pull_request": {
                "body": self.block() if body is None else body,
                "labels": [{"name": name} for name in ([native.PR_SUBSET_LABEL] if labels is None else labels)],
            }}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "event.json"
            path.write_bytes(json.dumps(payload).encode() if raw is None else raw)
            with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": str(path),
                                             "EXTRA_TIME_SELECTORS": extra_selectors}):
                yield

    def test_valid_block_keeps_suite_order_and_ignores_surrounding_prose(self):
        body = "Description with `$(untrusted-command)`\n" + self.block(list(reversed(self.chosen))) + "\nMore notes"
        with self.event(body=body):
            selected, source = native.selected_methods("subhub-pass")
        self.assertEqual(self.chosen, selected)
        self.assertEqual("pull_request_body", source)

    def test_battery_pr_subset_uses_only_reviewed_methods_in_suite_order(self):
        chosen = [native.BATTERY_SELECTORS[0], native.BATTERY_SELECTORS[-1]]
        with self.event(body=self.block(list(reversed(chosen))), labels=[native.PR_SUBSET_LABEL, "native-battery"]):
            self.assertEqual((chosen, "pull_request_body"), native.selected_methods("battery"))
        cls = "com.subhub.app.remote.PairingLifecycleAndroidTest"
        excluded = f"{cls}#{native.BATTERY_SOURCE_ONLY[cls][0]}"
        with self.assertRaisesRegex(ValueError, "unique exact selectors"):
            native.pr_subset(self.block([excluded]), "battery")

    def test_subset_requires_the_exact_opt_in_label(self):
        for labels in ([], ["native-subset-typo"], ["Native-subset"], ["native-subhub-pass"]):
            with self.subTest(labels=labels), self.event(body="malformed selector block", labels=labels):
                selected, source = native.selected_methods("subhub-pass")
                self.assertEqual(native.SUBHUB_PASS_SELECTORS, selected)
                self.assertEqual("suite_default", source)

    def test_opt_in_body_overrides_legacy_environment_subset_only_when_labelled(self):
        legacy = native.ALL_SELECTORS[0]
        with self.event(labels=[native.PR_SUBSET_LABEL, "native-subhub-pass", "native-extra-time-ui"], extra_selectors=legacy):
            self.assertEqual((self.chosen, "pull_request_body"), native.selected_methods("subhub-pass"))
        with self.event(labels=["native-extra-time-ui"], extra_selectors=legacy):
            self.assertEqual(([legacy], "workflow_selectors"), native.selected_methods("subhub-pass"))

    def test_missing_duplicate_reversed_and_lookalike_markers_fail_closed(self):
        block = self.block()
        invalid = ["", "[]", native.PR_SUBSET_START, native.PR_SUBSET_END,
                   block + block, block + native.PR_SUBSET_START, block + native.PR_SUBSET_END,
                   native.PR_SUBSET_END + json.dumps(self.chosen) + native.PR_SUBSET_START,
                   block.replace("native-selectors:start", "native-selectors:begin"),
                   block.replace("<!-- native-selectors:start -->", "<!--native-selectors:start-->"),
                   block + "<!-- Native-selectors:extra -->"]
        for body in invalid:
            with self.subTest(body=body), self.assertRaises(ValueError):
                native.pr_subset(body, "subhub-pass")

    def test_only_a_nonempty_json_array_of_strings_is_accepted(self):
        for value in ([], {}, None, True, 12, "method", [1], [None], [{}], [[]]):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "nonempty JSON array"):
                native.pr_subset(native.PR_SUBSET_START + json.dumps(value) + native.PR_SUBSET_END, "subhub-pass")
        for raw in ("```json\n[]\n```", "['single-quotes']", '["trailing-comma",]', '[] []'):
            with self.subTest(raw=raw), self.assertRaisesRegex(ValueError, "JSON array"):
                native.pr_subset(native.PR_SUBSET_START + raw + native.PR_SUBSET_END, "subhub-pass")

    def test_duplicate_noncanonical_and_unknown_strings_cannot_expand_selection(self):
        for values in ([self.chosen[0], self.chosen[0]], [" " + self.chosen[0]],
                       [self.chosen[0] + "\n"], [",".join(self.chosen)],
                       ["com.subhub.app.remote.RemoteSessionControlsAndroidTest"], ["*"],
                       ["com.subhub.app.remote.RemoteFlowAndroidTest#domPairsEditsAppliesEndsAndQueuesWhileSubIsOffline"]):
            with self.subTest(values=values), self.assertRaisesRegex(ValueError, "unique exact selectors"):
                native.pr_subset(self.block(values), "subhub-pass")

    def test_command_expression_and_path_strings_are_only_rejected_data(self):
        with mock.patch.object(native.subprocess, "Popen") as spawn:
            for value in ("$(touch /tmp/invalid)", "${{ github.token }}", "../file", "method; echo unsafe", "method\u0000"):
                with self.subTest(value=value), self.assertRaises(ValueError):
                    native.pr_subset(self.block([value]), "subhub-pass")
            spawn.assert_not_called()

    def test_default_suite_cannot_admit_an_integration_only_method(self):
        with self.event(), self.assertRaisesRegex(ValueError, "selected suite"):
            native.selected_methods("extra-time")

    def test_source_only_methods_remain_outside_pr_allowlist(self):
        cls = next(iter(native.SUBHUB_PASS_SOURCE_ONLY))
        value = f"{cls}#{native.SUBHUB_PASS_SOURCE_ONLY[cls][0]}"
        with self.assertRaisesRegex(ValueError, "unique exact selectors"):
            native.pr_subset(self.block([value]), "subhub-pass")

    def test_selector_and_body_size_bounds_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "nonempty JSON array"):
            native.pr_subset(self.block([self.chosen[0]] * 53), "subhub-pass")
        for body in (None, 12, self.block() + "x" * native.MAX_PR_BODY_BYTES,
                     self.block() + "é" * (native.MAX_PR_BODY_BYTES // 2)):
            with self.subTest(type=type(body)), self.assertRaisesRegex(ValueError, "bounded PR body"):
                native.pr_subset(body, "subhub-pass")

    def test_invalid_missing_or_oversize_event_is_rejected(self):
        for raw in (b"{", b"\xff", b"x" * (native.MAX_EVENT_BYTES + 1)):
            with self.subTest(size=len(raw)), self.event(raw=raw), self.assertRaises(ValueError):
                native.selected_methods("subhub-pass")
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": ""}):
            with self.assertRaisesRegex(ValueError, "GITHUB_EVENT_PATH"):
                native.selected_methods("subhub-pass")
        for payload in ([], {}, {"pull_request": []}, {"pull_request": {"labels": "native-subset"}},
                        {"pull_request": {"labels": ["native-subset"]}}, {"pull_request": {"labels": [{}]}}):
            with self.subTest(payload=payload), self.event(payload=payload), self.assertRaisesRegex(ValueError, "label list"):
                native.selected_methods("subhub-pass")

    def test_dispatch_does_not_read_pr_body_or_change_its_existing_inputs(self):
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": "/must-not-open",
                                         "EXTRA_TIME_SELECTORS": self.chosen[0]}), mock.patch.object(Path, "open") as reader:
            self.assertEqual(([self.chosen[0]], "workflow_selectors"), native.selected_methods("subhub-pass"))
            reader.assert_not_called()
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "EXTRA_TIME_SELECTORS": ""}):
            self.assertEqual((native.ALL_SELECTORS, "suite_default"), native.selected_methods("extra-time"))

    def test_invalid_labelled_subset_is_rejected_before_producer_output_or_build(self):
        with tempfile.TemporaryDirectory() as folder, self.event(body="malformed"):
            root = Path(folder)
            with mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), mock.patch.object(sys, "platform", "linux"), \
                    mock.patch.object(native, "ROOT", root), mock.patch.object(native.Producer, "build") as build:
                with self.assertRaises(ValueError):
                    native.Producer("subhub-pass")
                self.assertEqual([], list(root.iterdir()))
                build.assert_not_called()

    def test_subset_receipt_is_explicit_and_does_not_claim_the_whole_suite_passed(self):
        with tempfile.TemporaryDirectory() as folder, self.event():
            root = Path(folder)
            env = {"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
                   "RUNNER_TEMP": folder, "ANDROID_HOME": str(root / "sdk")}
            with mock.patch.dict(os.environ, env), mock.patch.object(native, "ROOT", root), mock.patch.object(sys, "platform", "linux"):
                producer = native.Producer("subhub-pass")
            receipt = json.loads((producer.out / "receipt.json").read_text())
            self.assertEqual("pull_request_body", receipt["selectionSource"])
            self.assertEqual("subhub-pass", receipt["suite"])
            self.assertEqual(self.chosen, receipt["selected"])
            self.assertEqual(self.chosen, receipt["remaining"])
            self.assertEqual(50, len(receipt["notSelected"]))
            self.assertEqual(52, len(receipt["suiteSelectors"]))
            self.assertEqual("admission", receipt["state"])
            self.assertEqual({}, receipt["methods"])

    def test_cli_inventory_check_previews_the_same_labelled_selection(self):
        with self.event(), mock.patch.object(sys, "argv", ["run_extra_time_native.py", "--suite", "subhub-pass", "--check-inventory"]), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            native.main()
        receipt = json.loads(output.getvalue())
        self.assertEqual(self.chosen, receipt["selectors"])
        self.assertEqual("pull_request_body", receipt["selectionSource"])


class NativeWorkflowSelectionTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (native.ROOT / ".github/workflows/extra-time-native.yml").read_text()

    def expression(self, name):
        match = re.search(r"^      " + name + r": \$\{\{ (.+) \}\}$", self.workflow, re.MULTILINE)
        self.assertIsNotNone(match)
        return match[1]

    def evaluate(self, expression, event="pull_request", labels=(), suite="", selectors=""):
        # The workflow's small supported expression vocabulary, exercised directly
        # from YAML so label precedence cannot silently diverge from these fixtures.
        replacements = {
            "github.event.pull_request.labels.*.name": "labels",
            "github.event_name": "event", "inputs.suite": "suite", "inputs.selectors": "selectors",
            "&&": " and ", "||": " or ", "!": " not ",
        }
        for before, after in replacements.items():
            expression = expression.replace(before, after)
        return eval(expression, {"__builtins__": {}}, {
            "contains": lambda values, item: item in values,
            "event": event, "labels": labels, "suite": suite, "selectors": selectors,
        })

    def test_plain_pr_preserves_default_full_21(self):
        suite = self.evaluate(self.expression("EXTRA_TIME_SUITE"))
        raw = self.evaluate(self.expression("EXTRA_TIME_SELECTORS"))
        self.assertEqual("extra-time", suite)
        self.assertEqual(21, len(native.selectors(raw, suite)))

    def test_integration_label_runs_52_even_with_both_legacy_labels(self):
        for labels in (("native-subhub-pass",), ("native-subhub-pass", "native-extra-time-ui", "native-extra-time-tribute-large")):
            with self.subTest(labels=labels):
                suite = self.evaluate(self.expression("EXTRA_TIME_SUITE"), labels=labels)
                raw = self.evaluate(self.expression("EXTRA_TIME_SELECTORS"), labels=labels)
                self.assertEqual("subhub-pass", suite)
                self.assertEqual("", raw)
                self.assertEqual(52, len(native.selectors(raw, suite)))

    def test_legacy_subset_labels_keep_original_counts(self):
        for label, count in (("native-extra-time-ui", 10), ("native-extra-time-tribute-large", 2)):
            suite = self.evaluate(self.expression("EXTRA_TIME_SUITE"), labels=(label,))
            raw = self.evaluate(self.expression("EXTRA_TIME_SELECTORS"), labels=(label,))
            self.assertEqual("extra-time", suite)
            self.assertEqual(count, len(native.selectors(raw, suite)))

    def test_reconnect_label_runs_only_six_even_with_older_suite_labels(self):
        for labels in (("native-reconnect",), ("native-reconnect", "native-subhub-pass", "native-extra-time-ui")):
            with self.subTest(labels=labels):
                suite = self.evaluate(self.expression("EXTRA_TIME_SUITE"), labels=labels)
                raw = self.evaluate(self.expression("EXTRA_TIME_SELECTORS"), labels=labels)
                self.assertEqual("reconnect", suite)
                self.assertEqual("", raw)
                self.assertEqual(6, len(native.selectors(raw, suite)))

    def test_battery_label_wins_over_all_older_suite_and_subset_labels(self):
        for labels in (("native-battery",), ("native-battery", "native-reconnect", "native-subhub-pass",
                                            "native-extra-time-ui", "native-extra-time-tribute-large")):
            with self.subTest(labels=labels):
                suite = self.evaluate(self.expression("EXTRA_TIME_SUITE"), labels=labels)
                raw = self.evaluate(self.expression("EXTRA_TIME_SELECTORS"), labels=labels)
                self.assertEqual("battery", suite)
                self.assertEqual("", raw)
                self.assertEqual(31, len(native.selectors(raw, suite)))

    def test_device_pass_label_precedes_existing_suites_and_supports_dispatch(self):
        labels = ("native-device-pass", "native-battery", "native-reconnect", "native-extra-time-ui")
        self.assertEqual("device-pass", self.evaluate(self.expression("EXTRA_TIME_SUITE"), labels=labels))
        self.assertEqual("", self.evaluate(self.expression("EXTRA_TIME_SELECTORS"), labels=labels))
        self.assertEqual(54, len(native.selectors("", "device-pass")))
        for raw, count in (("", 54), (native.DEVICE_PASS_SELECTORS[-1], 1)):
            args = dict(event="workflow_dispatch", suite="device-pass", selectors=raw)
            suite = self.evaluate(self.expression("EXTRA_TIME_SUITE"), **args)
            selected = self.evaluate(self.expression("EXTRA_TIME_SELECTORS"), **args)
            self.assertEqual(count, len(native.selectors(selected, suite)))

    def test_dispatch_battery_supports_full_suite_and_exact_subset(self):
        for raw, count in (("", 31), (native.BATTERY_SELECTORS[-1], 1)):
            args = dict(event="workflow_dispatch", suite="battery", selectors=raw)
            suite = self.evaluate(self.expression("EXTRA_TIME_SUITE"), **args)
            selected = self.evaluate(self.expression("EXTRA_TIME_SELECTORS"), **args)
            self.assertEqual(count, len(native.selectors(selected, suite)))

    def test_dispatch_reconnect_supports_full_suite_and_exact_subset(self):
        for raw, count in (("", 6), (native.RECONNECT_SELECTORS[0], 1)):
            args = dict(event="workflow_dispatch", suite="reconnect", selectors=raw)
            suite = self.evaluate(self.expression("EXTRA_TIME_SUITE"), **args)
            selected = self.evaluate(self.expression("EXTRA_TIME_SELECTORS"), **args)
            self.assertEqual(count, len(native.selectors(selected, suite)))

    def test_dispatch_pass_supports_full_suite_and_exact_subset(self):
        for raw, count in (("", 52), (native.SUBHUB_PASS_SELECTORS[20], 1)):
            args = dict(event="workflow_dispatch", suite="subhub-pass", selectors=raw)
            suite = self.evaluate(self.expression("EXTRA_TIME_SUITE"), **args)
            selected = self.evaluate(self.expression("EXTRA_TIME_SELECTORS"), **args)
            self.assertEqual(count, len(native.selectors(selected, suite)))

    def test_only_one_job_and_no_label_push_schedule_or_dispatch_side_effect(self):
        self.assertEqual(["native"], re.findall(r"^  ([a-zA-Z_-]+):$", self.workflow.split("jobs:\n", 1)[1], re.MULTILINE))
        self.assertNotIn("matrix:", self.workflow)
        self.assertNotIn("github.event.pull_request.body", self.workflow)
        self.assertNotIn("native-manual-only", self.workflow)
        self.assertNotRegex(self.workflow, r"(?m)^    if:")  # No job gate silently skips native qualification.
        trigger = self.workflow.split("on:\n", 1)[1].split("permissions:\n", 1)[0]
        self.assertNotIn("labeled", trigger)
        self.assertNotIn("types:", trigger)  # Retain the original PR default event set.
        self.assertNotRegex(trigger, r"(?m)^  (push|schedule|workflow_run|pull_request_target):")
        self.assertIn("options: [extra-time, subhub-pass, reconnect, battery, device-pass]", trigger)
        self.assertIn("timeout-minutes: 45", self.workflow)
        self.assertEqual(1, self.workflow.count("run: python3 scripts/run_extra_time_native.py"))
        self.assertRegex(self.workflow, r"run: python3 -m unittest [^\n]*\bscripts\.test_extra_time_native_inventory\b")


if __name__ == "__main__":
    unittest.main()
