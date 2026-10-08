param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_.:-]+$')]
    [string]$Serial,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../app/build/reports/device/android-capture'),
    [ValidateRange(1000, 60000)]
    [int]$TimeoutMs = 15000
)

# Android-only observation fallback. It neither controls Windows nor fabricates a sky screenshot ID.
$ErrorActionPreference = 'Stop'
$adbPath = (Get-Command adb -ErrorAction Stop).Source
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
[void](New-Item -ItemType Directory -Path $outputRoot -Force)
$nonce = [Guid]::NewGuid().ToString('N')
$partial = Join-Path $outputRoot ("frame-$nonce.partial")
$destination = Join-Path $outputRoot ("frame-$nonce.png")
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $adbPath
# Serial is restricted above; no shell evaluates these arguments.
$start.Arguments = "-s $Serial exec-out screencap -p"
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$captureProcess = [Diagnostics.Process]::new()
$captureProcess.StartInfo = $start
$stream = $null
$timer = [Diagnostics.Stopwatch]::StartNew()
try {
    $stream = [IO.File]::Open($partial, [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write, [IO.FileShare]::None)
    if (!$captureProcess.Start()) { throw 'Android capture did not start.' }
    $copy = $captureProcess.StandardOutput.BaseStream.CopyToAsync($stream)
    $errors = $captureProcess.StandardError.ReadToEndAsync()
    if (!$captureProcess.WaitForExit($TimeoutMs)) {
        $captureProcess.Kill()
        [void]$captureProcess.WaitForExit(5000)
        throw 'Android capture timed out; incomplete output remains .partial.'
    }
    if (!$copy.Wait(5000)) { throw 'Android capture output did not finish.' }
    if ($captureProcess.ExitCode -ne 0) {
        # Do not print raw device stderr: report status without accidental device data exposure.
        throw "Android capture failed (exit $($captureProcess.ExitCode))."
    }
    $stream.Dispose()
    $stream = $null
    $bytes = [IO.File]::ReadAllBytes($partial)
    $signature = @(137, 80, 78, 71, 13, 10, 26, 10)
    if ($bytes.Length -lt 33) { throw 'Android capture produced no valid PNG header.' }
    for ($i = 0; $i -lt $signature.Count; $i++) {
        if ($bytes[$i] -ne $signature[$i]) { throw 'Android capture output is not PNG.' }
    }
    if ([Text.Encoding]::ASCII.GetString($bytes, 12, 4) -ne 'IHDR') {
        throw 'Android capture has no PNG dimensions.'
    }
    $width = ([long]$bytes[16] -shl 24) + ([long]$bytes[17] -shl 16) +
            ([long]$bytes[18] -shl 8) + $bytes[19]
    $height = ([long]$bytes[20] -shl 24) + ([long]$bytes[21] -shl 16) +
            ([long]$bytes[22] -shl 8) + $bytes[23]
    if ($width -le 0 -or $height -le 0) { throw 'Android capture dimensions are invalid.' }
    Move-Item -LiteralPath $partial -Destination $destination
    [pscustomobject]@{
        path = $destination
        width = $width
        height = $height
        bytes = $bytes.Length
        captureMs = $timer.ElapsedMilliseconds
        source = 'android-adb'
        windowsScreenshotId = $null
    } | ConvertTo-Json
} finally {
    if ($stream) { $stream.Dispose() }
    $captureProcess.Dispose()
}
