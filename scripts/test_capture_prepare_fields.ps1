param([string]$AnalyzerPath = (Join-Path $PSScriptRoot 'analyze_censor_trace.ps1'))
$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('subhub-prepare-fields-' + [Guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $testRoot)
$fixtures = @()
try {
    foreach ($optional in @('', ' oldFrame=false')) {
        $path = Join-Path $testRoot ("case-$($fixtures.Count).txt")
        $fixtures += $path
        $lines = @(
            " 1788832900.000 1 2 I ScreenshotA11y: QUALITY_PREPARE_BEGIN sourceFastSequence=1 generation=2${optional} uptimeNanos=1000000",
            ' 1788832900.001 1 3 I DetectionEngine: INFERENCE_NATIVE_BEGIN lane=fast runId=3 provider=CPU uptimeNanos=2000000',
            ' 1788832900.003 1 3 I DetectionEngine: INFERENCE_NATIVE_END lane=fast runId=3 status=completed durationMs=2 uptimeNanos=4000000',
            " 1788832900.005 1 2 I ScreenshotA11y: QUALITY_PREPARE_END sourceFastSequence=1 generation=2${optional} durationMs=5 uptimeNanos=6000000"
        )
        [IO.File]::WriteAllLines($path, $lines, [Text.UTF8Encoding]::new($false))
        $report = (& $AnalyzerPath -Path $path) | ConvertFrom-Json
        if ($report.inferenceGate.qualityPrepareMs.count -ne 1 -or
                $report.inferenceGate.qualityPrepareMs.p50 -ne 5 -or
                $report.inferenceGate.qualityPrepareFastNativeOverlapPairs -ne 1) {
            throw 'Preparation fields or overlap were silently omitted.'
        }
    }
    'PASS: legacy and oldFrame preparation records (2 cases).'
} finally {
    foreach ($path in $fixtures) { if (Test-Path -LiteralPath $path) { [IO.File]::Delete($path) } }
    if (Test-Path -LiteralPath $testRoot) { [IO.Directory]::Delete($testRoot) }
}
