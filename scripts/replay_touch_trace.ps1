[CmdletBinding()]
<#
.SYNOPSIS
Converts a Pixel getevent capture into a compact replay manifest or replays it with ADB.

.DESCRIPTION
Raw captures retain gesture timing and path measurements. Replay uses Android's touchscreen input
injector with recorded endpoints, durations, and absolute inter-gesture scheduling. Replaying is
opt-in and validates the connected display dimensions before sending any input.

.EXAMPLE
.\scripts\replay_touch_trace.ps1 `
    -Path .\scripts\replays\emiru_google_images_pixel_8_pro.json `
    -Serial 192.168.1.109:5555 -Replay
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [string]$OutputPath,

    [switch]$IncludePoints,

    [switch]$Replay,

    [switch]$TraceMarkers,

    [string]$Serial,

    [int]$StartGesture = 1,

    [int]$EndGesture = [int]::MaxValue,

    [ValidateRange(0.1, 10.0)]
    [double]$Speed = 1.0,

    [ValidateRange(1, 10000)]
    [int]$TapDistancePx = 24,

    [ValidateRange(1, 5000)]
    [int]$TapDurationMs = 350,

    [ValidateRange(1, 10000)]
    [int]$CoordinateWidth = 1344,

    [ValidateRange(1, 10000)]
    [int]$CoordinateHeight = 2992
)

$ErrorActionPreference = 'Stop'

function Convert-HexCoordinate {
    param([Parameter(Mandatory = $true)][string]$Value)
    return [Convert]::ToInt32($Value, 16)
}

function Complete-Gesture {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()]
        [System.Collections.Generic.List[object]]$Gestures,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()]
        [System.Collections.Generic.List[object]]$Points,
        [double]$DownTime,
        [double]$UpTime
    )

    if ($Points.Count -eq 0) { return }
    $first = $Points[0]
    $last = $Points[$Points.Count - 1]
    $pathLength = 0.0
    for ($index = 1; $index -lt $Points.Count; $index++) {
        $dx = [double]$Points[$index].x - [double]$Points[$index - 1].x
        $dy = [double]$Points[$index].y - [double]$Points[$index - 1].y
        $pathLength += [Math]::Sqrt(($dx * $dx) + ($dy * $dy))
    }

    $durationMs = [Math]::Max(1, [Math]::Round(($UpTime - $DownTime) * 1000.0))
    $Gestures.Add([pscustomobject]@{
        index = $Gestures.Count + 1
        startSeconds = $DownTime
        endSeconds = $UpTime
        durationMs = [int]$durationMs
        startX = [int]$first.x
        startY = [int]$first.y
        endX = [int]$last.x
        endY = [int]$last.y
        deltaX = [int]$last.x - [int]$first.x
        deltaY = [int]$last.y - [int]$first.y
        pathLengthPx = [Math]::Round($pathLength, 1)
        pointCount = $Points.Count
        points = @($Points)
    })
}

$resolvedPath = (Resolve-Path -LiteralPath $Path).Path
$gestures = [System.Collections.Generic.List[object]]::new()

