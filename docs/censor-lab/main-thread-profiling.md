# Main-thread profiling without disconnecting Accessibility

Starting Android instrumentation can terminate the target process and leave its Accessibility
service enabled but unbound/crashed. A passing test or idle main-thread stack does not establish
a live censor pipeline. Never use that state for performance or alignment evidence.

Use MainThreadSamplingAndroidTest only as explicit emulator setup with sampleMainThread=true.
Build both APKs together, install the test APK, run the declared SubHubTestRunner, wait for its
terminal result, then reinstall the matching target APK. MainThreadSampler consumes the debug
one-shot flag on service connection and stops after 45 seconds. Verify current-process fast
publications before replay, and verify MAIN_SAMPLE_END before collecting final sample counts.
Do not change Accessibility permissions or weaken readiness gates to make the probe run.

Parse using scripts/analyze_main_thread_samples.py; require raw/parsed equality and no malformed
records. Keep startup, replay, and idle-tail populations distinct. Sampling has observer overhead
and is not precise wall-time accounting. Follow with an uninstrumented replay. No UI text, node
contents, or screenshots belong in these diagnostic records.
