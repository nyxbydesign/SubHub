param([string]$AnalyzerPath = (Join-Path $PSScriptRoot 'analyze_censor_trace.ps1'))
$ErrorActionPreference = 'Stop'
$path = Join-Path ([IO.Path]::GetTempPath()) ('subhub-scroll-metadata-' + [guid]::NewGuid().ToString('N') + '.log')
$record = 'SCROLL_METADATA schema=1 sourceUptimeMs=100 motionToken=abc classKind=4 offset=0,1500 max=0,30000 indices=4,8,200 explicit=-1,-1 sourcePresent=true nodes=3 ownerDepth=1 ownerKind=2 traversalFailed=false'
try {
    [IO.File]::WriteAllLines($path, @('09-20 03:00:00.000 ScreenshotA11y: ' + $record), [Text.UTF8Encoding]::new($false))
    $r = (& $AnalyzerPath -Path $path) | ConvertFrom-Json
    $items = @($r.scrollMetadata.records)
    if (-not $r.parsing.complete -or $r.parsing.scrollMetadataRecords -ne 1 -or
            $r.parsing.parsedScrollMetadataRecords -ne 1 -or $items.Count -ne 1 -or
            $items[0].offsetY -ne 1500 -or $items[0].maxY -ne 30000 -or
            $items[0].fromIndex -ne 4 -or $items[0].itemCount -ne 200 -or
            $items[0].explicitY -ne -1 -or $items[0].ownerDepth -ne 1) { throw 'Metadata parsed incorrectly' }
    foreach ($invalid in @($record + ' unknown=1', $record.Replace('schema=1', 'schema=2'), $record.Replace(' max=0,30000', ''))) {
        [IO.File]::WriteAllLines($path, @($invalid), [Text.UTF8Encoding]::new($false))
        $bad = (& $AnalyzerPath -Path $path) | ConvertFrom-Json
        if ($bad.parsing.complete -or $bad.parsing.unparsedScrollMetadataRecords -ne 1) {
            throw 'Unsupported metadata was silently accepted'
        }
    }
    'PASS: numeric scroll metadata and incomplete-schema detection.'
} finally {
    if (Test-Path -LiteralPath $path) { [IO.File]::Delete($path) }
}
