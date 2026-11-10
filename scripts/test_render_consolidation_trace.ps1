$ErrorActionPreference = 'Stop'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('subhub-grouping-' + [guid]::NewGuid().ToString('N') + '.log')
$analyzer = Join-Path $PSScriptRoot 'analyze_censor_trace.ps1'
try {
    $valid = ' 1788590001.000 1 1 I CensorMotion: CONSOLIDATE input=5 output=3 merged=2 rawLive=3 selectedLive=3 rawCache=2 selectedCache=2'
    [IO.File]::WriteAllText($fixture, $valid, [Text.UTF8Encoding]::new($false))
    $result = & $analyzer $fixture | ConvertFrom-Json
    if ($result.displayMotion.consolidationPublishes -ne 1 -or $result.displayMotion.consolidatedRegions -ne 2) {
        throw 'Raw and parsed consolidation counts differ.'
    }
    foreach ($invalid in @($valid + ' unexpected=1', $valid.Replace('merged=2', 'merged=7'),
            $valid.Replace('rawLive=3', 'rawLive=9'), 'CensorMotion: CONSOLIDATE broken')) {
        [IO.File]::WriteAllText($fixture, $invalid, [Text.UTF8Encoding]::new($false))
        $rejected = $false
        try { & $analyzer $fixture | Out-Null } catch { $rejected = $true }
        if (!$rejected) { throw 'Unsupported grouping trace was silently accepted.' }
    }
    Write-Output 'PASS: one valid grouping record and four invalid-record fixtures.'
} finally {
    Remove-Item -LiteralPath $fixture -ErrorAction SilentlyContinue
}
