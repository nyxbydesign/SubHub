"""Drain Gradle output and report only bounded, fixed diagnostic labels.

Never retain a raw log or forward arbitrary output. This is a lossy diagnostic,
not a success oracle: the workflow separately preserves Gradle's exit status.
"""
import json
import re
import sys

CHUNK_BYTES = 4096
MAX_LINE_BYTES = 8192
MAX_CATEGORIES = 16
MAX_OUTPUT_BYTES = 4096
FAILURE_EXIT = 70

EXCEPTIONS = {
    b"org.gradle.api.tasks.TaskExecutionException": "task_execution",
    b"org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException": "worker_execution",
    b"org.gradle.internal.exceptions.DefaultMultiCauseException": "multiple_causes",
    b"org.gradle.execution.MultipleBuildFailures": "multiple_failures",
    b"org.gradle.api.GradleException": "gradle",
    b"org.gradle.api.internal.tasks.compile.CompilationFailedException": "java_compilation",
    b"org.gradle.api.internal.tasks.compile.JdkJavaCompiler$CompilationFailedException": "java_compilation",
    b"org.gradle.api.tasks.testing.TestExecutionException": "test_execution",
    b"org.gradle.api.internal.exceptions.MarkedVerificationException": "verification",
    b"org.gradle.api.internal.artifacts.ivyservice.DefaultLenientConfiguration$ArtifactResolveException": "dependency_resolution",
    b"org.gradle.internal.resolve.ModuleVersionResolveException": "dependency_resolution",
    b"com.android.builder.internal.aapt.v2.Aapt2Exception": "android_resources",
    b"com.android.builder.packaging.PackagerException": "apk_packaging",
    b"com.android.ide.common.process.ProcessException": "process",
    b"java.lang.OutOfMemoryError": "out_of_memory",
    b"java.lang.NullPointerException": "null_pointer",
    b"java.lang.IllegalStateException": "illegal_state",
    b"java.lang.IllegalArgumentException": "illegal_argument",
    b"java.io.IOException": "io",
    b"java.io.FileNotFoundException": "missing_file",
    b"java.io.EOFException": "unexpected_eof",
    b"java.nio.file.NoSuchFileException": "missing_file",
    b"java.nio.file.AccessDeniedException": "access_denied",
    b"java.nio.file.FileSystemException": "filesystem",
    b"java.nio.channels.ClosedChannelException": "closed_channel",
    b"java.util.zip.ZipException": "zip",
}
RESOURCE_MESSAGES = {
    b"No space left on device": "no_space",
    b"Disk quota exceeded": "disk_quota",
    b"Too many open files": "file_limit",
    b"Permission denied": "permission_denied",
    b"Read-only file system": "read_only_filesystem",
}
RESOURCE_EXCEPTIONS = {
    b"java.io.IOException", b"java.io.FileNotFoundException",
    b"java.nio.file.FileSystemException", b"java.nio.file.AccessDeniedException",
}
TASKS = {
    b":app:compileDebugJavaWithJavac": "java_compile",
    b":app:compileDebugUnitTestJavaWithJavac": "unit_java_compile",
    b":app:compileDebugAndroidTestJavaWithJavac": "native_java_compile",
    b":app:checkDebugAarMetadata": "dependency_metadata",
    b":app:mergeDebugResources": "merge_resources",
    b":app:processDebugResources": "process_resources",
    b":app:processDebugMainManifest": "main_manifest",
    b":app:lintAnalyzeDebug": "lint_analysis",
    b":app:lintReportDebug": "lint_report",
    b":app:testDebugUnitTest": "unit_tests",
    b":app:lintDebug": "lint",
    b":app:assembleDebug": "assemble",
    b":app:packageDebug": "package",
}
STATUSES = {None: "seen", b"FAILED": "failed", b"UP-TO-DATE": "up_to_date",
            b"FROM-CACHE": "from_cache", b"SKIPPED": "skipped", b"NO-SOURCE": "no_source"}
EXCEPTION_LINE = re.compile(
    rb"[ \t]*(?:(?:Caused by|Suppressed): )?([A-Za-z0-9_.$]{1,256})(?::(.*))?")
