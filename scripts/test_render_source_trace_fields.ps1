param([string]$AnalyzerPath = (Join-Path $PSScriptRoot 'analyze_censor_trace.ps1'))
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('subhub-render-fields-' + [Guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $root)
$path = Join-Path $root 'trace.log'
$base = '1788832900.000 1 2 I ScreenshotA11y: OVERLAY_PUBLISH pass=fast scrollId=1 captureAgeMs=100 inferenceMs=30 preprocessMs=1 runtimeMs=28 postprocessMs=1 afterMotionMs=5 tracks=2 rawVisual=2 cachedQuality=0 qualityOnly=0 geometryMatched=2 geometryChanged=0 maxCenterDeltaPx=0 maxSizeDeltaPx=0 dropped=0 qualityPreemptions=0 qualityCancelledRuns=0'
try {
    foreach($suffix in @('', ' renderSourceKnown=true renderSourceTime=10 renderSourceBias=0,-98')) {
        [IO.File]::WriteAllLines($path, @($base + $suffix), [Text.UTF8Encoding]::new($false))
        $report = (& $AnalyzerPath -Path $path) | ConvertFrom-Json
        if($report.fast.publishes -ne 1 -or !$report.parsing.complete -or
                $report.parsing.overlayRecords -ne 1 -or $report.fast.captureAgeMs.p50 -ne 100) {
            throw 'Frame was lost after additive provenance fields.'
        }
    }
    [IO.File]::WriteAllLines($path, @($base + ' unsupportedChangedSchema=1'), [Text.UTF8Encoding]::new($false))
    $report = (& $AnalyzerPath -Path $path) | ConvertFrom-Json
    if($report.parsing.complete -or $report.parsing.unparsedOverlayRecords -ne 1) {
        throw 'Unrecognized schema was not exposed as incomplete parsing.'
    }
    'PASS: legacy, provenance, and unparsed-overlay reporting.'
} finally {
    if(Test-Path -LiteralPath $path){[IO.File]::Delete($path)}
    [IO.Directory]::Delete($root)
}
