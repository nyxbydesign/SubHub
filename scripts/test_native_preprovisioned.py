"""Offline preprovisioned-host admission tests. Never run SDK tools or a guest."""
import contextlib
import inspect
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from scripts import run_extra_time_native as native


HOST = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_ARCH": "X64"}
FORBIDDEN = {"sudo", "setfacl", "chmod", "udevadm", "apt", "apt-get", "--licenses", "--install", "--update"}


class PreprovisionedHostTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.sdk = Path(temporary.name) / "sdk"
        self.executables = ["platform-tools/adb", "emulator/emulator", "emulator/qemu/linux-x86_64/qemu-system-x86_64",
                            "build-tools/35.0.0/aapt", "build-tools/35.0.0/apksigner",
                            "cmdline-tools/latest/bin/sdkmanager", "cmdline-tools/latest/bin/avdmanager"]
        self.data = ["platforms/android-35/android.jar", "system-images/android-35/google_apis/x86_64/system.img",
                     "system-images/android-35/google_apis/x86_64/userdata.img"]
        for relative in self.executables + self.data:
            self.write(relative, "synthetic, never executed")
        self.metadata = {
            "platform-tools/source.properties": "Pkg.Revision=35.0.2\n",
            "emulator/source.properties": "Pkg.Revision=35.4.9\n",
            "build-tools/35.0.0/source.properties": "Pkg.Revision=35.0.0\n",
            "platforms/android-35/source.properties": "Pkg.Revision=2\nAndroidVersion.ApiLevel=35\n",
            "system-images/android-35/google_apis/x86_64/source.properties":
                "Pkg.Revision=12\nAndroidVersion.ApiLevel=35\nSystemImage.Abi=x86_64\nSystemImage.TagId=google_apis\n",
            "cmdline-tools/latest/source.properties": "Pkg.Revision=12.0\n",
        }
        for relative, value in self.metadata.items():
            self.write(relative, value)
        patches = [mock.patch.dict(os.environ, {**HOST, "ANDROID_HOME": str(self.sdk)}, clear=True),
                   mock.patch.object(sys, "platform", "linux"),
                   mock.patch.object(Path, "is_char_device", return_value=True),
                   mock.patch.object(os, "access", return_value=True)]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def write(self, relative, value):
        path = self.sdk / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
        return path

    @contextlib.contextmanager
    def no_side_effects(self):
        original_open = Path.open
        def read_only_open(path, mode="r", *args, **kwargs):
            if any(flag in mode for flag in "wax+"):
                raise AssertionError("write-mode open forbidden")
            return original_open(path, mode, *args, **kwargs)
        with mock.patch.object(Path, "open", read_only_open), \
                mock.patch.object(native.subprocess, "Popen", side_effect=AssertionError("child process forbidden")), \
                mock.patch.object(Path, "mkdir", side_effect=AssertionError("mkdir forbidden")), \
                mock.patch.object(Path, "write_text", side_effect=AssertionError("write forbidden")), \
                mock.patch.object(Path, "write_bytes", side_effect=AssertionError("write forbidden")), \
                mock.patch.object(native.socket, "socket", side_effect=AssertionError("socket forbidden")):
            yield

    def report(self):
        with self.no_side_effects():
            return native.preprovisioned_host_report()

    def producer(self):
        producer = native.Producer.__new__(native.Producer)
        producer.sdk = self.sdk
        producer.env = {"LD_LIBRARY_PATH": "/already/installed"}
        producer.record = {}
        producer.save = mock.Mock()
        producer.resource_guard = mock.Mock()
        producer.command = mock.Mock(return_value=("installed-version\n", 0))
        return producer

    def assert_safe_commands(self, producer):
        for call in producer.command.call_args_list:
            arguments = [str(value) for value in call.args[0]]
            self.assertFalse(FORBIDDEN.intersection(arguments), arguments)
            self.assertNotIn("install", arguments)

    def test_complete_report_uses_only_existing_capabilities_and_no_side_effects(self):
        report = self.report()
        self.assertEqual([], report["missing"])
        self.assertEqual(6, len(report["packages"]))
        self.assertTrue(report["preprovisionedOnly"])
        self.assertFalse(report["runtimeVerified"])
        self.assertEqual({"characterDevice": True, "readable": True, "writable": True}, report["kvm"])

    def test_host_identity_and_each_kvm_permission_fail_closed_without_repair(self):
        for key, value in (("GITHUB_ACTIONS", "false"), ("RUNNER_ENVIRONMENT", "self-hosted"), ("RUNNER_ARCH", "ARM64")):
            with self.subTest(key=key), mock.patch.dict(os.environ, {key: value}):
                self.assertIn("github-hosted-linux-x64", self.report()["missing"])
        with mock.patch.object(sys, "platform", "win32"):
            self.assertIn("github-hosted-linux-x64", self.report()["missing"])
        with mock.patch.object(Path, "is_char_device", return_value=False):
            self.assertIn("existing-readable-writable-kvm", self.report()["missing"])
        for mode in (os.R_OK, os.W_OK):
            with self.subTest(mode=mode), mock.patch.object(os, "access", side_effect=lambda path, requested: not (Path(path) == Path('/dev/kvm') and requested == mode)):
                self.assertIn("existing-readable-writable-kvm", self.report()["missing"])

    def test_missing_sdk_does_not_create_or_install_it(self):
        for value in ("", str(self.sdk / "not-installed")):
            with mock.patch.dict(os.environ, {"ANDROID_HOME": value, "ANDROID_SDK_ROOT": ""}):
                self.assertIn("installed-android-sdk", self.report()["missing"])
        self.assertFalse((self.sdk / "not-installed").exists())

    def test_each_required_file_is_admitted_individually(self):
        for relative in self.executables + self.data:
            path = self.sdk / relative
            path.unlink()
            try:
                with self.subTest(relative=relative):
                    self.assertTrue(self.report()["missing"])
            finally:
                self.write(relative, "synthetic, never executed")
        for relative in self.executables + self.data:
            with self.subTest(permission=relative), mock.patch.object(os, "access", side_effect=lambda path, mode: Path(path) != self.sdk / relative):
                self.assertTrue(self.report()["missing"])

    def test_each_metadata_file_missing_malformed_or_oversized_fails_closed(self):
        for relative, original in self.metadata.items():
            path = self.sdk / relative
            for value in (None, "Pkg.Revision=\n", "Pkg.Revision=1\nPkg.Revision=2\n", "not a property\n", "x" * (64 * 1024 + 1)):
                if value is None:
                    path.unlink()
                else:
                    path.write_text(value)
                with self.subTest(relative=relative, value=value and value[:25]):
                    self.assertIn("metadata:" + relative, self.report()["missing"])
                path.write_text(original)

    def test_wrong_api_abi_image_tag_or_exact_build_tools_revision_is_refused(self):
        changes = [
            ("platforms/android-35/source.properties", "ApiLevel=35", "ApiLevel=34"),
            ("build-tools/35.0.0/source.properties", "Revision=35.0.0", "Revision=35.0.1"),
            ("system-images/android-35/google_apis/x86_64/source.properties", "ApiLevel=35", "ApiLevel=36"),
            ("system-images/android-35/google_apis/x86_64/source.properties", "Abi=x86_64", "Abi=arm64-v8a"),
            ("system-images/android-35/google_apis/x86_64/source.properties", "TagId=google_apis", "TagId=google_apis_playstore"),
        ]
        for relative, before, after in changes:
            original = self.metadata[relative]
            self.write(relative, original.replace(before, after))
            with self.subTest(after=after):
                self.assertIn("metadata:" + relative, self.report()["missing"])
            self.write(relative, original)

    def test_prepare_records_missing_capabilities_before_any_tool_invocation(self):
        producer = self.producer()
        (self.sdk / "emulator/emulator").unlink()
        with self.assertRaisesRegex(RuntimeError, "Preprovisioned native host is incomplete"):
            producer.prepare_sdk()
        producer.command.assert_not_called()
        self.assertIn("executable:emulator/emulator", producer.record["hostPreflight"]["missing"])
        producer.save.assert_called_once()

    def test_complete_prepare_only_inspects_versions_loader_and_acceleration(self):
        producer = self.producer()
        with mock.patch.object(native.shutil, "which", return_value="/usr/bin/ldd"):
            producer.prepare_sdk()
        self.assert_safe_commands(producer)
        calls = [[str(v) for v in call.args[0]] for call in producer.command.call_args_list]
        self.assertEqual(["--version", "version", str(self.sdk / 'emulator/emulator'),
                          str(self.sdk / 'emulator/qemu/linux-x86_64/qemu-system-x86_64'), "-accel-check", "-version"],
                         [call[-1] for call in calls])
        self.assertEqual({"LD_LIBRARY_PATH": "/already/installed"}, producer.env)
        self.assertEqual([], producer.record["emulatorRuntime"]["packagesRequested"])

    def test_any_missing_loader_library_fails_without_apt_and_restores_environment(self):
        for library in ("libpulse.so.0", "unknown-library.so"):
            producer = self.producer()
            producer.command.return_value = (library + " => not found\n", 0)
            with self.subTest(library=library), mock.patch.object(native.shutil, "which", return_value="/usr/bin/ldd"), \
                    self.assertRaisesRegex(RuntimeError, "Preprovisioned emulator host dependencies"):
                producer.emulator_runtime()
            self.assertEqual(2, producer.command.call_count)
            self.assert_safe_commands(producer)
            self.assertEqual({"LD_LIBRARY_PATH": "/already/installed"}, producer.env)
            self.assertEqual([], producer.record["emulatorRuntime"]["packagesRequested"])
        producer = self.producer()
        producer.command.return_value = ("not dynamic", 1)
        with mock.patch.object(native.shutil, "which", return_value="/usr/bin/ldd"), self.assertRaisesRegex(RuntimeError, "loader inspection failed"):
            producer.emulator_runtime()
        self.assertEqual({"LD_LIBRARY_PATH": "/already/installed"}, producer.env)
        self.assert_safe_commands(producer)

    def test_missing_ldd_or_failed_acceleration_does_not_trigger_setup_or_fallback(self):
        producer = self.producer()
        with mock.patch.object(native.shutil, "which", return_value=None), self.assertRaises(RuntimeError):
            producer.prepare_sdk()
        self.assert_safe_commands(producer)
        producer = self.producer()
        def command(args, **kwargs):
            if str(args[-1]) == "-accel-check":
                raise RuntimeError("acceleration unavailable")
            return "installed-version\n", 0
        producer.command.side_effect = command
        with mock.patch.object(native.shutil, "which", return_value="/usr/bin/ldd"), self.assertRaisesRegex(RuntimeError, "acceleration unavailable"):
            producer.prepare_sdk()
        self.assert_safe_commands(producer)
        self.assertFalse(any("-accel" in call.args[0] for call in producer.command.call_args_list))

    def test_build_disables_sdk_auto_download_before_any_build_result(self):
        producer = self.producer()
        producer.command.side_effect = RuntimeError("stop before executing build")
        with self.assertRaisesRegex(RuntimeError, "stop before executing"):
            producer.build()
        args = producer.command.call_args.args[0]
        self.assertIn("-Pandroid.builder.sdkDownload=false", args)
        self.assertEqual(["assembleDebug", "assembleDebugAndroidTest"], args[-2:])
        self.assert_safe_commands(producer)

    def test_check_host_cli_does_not_construct_producer_or_execute_children(self):
        for missing in (False, True):
            with mock.patch.object(sys, "argv", ["run_extra_time_native.py", "--check-host"]), \
                    mock.patch.object(native, "Producer", side_effect=AssertionError("producer forbidden")), \
                    mock.patch.object(Path, "is_char_device", return_value=not missing), \
                    self.no_side_effects(), contextlib.redirect_stdout(io.StringIO()) as output:
                if missing:
                    with self.assertRaises(SystemExit) as caught:
                        native.main()
                    self.assertEqual(1, caught.exception.code)
                else:
                    native.main()
            self.assertFalse(json.loads(output.getvalue())["runtimeVerified"])

    def test_invalid_suite_or_conflicting_inspections_cannot_start_producer(self):
        for args in (["--suite", "none"], ["--suite", "all"], ["--suite", "$(touch nope)"], ["--check-host", "--check-inventory"]):
            with self.subTest(args=args), mock.patch.object(sys, "argv", ["run_extra_time_native.py", *args]), \
                    mock.patch.object(native, "Producer") as producer, contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                native.main()
            producer.assert_not_called()

    def test_producer_source_has_no_host_provisioning_command_literals(self):
        import ast
        tree = ast.parse(inspect.getsource(native))
        strings = {node.value for node in ast.walk(tree) if isinstance(node, ast.Constant) and isinstance(node.value, str)}
        self.assertFalse(FORBIDDEN.intersection(strings))
        self.assertIn("-Pandroid.builder.sdkDownload=false", strings)


if __name__ == "__main__":
    unittest.main()