if ([IO.Path]::GetExtension($resolvedPath) -eq '.json') {
    $loaded = Get-Content -Raw -LiteralPath $resolvedPath | ConvertFrom-Json
    if ([int]$loaded.schemaVersion -ne 1) {
        throw "Unsupported replay schema version $($loaded.schemaVersion)."
    }
    $CoordinateWidth = [int]$loaded.coordinateWidth
    $CoordinateHeight = [int]$loaded.coordinateHeight
    foreach ($gesture in @($loaded.gestures)) { $gestures.Add($gesture) }
} else {
    $points = [System.Collections.Generic.List[object]]::new()
    $touching = $false
    $downTime = 0.0
    $currentX = $null
    $currentY = $null
    $positionChanged = $false
    $lastTimestamp = 0.0

    foreach ($line in [System.IO.File]::ReadLines($resolvedPath)) {
        $clean = $line -replace "`e\[[0-9;?]*[ -/]*[@-~]", ''
        $match = [regex]::Match($clean, '^\[\s*(?<time>\d+\.\d+)\]\s+(?<type>\S+)\s+(?<code>\S+)\s+(?<value>\S+)')
        if (-not $match.Success) { continue }

        $timestamp = [double]::Parse(
            $match.Groups['time'].Value,
            [Globalization.CultureInfo]::InvariantCulture)
        $type = $match.Groups['type'].Value
        $code = $match.Groups['code'].Value
        $value = $match.Groups['value'].Value
        $lastTimestamp = $timestamp

        if ($type -eq 'EV_KEY' -and $code -eq 'BTN_TOUCH' -and $value -eq 'DOWN') {
            if ($touching) {
                Complete-Gesture -Gestures $gestures -Points $points `
                    -DownTime $downTime -UpTime $timestamp
            }
            $touching = $true
            $downTime = $timestamp
            $currentX = $null
            $currentY = $null
            $positionChanged = $false
            $points = [System.Collections.Generic.List[object]]::new()
            continue
        }

        if (-not $touching) { continue }

        if ($type -eq 'EV_ABS' -and $code -eq 'ABS_MT_POSITION_X') {
            $currentX = Convert-HexCoordinate $value
            $positionChanged = $true
            continue
        }
        if ($type -eq 'EV_ABS' -and $code -eq 'ABS_MT_POSITION_Y') {
            $currentY = Convert-HexCoordinate $value
            $positionChanged = $true
            continue
        }
        if ($type -eq 'EV_SYN' -and $code -eq 'SYN_REPORT') {
            if ($positionChanged -and $null -ne $currentX -and $null -ne $currentY) {
                $points.Add([pscustomobject]@{
                    dtMs = [int][Math]::Round(($timestamp - $downTime) * 1000.0)
                    x = [int]$currentX
                    y = [int]$currentY
                })
            }
            $positionChanged = $false
            continue
        }
        if (($type -eq 'EV_KEY' -and $code -eq 'BTN_TOUCH' -and $value -eq 'UP') -or
                ($type -eq 'EV_ABS' -and $code -eq 'ABS_MT_TRACKING_ID' -and
                    $value -eq 'ffffffff')) {
            Complete-Gesture -Gestures $gestures -Points $points `
                -DownTime $downTime -UpTime $timestamp
            $touching = $false
            $points = [System.Collections.Generic.List[object]]::new()
        }
    }

    if ($touching) {
        Complete-Gesture -Gestures $gestures -Points $points `
            -DownTime $downTime -UpTime $lastTimestamp
    }

    for ($index = 0; $index -lt $gestures.Count; $index++) {
        $pause = if ($index + 1 -lt $gestures.Count) {
            [Math]::Max(0, [Math]::Round(
                ($gestures[$index + 1].startSeconds - $gestures[$index].endSeconds) * 1000.0))
        } else { 0 }
        $gestures[$index] | Add-Member `
            -NotePropertyName pauseAfterMs -NotePropertyValue ([int]$pause)
    }
}

$selected = @($gestures | Where-Object {
    $_.index -ge $StartGesture -and $_.index -le $EndGesture
})

if ($OutputPath) {
    $outputGestures = foreach ($gesture in $selected) {
        $item = [ordered]@{
            index = [int]$gesture.index
            startSeconds = [double]$gesture.startSeconds
            endSeconds = [double]$gesture.endSeconds
            durationMs = [int]$gesture.durationMs
            startX = [int]$gesture.startX
            startY = [int]$gesture.startY
            endX = [int]$gesture.endX
            endY = [int]$gesture.endY
            deltaX = [int]$gesture.deltaX
            deltaY = [int]$gesture.deltaY
            pathLengthPx = [double]$gesture.pathLengthPx
            pointCount = [int]$gesture.pointCount
            pauseAfterMs = [int]$gesture.pauseAfterMs
        }
        if ($IncludePoints -and $null -ne $gesture.points) {
            $item.points = @($gesture.points)
        }
        [pscustomobject]$item
    }
    $recording = [pscustomobject]@{
        schemaVersion = 1
        source = Split-Path -Leaf $resolvedPath
        coordinateWidth = $CoordinateWidth
        coordinateHeight = $CoordinateHeight
        gestureCount = $selected.Count
        gestures = @($outputGestures)
    }
    $outputDirectory = Split-Path -Parent $OutputPath
    if ($outputDirectory -and -not (Test-Path -LiteralPath $outputDirectory)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }
    $recording | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding utf8
}

$selected | Select-Object index, durationMs, startX, startY, endX, endY,
    deltaX, deltaY, pathLengthPx, pointCount, pauseAfterMs | Format-Table -AutoSize

if (-not $Replay) { return }
if (-not $Serial) { throw '-Serial is required with -Replay.' }
if ($selected.Count -eq 0) { throw 'No gestures were selected for replay.' }
if ((& adb -s $Serial get-state 2>$null) -ne 'device') {
    throw "ADB device $Serial is not connected."
}

$sizeOutput = (& adb -s $Serial shell wm size) -join ' '
$sizeMatch = [regex]::Match($sizeOutput, '(?<width>\d+)x(?<height>\d+)')
if (-not $sizeMatch.Success) { throw "Could not read display size from: $sizeOutput" }
$actualWidth = [int]$sizeMatch.Groups['width'].Value
$actualHeight = [int]$sizeMatch.Groups['height'].Value
if ($actualWidth -ne $CoordinateWidth -or $actualHeight -ne $CoordinateHeight) {
    throw "Display is ${actualWidth}x${actualHeight}; recording expects " +
        "${CoordinateWidth}x${CoordinateHeight}."
}

$origin = [double]$selected[0].startSeconds
$clock = [Diagnostics.Stopwatch]::StartNew()
foreach ($gesture in $selected) {
    $targetStartMs = (($gesture.startSeconds - $origin) * 1000.0) / $Speed
    $waitMs = [int][Math]::Round($targetStartMs - $clock.Elapsed.TotalMilliseconds)
    if ($waitMs -gt 0) { Start-Sleep -Milliseconds $waitMs }

    $distance = [Math]::Sqrt(
        ([double]$gesture.deltaX * [double]$gesture.deltaX) +
        ([double]$gesture.deltaY * [double]$gesture.deltaY))
    $duration = [Math]::Max(1, [int][Math]::Round($gesture.durationMs / $Speed))
    $isTap = $distance -le $TapDistancePx -and $gesture.durationMs -le $TapDurationMs
    $gestureKind = if ($isTap) { 'tap' } else { 'swipe' }
    if ($TraceMarkers) {
        & adb -s $Serial shell log -t SubHubReplay `
            "GESTURE_START index=$($gesture.index) kind=$gestureKind durationMs=$duration dx=$($gesture.deltaX) dy=$($gesture.deltaY)" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Gesture $($gesture.index) start marker failed." }
    }
    if ($isTap) {
        & adb -s $Serial shell input touchscreen tap $gesture.startX $gesture.startY | Out-Null
    } else {
        & adb -s $Serial shell input touchscreen swipe `
            $gesture.startX $gesture.startY $gesture.endX $gesture.endY $duration | Out-Null
    }
    if ($LASTEXITCODE -ne 0) { throw "Gesture $($gesture.index) injection failed." }
    if ($TraceMarkers) {
        & adb -s $Serial shell log -t SubHubReplay `
            "GESTURE_END index=$($gesture.index)" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Gesture $($gesture.index) end marker failed." }
    }
}

$clock.Stop()
Write-Output ("Replayed {0} gestures in {1:N0} ms at {2:N2}x speed." -f `
    $selected.Count, $clock.Elapsed.TotalMilliseconds, $Speed)
