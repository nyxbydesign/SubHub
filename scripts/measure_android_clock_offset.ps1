param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateRange(1,9)][int]$Samples = 5
)
$ErrorActionPreference = 'Stop'
$measurements = @()
for ($index = 0; $index -lt $Samples; $index++) {
    $before = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $deviceOutput = & adb -s $Serial shell date '+%s%3N'
    $exitCode = $LASTEXITCODE
    $after = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $deviceText = ($deviceOutput -join '').Trim()
    if ($exitCode -ne 0 -or $deviceText -notmatch '^\d{13}$' -or $after -lt $before) {
        throw 'Clock probe failed or returned an unsupported timestamp.'
    }
    $device = [long]$deviceText
    $measurements += [pscustomobject]@{
        hostBeforeMs = $before
        hostAfterMs = $after
        deviceMs = $device
        roundTripMs = $after - $before
        hostMinusDeviceMs = ($before + $after) / 2.0 - $device
        uncertaintyMs = ($after - $before) / 2.0
    }
}
$best = $measurements | Sort-Object roundTripMs | Select-Object -First 1
[pscustomobject]@{
    schema = 1
    mapping = 'hostEpochMs = deviceEpochMs + hostMinusDeviceMs'
    note = 'Round-trip midpoint estimate, not exact synchronization; bracket each recording.'
    hostMinusDeviceMs = $best.hostMinusDeviceMs
    uncertaintyMs = $best.uncertaintyMs
    samples = $measurements
} | ConvertTo-Json -Depth 4
