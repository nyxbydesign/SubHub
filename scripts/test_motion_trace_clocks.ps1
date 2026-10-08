param([string]$AnalyzerPath = (Join-Path $PSScriptRoot 'analyze_censor_trace.ps1'))
$ErrorActionPreference='Stop'
$path=Join-Path ([IO.Path]::GetTempPath()) ('subhub-clock-'+[Guid]::NewGuid().ToString('N')+'.log')
try {
    [IO.File]::WriteAllLines($path,@(
        '1788832900.000 1 2 I CensorMotion: DRAW seq=1 inputToDrawMs=9 drawClock=uptime inputToPresentationMs=42 visual=0,10 text=0,10 viewportLead=0,-3 renderTickMs=16',
        '1788832900.100 1 2 I CensorMotion: DRAW seq=2 inputToDrawMs=50 visual=0,20 text=0,20 viewportLead=0,4 renderTickMs=33'
    ),[Text.UTF8Encoding]::new($false))
    $r=(& $AnalyzerPath -Path $path) | ConvertFrom-Json
    if($r.displayMotion.draws -ne 2 -or $r.displayMotion.explicitUptimeDraws -ne 1 -or
       $r.displayMotion.legacyUnknownClockDraws -ne 1 -or
       $r.displayMotion.inputToPresentationMs.p50 -ne 42 -or
       $r.displayMotion.inputToDrawMs.min -ne 9 -or
       $r.displayMotion.viewportLeadAbsPx.max -ne 4 -or
       $r.displayMotion.renderTickMs.max -ne 33) {throw 'Motion clocks or existing geometry fields were misparsed'}
    'PASS: actual, expected-presentation and legacy-unknown clocks remain distinct.'
} finally {if(Test-Path -LiteralPath $path){[IO.File]::Delete($path)}}
