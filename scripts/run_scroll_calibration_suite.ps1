param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$ManifestPath = "$PSScriptRoot\calibration\scroll_suite.json",
    [string]$OutputDirectory = "$PSScriptRoot\..\app\build\reports\device\calibration",
    [int]$TargetStart = 0,
    [int]$TargetCount = 0,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
    }
}

function Write-DeviceMarker {
    param([string]$Message)
    if ($DryRun) {
        Write-Output "MARKER $Message"
        return
    }
    Invoke-Adb -Arguments @('shell', 'log', '-t', 'CensorReplay', $Message) | Out-Null
}

function Scale-Coordinate {
    param([int]$Value, [int]$Reference, [int]$Actual)
    return [int][Math]::Round($Value * $Actual / [double][Math]::Max(1, $Reference))
}

$manifestFile = Resolve-Path -LiteralPath $ManifestPath
$manifest = Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
$referenceWidth = [int]$manifest.referenceDisplay.width
$referenceHeight = [int]$manifest.referenceDisplay.height

$sizeLine = (& adb -s $Serial shell wm size | Select-String 'Physical size|Override size' |
    Select-Object -Last 1).Line
if ($sizeLine -notmatch '(\d+)x(\d+)') { throw 'Could not resolve device display size.' }
$displayWidth = [int]$Matches[1]
$displayHeight = [int]$Matches[2]

$allTargets = @($manifest.targets)
$start = [Math]::Max(0, $TargetStart)
$count = if ($TargetCount -gt 0) { $TargetCount } else { $allTargets.Count - $start }
$targets = @($allTargets | Select-Object -Skip $start -First $count)
if ($targets.Count -eq 0) { throw 'No calibration targets selected.' }

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$sessionId = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
$logPath = Join-Path $OutputDirectory "calibration-$sessionId-logcat.txt"
$runPath = Join-Path $OutputDirectory "calibration-$sessionId-run.json"
$logProcess = $null

try {
    if (-not $DryRun) {
        & adb -s $Serial logcat -c
        $arguments = @(
            '-s', $Serial, 'logcat', '-v', 'epoch',
            'CensorReplay:I', 'ScreenshotA11y:I', 'CensorMotion:I', '*:S'
        )
        $logProcess = Start-Process -FilePath 'adb' -ArgumentList $arguments `
            -RedirectStandardOutput $logPath -RedirectStandardError "$logPath.err" `
            -WindowStyle Hidden -PassThru
    }

    $runStarted = [DateTimeOffset]::UtcNow
    $targetRuns = [System.Collections.Generic.List[object]]::new()
    for ($targetIndex = 0; $targetIndex -lt $targets.Count; $targetIndex++) {
        $target = $targets[$targetIndex]
        $installed = (& adb -s $Serial shell pm path $target.package).Trim()
        if (-not $installed) {
            $targetRuns.Add([pscustomobject]@{
                id = $target.id; kind = $target.kind; skipped = $true; reason = 'not-installed'
            })
            continue
        }
        Write-DeviceMarker "TARGET_START index=$targetIndex id=$($target.id) kind=$($target.kind)"
        if (-not $DryRun) {
            Invoke-Adb -Arguments @(
                'shell', 'monkey', '-p', $target.package,
                '-c', 'android.intent.category.LAUNCHER', '1'
            ) |
                Out-Null
            Start-Sleep -Milliseconds 1600
        }
        $gestures = [System.Collections.Generic.List[object]]::new()
        foreach ($gesture in @($manifest.gestures)) {
            $fromX = Scale-Coordinate $gesture.from[0] $referenceWidth $displayWidth
            $fromY = Scale-Coordinate $gesture.from[1] $referenceHeight $displayHeight
            $toX = Scale-Coordinate $gesture.to[0] $referenceWidth $displayWidth
            $toY = Scale-Coordinate $gesture.to[1] $referenceHeight $displayHeight
            $duration = [int]$gesture.durationMs
            Write-DeviceMarker "GESTURE_START target=$targetIndex id=$($gesture.id) durationMs=$duration"
            $started = [DateTimeOffset]::UtcNow
            if (-not $DryRun) {
                Invoke-Adb -Arguments @(
                    'shell', 'input', 'touchscreen', 'swipe',
                    "$fromX", "$fromY", "$toX", "$toY", "$duration"
                ) |
                    Out-Null
            }
            Write-DeviceMarker "GESTURE_END target=$targetIndex id=$($gesture.id)"
            $gestures.Add([pscustomobject]@{
                id = $gesture.id
                startedUtc = $started.ToString('O')
                from = @($fromX, $fromY)
                to = @($toX, $toY)
                durationMs = $duration
            })
            if (-not $DryRun) { Start-Sleep -Milliseconds ([int]$gesture.afterMs) }
        }
        Write-DeviceMarker "TARGET_END index=$targetIndex id=$($target.id)"
        $targetRuns.Add([pscustomobject]@{
            id = $target.id
            kind = $target.kind
            skipped = $false
            gestures = @($gestures)
        })
    }
    $result = [ordered]@{
        schemaVersion = 1
        sessionId = $sessionId
        display = [ordered]@{ width = $displayWidth; height = $displayHeight }
        startedUtc = $runStarted.ToString('O')
        stoppedUtc = [DateTimeOffset]::UtcNow.ToString('O')
        targets = @($targetRuns)
        logcat = $logPath
    }
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $runPath -Encoding utf8
    Write-Output "Calibration suite complete: $runPath"
} finally {
    if ($null -ne $logProcess -and -not $logProcess.HasExited) {
        Stop-Process -Id $logProcess.Id -Force
        $logProcess.WaitForExit()
    }
}
