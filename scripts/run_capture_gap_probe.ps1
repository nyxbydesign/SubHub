param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunName,
    [switch]$RecordVideo
)
$ErrorActionPreference = 'Stop'
$serial = 'emulator-5554'
$adbPath = (Get-Command adb).Source
function Invoke-Adb {
    param([string[]]$Arguments)
    $value = & $adbPath -s $serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Emulator command failed' }
    return $value
}
$hardware = (Invoke-Adb -Arguments @('shell','getprop','ro.hardware')) -join ''
if ($hardware -notmatch 'ranchu|goldfish') { throw 'Emulator-only probe' }
$size = (Invoke-Adb -Arguments @('shell','wm','size')) -join ''
$sizes = @([Regex]::Matches($size, '(?:Physical|Override) size: (\d+x\d+)'))
if ($sizes.Count -eq 0 -or $sizes[-1].Groups[1].Value -ne '1344x2992') {
    throw 'This gesture fixture requires the declared emulator geometry'
}
[xml]$settings = (Invoke-Adb -Arguments @('shell','run-as','com.subhub.app','cat',
    'shared_prefs/capture_gap_experiment.xml')) -join "`n"
if ($settings.SelectSingleNode("/map/boolean[@name='enabled']").value -ne 'true') {
    throw 'Explicit capture-gap instrumentation setup is required first'
}
$appProcess = (Invoke-Adb -Arguments @('shell','pidof','com.subhub.app')) -join ''
if ($appProcess -notmatch '^\d+$') { throw 'No live application process' }
$preflight = @(Invoke-Adb -Arguments @('logcat','-d',"--pid=$appProcess",'-s','ScreenshotA11y:I'))
if (@($preflight | Select-String 'OVERLAY_PUBLISH pass=fast').Count -lt 6) {
    throw 'Wait for established censor coverage before arming the gap'
}
$root = Join-Path (Resolve-Path "$PSScriptRoot/../app/build/reports/device").Path $RunName
if (Test-Path -LiteralPath $root) { throw 'Use a fresh run name' }
[void](New-Item -ItemType Directory -Path $root)
$trace = Join-Path $root 'trace.log'
$remote = '/sdcard/subhub-' + $RunName + '.mp4'
$collector = $null
$recorder = $null
$armed = $false
function Read-LiveTrace {
    if (!(Test-Path -LiteralPath $trace)) { return '' }
    $stream = [IO.File]::Open($trace,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
    $reader = [IO.StreamReader]::new($stream)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Mark([string]$Text) {
    Invoke-Adb -Arguments @('shell','log','-t','CensorReplay',$Text) | Out-Null
}
try {
    $collector = Start-Process -FilePath $adbPath -ArgumentList @('-s',$serial,'logcat','-v','epoch',
        '-T','1','-s','ScreenshotA11y:I','CensorMotion:I','CensorReplay:I','AndroidRuntime:E') `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $trace `
        -RedirectStandardError (Join-Path $root 'trace.err')
    $ready = 'GAP_COLLECTOR_READY id=' + $RunName
    $seen = $false
    for ($attempt=0; $attempt -lt 15; $attempt++) {
        Mark $ready
        Start-Sleep -Milliseconds 100
        if ((Read-LiveTrace).Contains($ready)) { $seen=$true; break }
    }
    if (!$seen) { throw 'Collector readiness not established' }
    if ($RecordVideo) {
        $recorder = Start-Process -FilePath $adbPath -ArgumentList @('-s',$serial,'shell','screenrecord',
            '--size','720x1600','--bit-rate','4M','--bugreport','--time-limit','24','--verbose',$remote) `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'recording.out') `
            -RedirectStandardError (Join-Path $root 'recording.err')
        $videoReady = $false
        for ($attempt=0; $attempt -lt 12; $attempt++) {
            Start-Sleep -Milliseconds 200
            $recorder.Refresh()
            if ($recorder.HasExited) { break }
            $length = (& $adbPath -s $serial shell stat -c %s $remote 2>$null) -join ''
            if ($LASTEXITCODE -eq 0 -and $length -match '^\d+$' -and [long]$length -gt 0) {
                $videoReady=$true; break
            }
        }
        if (!$videoReady) { throw 'Recorder readiness not established' }
    }
    $begin = 'GAP_GATE_BEGIN id=' + $RunName
    $end = 'GAP_GATE_END id=' + $RunName
    Mark $begin
    Invoke-Adb -Arguments @('shell','run-as','com.subhub.app','touch','cache/capture-gap-arm') | Out-Null
    $armed = $true
    $gapStarted = $false
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Milliseconds 100
        if ((Read-LiveTrace).Contains('CAPTURE_GAP phase=start ')) { $gapStarted=$true; break }
    }
    if (!$gapStarted) { throw 'Pause not acknowledged; no gestures issued' }
    $gestures = @(@(1350,1050,1000,600),@(1050,1350,1000,800),@(1800,1000,220,900),
        @(1000,1800,220,1100),@(1350,1200,150,100),@(1200,1350,150,100),
        @(1350,1200,150,100),@(1200,1350,150,100),@(1350,1200,150,100),@(1200,1350,150,100))
    $index = 0
    foreach ($gesture in $gestures) {
        Mark ('GAP_GESTURE_START id=' + $index)
        Invoke-Adb -Arguments @('shell','input','swipe','700',"$($gesture[0])",'700',
            "$($gesture[1])","$($gesture[2])") | Out-Null
        Mark ('GAP_GESTURE_END id=' + $index)
        Start-Sleep -Milliseconds $gesture[3]
        $index++
    }
    Start-Sleep -Milliseconds 2000
    if (!(Read-LiveTrace).Contains('CAPTURE_GAP phase=end ')) { throw 'Pause completion not observed' }
    Mark $end
    if ($recorder) {
        if (!$recorder.WaitForExit(30000)) { throw "Recorder remains live PID $($recorder.Id)" }
        if ($recorder.ExitCode -ne 0) { throw 'Recorder failed' }
        Invoke-Adb -Arguments @('pull',$remote,(Join-Path $root 'alignment.mp4')) | Out-Null
    }
    & "$PSScriptRoot/analyze_censor_trace.ps1" -Path $trace -StartMarker $begin -EndMarker $end |
        Out-File -LiteralPath (Join-Path $root 'analysis.json') -Encoding utf8
    Write-Output "completed=$RunName gestures=$index"
} finally {
    if ($collector) { $collector.Refresh(); if (!$collector.HasExited) { Stop-Process -Id $collector.Id } }
    if ($recorder) {
        $recorder.Refresh()
        if (!$recorder.HasExited -and !$recorder.WaitForExit(30000)) {
            Write-Warning "Recorder still live PID $($recorder.Id)"
        }
    }
    if ($armed) {
        try { Invoke-Adb -Arguments @('shell','run-as','com.subhub.app','rm','-f','cache/capture-gap-arm') | Out-Null }
        catch { Write-Warning 'Probe arm-marker cleanup failed; disable the probe through instrumentation' }
    }
}
