param([string]$AnalyzerPath = (Join-Path $PSScriptRoot 'analyze_censor_trace.ps1'))
$ErrorActionPreference = 'Stop'
$path = Join-Path ([IO.Path]::GetTempPath()) ('subhub-companion-' + [guid]::NewGuid().ToString('N') + '.log')
try {
    [IO.File]::WriteAllLines($path, @(
        '09-20 13:56:00.000 ScreenshotA11y: SCROLL_EVENT id=19 source=accessibility-authoritative gapMs=502 eventAgeMs=2 rawDx=0 rawDy=281 dx=0 dy=281 evidence=ABSOLUTE adjustedPx=0 amplified=false',
        '09-20 13:56:00.012 ScreenshotA11y: SCROLL_EVENT id=19 source=companion-duplicate gapMs=12 eventAgeMs=2 rawDx=0 rawDy=281 dx=0 dy=0 evidence=EXPLICIT adjustedPx=281 amplified=false'
    ), [Text.UTF8Encoding]::new($false))
    $result = (& $AnalyzerPath -Path $path) | ConvertFrom-Json
    $motion = $result.scroll
    if ($motion.events -ne 2 -or $motion.rawAbs -ne 562 -or $motion.appliedAbs -ne 281 -or
            $motion.adjustedPixels -ne 281 -or $motion.explicitlyAmplifiedEvents -ne 0 -or
            $motion.sources.'companion-duplicate' -ne 1) { throw 'Companion suppression trace was misparsed' }
    'PASS: companion duplicate retains raw displacement while counting one application.'
} finally {
    if (Test-Path -LiteralPath $path) { [IO.File]::Delete($path) }
}