TASK_LINE = re.compile(rb"> Task ([A-Za-z0-9_:]+)(?: (FAILED|UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE))?")


class Diagnostics:
    def __init__(self):
        self.result = {"schema": 1, "messagesOmitted": True, "truncated": False,
                       "sanitizerFailed": False, "exceptionSectionSeen": False, "unknownException": False,
                       "exceptions": [], "resources": [], "tasks": {}}
        self.in_exception = False

    def add(self, key, label):
        values = self.result[key]
        if label not in values:
            if len(values) < MAX_CATEGORIES:
                values.append(label)
            else:
                self.result["truncated"] = True

    def accept(self, line):
        if line == b"* Exception is:":
            self.in_exception = True
            self.result["exceptionSectionSeen"] = True
            return
        if line.startswith((b"* ", b"BUILD ")):
            self.in_exception = False
        if not self.in_exception:
            task = TASK_LINE.fullmatch(line)
            if task and task[1] in TASKS:
                key = TASKS[task[1]]
                if self.result["tasks"].get(key) != "failed":
                    self.result["tasks"][key] = STATUSES[task[2]]
            return
        error = EXCEPTION_LINE.fullmatch(line)
        if not error:
            return
        label = EXCEPTIONS.get(error[1])
        if label is None:
            self.result["unknownException"] = True
            return
        self.add("exceptions", label)
        # Only recognize fixed resource phrases at the end of a known I/O error.
        # The message and any path preceding the phrase are never copied out.
        if error[1] in RESOURCE_EXCEPTIONS:
            message = (error[2] or b"").strip()
            for phrase, category in RESOURCE_MESSAGES.items():
                if message == phrase or message.endswith((b": " + phrase, b"(" + phrase + b")")):
                    self.add("resources", category)


def summarize(stream):
    diagnostic = Diagnostics()
    pending = bytearray()
    dropping = False

    def consume():
        try:
            diagnostic.accept(bytes(pending).rstrip(b"\r"))
        except Exception:
            # A parser failure must not close the pipe early and SIGPIPE Gradle.
            # Stop parsing, but continue draining every chunk through EOF.
            diagnostic.result["sanitizerFailed"] = True

    while True:
        chunk = stream.read(CHUNK_BYTES)
        if not chunk:
            break
        if diagnostic.result["sanitizerFailed"]:
            continue
        pieces = chunk.split(b"\n")
        for index, piece in enumerate(pieces):
            if not dropping:
                if len(pending) + len(piece) > MAX_LINE_BYTES:
                    diagnostic.result["truncated"] = True
                    pending.clear()
                    dropping = True
                else:
                    pending.extend(piece)
            if index < len(pieces) - 1:
                if not dropping:
                    consume()
                pending.clear()
                dropping = False
                if diagnostic.result["sanitizerFailed"]:
                    break
    if pending and not dropping and not diagnostic.result["sanitizerFailed"]:
        consume()
    return diagnostic.result


def emit(stream, output):
    try:
        result = summarize(stream)
        payload = json.dumps({"gradleDiagnostic": result}, separators=(",", ":"), sort_keys=True) + "\n"
        if len(payload.encode("utf-8")) > MAX_OUTPUT_BYTES:
            raise ValueError("Diagnostic output exceeded its bound")
    except Exception:
        # Never expose the error text or a Python traceback from this reporter.
        # Unexpected parsing/serialization faults also leave the producer's pipe
        # open until EOF. An unreadable pipe cannot be recovered by this helper.
        try:
            while stream.read(CHUNK_BYTES):
                pass
        except Exception:
            pass
        result = {"schema": 1, "messagesOmitted": True, "sanitizerFailed": True}
        payload = '{"gradleDiagnostic":{"schema":1,"messagesOmitted":true,"sanitizerFailed":true}}\n'
    try:
        output.write(payload)
        output.flush()
    except Exception:
        return FAILURE_EXIT
    return FAILURE_EXIT if result["sanitizerFailed"] else 0


if __name__ == "__main__":
    sys.exit(emit(sys.stdin.buffer, sys.stdout))
