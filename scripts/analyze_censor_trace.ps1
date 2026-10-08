param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string[]] $Path,
    [string] $StartMarker,
    [string] $EndMarker
)

$ErrorActionPreference = 'Stop'

$hasStartMarker = -not [string]::IsNullOrWhiteSpace($StartMarker)
$hasEndMarker = -not [string]::IsNullOrWhiteSpace($EndMarker)
if ($hasStartMarker -ne $hasEndMarker) {
    throw 'StartMarker and EndMarker must be supplied together.'
}

function Get-TraceTime([string] $line) {
    if ($line -match '^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)') {
        return [datetime]::ParseExact(
            "2026-$($Matches[1])",
            'yyyy-MM-dd HH:mm:ss.fff',
            [Globalization.CultureInfo]::InvariantCulture)
    }
    if ($line -match '^\s+(\d{10}\.\d{3})\s') {
        $milliseconds = [long] ([double] $Matches[1] * 1000.0)
        return [DateTimeOffset]::FromUnixTimeMilliseconds($milliseconds).UtcDateTime
    }
    return $null
}

function Get-Distribution([object[]] $values) {
    $numbers = @($values | Where-Object { $null -ne $_ } |
            ForEach-Object { [double] $_ } | Sort-Object)
    if ($numbers.Count -eq 0) { return $null }
    function At-Percentile([double] $percentile) {
        if ($numbers.Count -eq 1) { return $numbers[0] }
        $position = ($numbers.Count - 1) * $percentile
        $lower = [math]::Floor($position)
        $upper = [math]::Ceiling($position)
        if ($lower -eq $upper) { return $numbers[$lower] }
        return $numbers[$lower] + (($numbers[$upper] - $numbers[$lower]) * ($position - $lower))
    }
    $sum = ($numbers | Measure-Object -Sum).Sum
    return [ordered]@{
        count = $numbers.Count
        min = [math]::Round($numbers[0], 2)
        p50 = [math]::Round((At-Percentile 0.50), 2)
        p90 = [math]::Round((At-Percentile 0.90), 2)
        p95 = [math]::Round((At-Percentile 0.95), 2)
        p99 = [math]::Round((At-Percentile 0.99), 2)
        max = [math]::Round($numbers[-1], 2)
        mean = [math]::Round(($sum / $numbers.Count), 2)
    }
}

function Get-GroupCounts([object[]] $items, [string] $property) {
    $result = [ordered]@{}
    foreach ($group in @($items | Group-Object -Property $property | Sort-Object Name)) {
        $result[$group.Name] = $group.Count
    }
    return $result
}

foreach ($requestedPath in $Path) {
    $resolved = (Resolve-Path -LiteralPath $requestedPath).Path
    $sourceLines = @(Get-Content -LiteralPath $resolved)
    if ($hasStartMarker) {
        $startMatches = @(
            for ($index = 0; $index -lt $sourceLines.Count; $index++) {
                if ($sourceLines[$index].Contains($StartMarker)) { $index }
            })
        $endMatches = @(
            for ($index = 0; $index -lt $sourceLines.Count; $index++) {
                if ($sourceLines[$index].Contains($EndMarker)) { $index }
            })
        if ($startMatches.Count -ne 1) {
            throw "StartMarker matched $($startMatches.Count) lines in ${resolved}; expected exactly one."
        }
        if ($endMatches.Count -ne 1) {
            throw "EndMarker matched $($endMatches.Count) lines in ${resolved}; expected exactly one."
        }
        $startIndex = [int] $startMatches[0]
        $endIndex = [int] $endMatches[0]
        if ($startIndex -ge $endIndex) {
            throw "StartMarker must precede EndMarker in ${resolved}."
        }
        $lines = @($sourceLines[$startIndex..$endIndex])
        $selection = [ordered]@{
            mode = 'bounded'
            startMarker = $StartMarker
            endMarker = $EndMarker
            sourceLineCount = $sourceLines.Count
            selectedLineCount = $lines.Count
            startLine = $startIndex + 1
            endLine = $endIndex + 1
            excludedBefore = $startIndex
            excludedAfter = $sourceLines.Count - $endIndex - 1
        }
    } else {
        $lines = $sourceLines
        $selection = [ordered]@{
            mode = 'unscoped'
            startMarker = $null
            endMarker = $null
            sourceLineCount = $sourceLines.Count
            selectedLineCount = $sourceLines.Count
            startLine = if ($sourceLines.Count -gt 0) { 1 } else { $null }
            endLine = if ($sourceLines.Count -gt 0) { $sourceLines.Count } else { $null }
            excludedBefore = 0
            excludedAfter = 0
        }
    }
    $firstTime = $null
    $lastTime = $null
    $publishes = [Collections.Generic.List[object]]::new()
    $quality = [Collections.Generic.List[object]]::new()
    $streamingQuality = [Collections.Generic.List[object]]::new()
    $streamingQualityDrops = [Collections.Generic.List[object]]::new()
    $qualityBackfillOffers = [Collections.Generic.List[object]]::new()
    $qualityBackfillDrops = [Collections.Generic.List[object]]::new()
    $qualityPreempts = [Collections.Generic.List[object]]::new()
    $qualityCancellations = [Collections.Generic.List[object]]::new()
    $qualityGateSkips = [Collections.Generic.List[object]]::new()
    $qualityLateStages = [Collections.Generic.List[object]]::new()
    $qualityLatePresents = [Collections.Generic.List[object]]::new()
    $qualityRetainedPresents = [Collections.Generic.List[object]]::new()
    $rawQualityRetainedPresents = 0
    $qualityImmediatePresents = [Collections.Generic.List[object]]::new()
    $qualityLateDrops = [Collections.Generic.List[object]]::new()
    $qualityConcurrency = [Collections.Generic.List[object]]::new()
    $fastGateWaits = [Collections.Generic.List[object]]::new()
    $qualityWindows = [Collections.Generic.List[object]]::new()
    $nativeRuns = [Collections.Generic.List[object]]::new()
    $nativeBegins = @{}
    $qualityPrepares = [Collections.Generic.List[object]]::new()
    $qualityPrepareBegins = @{}
    $startupEvents = [Collections.Generic.List[object]]::new()
    $qualitySupplements = [Collections.Generic.List[object]]::new()
    $qualityRetires = [Collections.Generic.List[object]]::new()
    $sceneBegins = [Collections.Generic.List[object]]::new()
    $sceneCommits = [Collections.Generic.List[object]]::new()
    $sceneTimeouts = [Collections.Generic.List[object]]::new()
    $sceneLateDrops = [Collections.Generic.List[object]]::new()
    $sceneInvalidations = [Collections.Generic.List[object]]::new()
    $worldCacheQueries = [Collections.Generic.List[object]]::new()
    $worldCacheInvalidations = [Collections.Generic.List[object]]::new()
    $worldCacheReentries = [Collections.Generic.List[object]]::new()
    $capturePhases = [Collections.Generic.List[object]]::new()
    $scrolls = [Collections.Generic.List[object]]::new()
    $scrollMetadata = [Collections.Generic.List[object]]::new()
    $rawScrollMetadata = 0
    $textScans = [Collections.Generic.List[object]]::new()
    $textPublishes = [Collections.Generic.List[object]]::new()
    $textConfirms = [Collections.Generic.List[object]]::new()
    $fastPublishTimes = [Collections.Generic.List[datetime]]::new()
    $motionDraws = [Collections.Generic.List[object]]::new()
    $motionInputs = [Collections.Generic.List[object]]::new()
    $motionSettles = [Collections.Generic.List[object]]::new()
    $renderConsolidations = [Collections.Generic.List[object]]::new()
    $viewportPollStarts = [Collections.Generic.List[object]]::new()
    $anchorSets = [Collections.Generic.List[object]]::new()
    $anchorPhases = [Collections.Generic.List[object]]::new()
    $anchorRejects = [Collections.Generic.List[object]]::new()
    $gestureStarts = [Collections.Generic.List[object]]::new()
    $rawOverlayRecords = 0
    $rawQualityRecords = 0
    $knownRenderSourceRecords = 0

    foreach ($line in $lines) {
        $time = Get-TraceTime $line
        if ($line.TrimStart().StartsWith('{')) {
            try {
                $record = $line | ConvertFrom-Json
                if ($null -ne $record.message) {
                    $line = [string] $record.message
                    if ($null -ne $record.wallMillis) {
                        $time = [DateTimeOffset]::FromUnixTimeMilliseconds(
                                [long] $record.wallMillis).UtcDateTime
                    }
                }
            } catch {
                # Keep malformed/non-Censor-Lab JSON as a raw line for legacy parsing.
            }
        }
        if ($line.Contains('OVERLAY_PUBLISH ')) {
            $rawOverlayRecords++
            if ($line -match ' renderSourceKnown=true ') { $knownRenderSourceRecords++ }
            # Provenance is additive telemetry; keep the legacy positional frame schema intact.
            $line = $line -replace ' renderSourceKnown=(?:true|false) renderSourceTime=-?\d+ renderSourceBias=-?\d+,-?\d+$', ''
        }
        if ($line -match '\b(?:QUALITY_READY|QUALITY_STREAM_CACHE|QUALITY_CACHE)\b') {
            $rawQualityRecords++
        }
        if ($line -match '\bQUALITY_READY\b') {
            $knownQualityFields = @('id', 'scrollId', 'captureAgeMs', 'bitmapPrepareMs',
                'inferenceMs', 'preprocessMs', 'runtimeMs', 'postprocessMs', 'afterMotionMs',
                'rawVisual', 'acceptedVisual', 'tile', 'tileBounds', 'renderAuthority',
                'transactionStatus', 'backfillStatus', 'backfillMatched', 'backfillInserted',
                'backfillPromoted', 'backfillRefined', 'oldFrame', 'sourceGeneration',
                'sourceFastSequence', 'currentFastSequence', 'dropped', 'staleDropped',
                'preemptions', 'cancelledRuns')
            foreach ($field in [regex]::Matches($line, '\b(\w+)=')) {
                if ($field.Groups[1].Value -notin $knownQualityFields) {
                    throw 'Unsupported QUALITY_READY field; parsing is incomplete.'
                }
            }
        }
        if ($null -ne $time) {
            if ($null -eq $firstTime) { $firstTime = $time }
            $lastTime = $time
        }

        if ($line -match 'OVERLAY_PUBLISH pass=(\S+) scrollId=(\d+) captureAgeMs=(\d+) inferenceMs=(\d+) preprocessMs=(\d+) runtimeMs=(\d+) postprocessMs=(\d+) afterMotionMs=(-?\d+).*? tracks=(\d+) rawVisual=(\d+) cachedQuality=(\d+) qualityOnly=(\d+)(?: identityRealtimeLinked=(\d+) identityQualityLinked=(\d+) identityFused=(\d+) identityCarriedQuality=(\d+) identityUnlinkedQuality=(\d+))? geometryMatched=(\d+) geometryChanged=(\d+) maxCenterDeltaPx=(\d+) maxSizeDeltaPx=(\d+) dropped=(\d+)(?: duplicatesSuppressed=(\d+))?(?: renderHandOffs=(\d+))?(?: qualityOnlyTracks=(\d+) renderTracks=(\d+))?(?: visibleRenderTracks=(\d+))?(?: qualityActive=(true|false))?(?: qualityConcurrent=(true|false))?(?: qualityCacheReusesSkipped=(\d+))?(?: qualityPreemptions=(\d+) qualityCancelledRuns=(\d+))?$') {
            $item = [pscustomobject]@{
                time = $time
                pass = $Matches[1]
                scrollId = [int] $Matches[2]
                captureAge = [int] $Matches[3]
                inference = [int] $Matches[4]
                preprocess = [int] $Matches[5]
                runtime = [int] $Matches[6]
                postprocess = [int] $Matches[7]
                afterMotion = [int] $Matches[8]
                tracks = [int] $Matches[9]
                rawVisual = [int] $Matches[10]
                cachedQuality = [int] $Matches[11]
                qualityOnly = [int] $Matches[12]
                identityRealtimeLinked = if ($Matches[13]) { [int] $Matches[13] } else { 0 }
                identityQualityLinked = if ($Matches[14]) { [int] $Matches[14] } else { 0 }
                identityFused = if ($Matches[15]) { [int] $Matches[15] } else { 0 }
                identityCarriedQuality = if ($Matches[16]) { [int] $Matches[16] } else { 0 }
                identityUnlinkedQuality = if ($Matches[17]) { [int] $Matches[17] } else { 0 }
                geometryMatched = [int] $Matches[18]
                geometryChanged = [int] $Matches[19]
                maxCenterDelta = [int] $Matches[20]
                maxSizeDelta = [int] $Matches[21]
                dropped = [int] $Matches[22]
                duplicatesSuppressed = if ($Matches[23]) { [int] $Matches[23] } else { 0 }
                renderHandOffs = if ($Matches[24]) { [int] $Matches[24] } else { 0 }
                qualityOnlyTracks = if ($Matches[25]) { [int] $Matches[25] } else { 0 }
                renderTracks = if ($Matches[26]) { [int] $Matches[26] } else { [int] $Matches[9] }
                visibleRenderTracks = if ($Matches[27]) { [int] $Matches[27] }
                    elseif ($Matches[26]) { [int] $Matches[26] }
                    else { [int] $Matches[9] }
                qualityActive = $Matches[28] -eq 'true'
                qualityConcurrent = $Matches[29] -eq 'true'
                qualityCacheReusesSkipped = if ($Matches[30]) { [long] $Matches[30] } else { 0L }
                qualityPreemptions = if ($Matches[31]) { [long] $Matches[31] } else { 0L }
                qualityCancelledRuns = if ($Matches[32]) { [long] $Matches[32] } else { 0L }
            }
            $publishes.Add($item)
            if ($item.pass -eq 'fast' -and $null -ne $time) { $fastPublishTimes.Add($time) }
            continue
        }
        if ($line -match '(?:CensorMotion(?:\(\d+\))?: )?INPUT .*source=(\S+).*prediction=(-?\d+),(-?\d+) predictionPeakMs=(\d+)') {
            $motionInputs.Add([pscustomobject]@{
                time = $time
                source = $Matches[1]
                predictionAbs = [math]::Abs([int] $Matches[2]) + [math]::Abs([int] $Matches[3])
                predictionPeak = [int] $Matches[4]
            })
            continue
        }
        if ($line -match 'CONSOLIDATE input=(\d+) output=(\d+) merged=(\d+) rawLive=(\d+) selectedLive=(\d+) rawCache=(\d+) selectedCache=(\d+)\s*$') {
            if (([int] $Matches[1] - [int] $Matches[2] -ne [int] $Matches[3]) -or
                    ([int] $Matches[4] + [int] $Matches[6] -ne [int] $Matches[1])) {
                throw 'Inconsistent CONSOLIDATE counts; parsing is incomplete.'
            }
            $renderConsolidations.Add([pscustomobject]@{
                time = $time
                input = [int] $Matches[1]
                output = [int] $Matches[2]
                merged = [int] $Matches[3]
                live = [int] $Matches[5]
                cached = [int] $Matches[7]
            })
            continue
        }
        if ($line -match 'CONSOLIDATE input=(\d+) output=(\d+) merged=(\d+) live=(\d+) cached=(\d+)\s*$') {
            $renderConsolidations.Add([pscustomobject]@{
                time = $time
                input = [int] $Matches[1]
                output = [int] $Matches[2]
                merged = [int] $Matches[3]
                live = [int] $Matches[4]
                cached = [int] $Matches[5]
            })
            continue
        }
        if ($line -match '\bCONSOLIDATE\b') {
            throw 'Unsupported CONSOLIDATE record; parsing is incomplete.'
        }
        if ($line -match 'QUALITY_CONCURRENCY action=(\S+) fastRuntimeMs=(\d+) idleRuntimeEmaMs=(\d+) pauseUntilUptimeMs=(\d+)') {
            $qualityConcurrency.Add([pscustomobject]@{
                time = $time
                action = $Matches[1]
                fastRuntime = [int] $Matches[2]
                idleRuntimeEma = [int] $Matches[3]
                pauseUntil = [long] $Matches[4]
            })
            continue
        }
        if ($line -match 'VIEWPORT_POLL_START started=(true|false) anchors=(\d+).*generation=(\d+)') {
            $viewportPollStarts.Add([pscustomobject]@{
                time = $time
                started = $Matches[1] -eq 'true'
                anchors = [int] $Matches[2]
                generation = [long] $Matches[3]
            })
            continue
        }
        if ($line -match 'QUALITY_LATE_STAGE sourceFastSequence=(\d+) sourceGeneration=(\d+) regions=(\d+) replaced=(true|false) captureAgeMs=(\d+)') {
            $qualityLateStages.Add([pscustomobject]@{
                time = $time
                sourceSequence = [long] $Matches[1]
                generation = [long] $Matches[2]
                regions = [int] $Matches[3]
                replaced = $Matches[4] -eq 'true'
                captureAge = [int] $Matches[5]
            })
            continue
        }
        if ($line -match 'QUALITY_IMMEDIATE_PRESENT\b') {
            if ($null -eq $time -or $line -notmatch 'QUALITY_IMMEDIATE_PRESENT sourceFastSequence=(\d+) regions=(\d+) readyToPresentMs=(\d+) captureAgeMs=(\d+)\s*$') {
                throw 'Incomplete QUALITY_IMMEDIATE_PRESENT record; cannot report valid delivery metrics.'
            }
            $qualityImmediatePresents.Add([pscustomobject]@{
                time = $time
                sourceSequence = [long] $Matches[1]
                regions = [int] $Matches[2]
                readyToPresent = [long] $Matches[3]
                captureAge = [long] $Matches[4]
            })
            continue
        }
        if ($line -match 'QUALITY_LATE_PRESENT sourceFastSequence=(\d+) consumerFastSequence=(\d+) sourceGeneration=(\d+) regions=(\d+) readyToPresentMs=(\d+)') {
            $qualityLatePresents.Add([pscustomobject]@{
                time = $time
                sourceSequence = [long] $Matches[1]
                consumerSequence = [long] $Matches[2]
                generation = [long] $Matches[3]
                regions = [int] $Matches[4]
                readyToPresent = [int] $Matches[5]
            })
            continue
        }
        if ($line -match 'QUALITY_RETAINED_PRESENT\b') {
            $rawQualityRetainedPresents++
            if ($null -eq $time -or $line -notmatch 'QUALITY_RETAINED_PRESENT sourceFastSequence=(\d+) consumerFastSequence=(\d+) regions=(\d+) captureAgeMs=(\d+)\s*$') {
                throw 'Incomplete QUALITY_RETAINED_PRESENT record; cannot report valid reuse metrics.'
            }
            $qualityRetainedPresents.Add([pscustomobject]@{
                time = $time
                sourceSequence = [long] $Matches[1]
                consumerSequence = [long] $Matches[2]
                regions = [int] $Matches[3]
                captureAge = [long] $Matches[4]
            })
            continue
        }
        if ($line -match 'QUALITY_LATE_DROP reason=(\S+)') {
            $qualityLateDrops.Add([pscustomobject]@{ time = $time; reason = $Matches[1] })
            continue
        }
        if ($line -match 'CensorAnchorPoll(?:\(\d+\))?: ANCHOR_SET count=(\d+) candidates=(\d+) visited=(\d+) selectionMs=(\d+)') {
            $anchorSets.Add([pscustomobject]@{
                time = $time
                count = [int] $Matches[1]
                candidates = [int] $Matches[2]
                visited = [int] $Matches[3]
                selection = [int] $Matches[4]
            })
            continue
        }
        if ($line -match 'CensorAnchorPoll(?:\(\d+\))?: ANCHOR_PHASE dx=(-?\d+) dy=(-?\d+) latencyMs=(\d+) intervalMs=(\d+) contributors=(\d+) refreshed=(\d+) rejected=(\d+)') {
            $anchorPhases.Add([pscustomobject]@{
                time = $time
                displacement = [math]::Abs([int] $Matches[1]) + [math]::Abs([int] $Matches[2])
                latency = [int] $Matches[3]
                interval = [int] $Matches[4]
                contributors = [int] $Matches[5]
                refreshed = [int] $Matches[6]
                rejected = [int] $Matches[7]
            })
            continue
        }
        if ($line -match 'CensorAnchorPoll(?:\(\d+\))?: ANCHOR_REJECT .*reason=(\S+)') {
            $anchorRejects.Add([pscustomobject]@{ time = $time; reason = $Matches[1] })
            continue
        }
        if ($line -match '(?:CensorMotion(?:\(\d+\))?: )?DRAW .*inputToDrawMs=(\d+).*?(?:viewportLead=(-?\d+),(-?\d+))?(?: renderTickMs=(\d+))?$') {
            $leadX = if ($Matches[2]) { [int] $Matches[2] } else { 0 }
            $leadY = if ($Matches[3]) { [int] $Matches[3] } else { 0 }
            $drawLatency = [int] $Matches[1]
            $tick = if ($Matches[4]) { [int] $Matches[4] } else { $null }
            $explicitDrawClock = $line -match ' drawClock=uptime(?: |$)'
            $presentationLatency = if ($line -match ' inputToPresentationMs=(\d+)(?: |$)') {
                [int] $Matches[1]
            } else { $null }
            $motionDraws.Add([pscustomobject]@{
                time = $time
                inputToDraw = $drawLatency
                explicitDrawClock = $explicitDrawClock
                inputToPresentation = $presentationLatency
                leadAbs = [math]::Abs($leadX) + [math]::Abs($leadY)
                renderTick = $tick
            })
            continue
        }
        if ($line -match '(?:CensorMotion(?:\(\d+\))?: )?SETTLED .*inputToSettledMs=(\d+)') {
            $motionSettles.Add([pscustomobject]@{
                time = $time
                inputToSettled = [int] $Matches[1]
            })
            continue
        }
        if ($line -match 'QUALITY_CACHE scrollId=(\d+) captureAgeMs=(\d+) inferenceMs=(\d+) preprocessMs=(\d+) runtimeMs=(\d+) postprocessMs=(\d+) afterMotionMs=(-?\d+) rawVisual=(\d+) stableVisual=(\d+)(?: identityLinked=(\d+) identityUnlinked=(\d+))?(?: pendingVisual=(\d+) deferredUnlinked=(\d+)(?: supplementedTracks=(\d+))?.*? confirmationRequested=(true|false))?') {
            $qualityItem = [pscustomobject]@{
                time = $time
                scrollId = [int] $Matches[1]
                captureAge = [int] $Matches[2]
                inference = [int] $Matches[3]
                preprocess = [int] $Matches[4]
                runtime = [int] $Matches[5]
                postprocess = [int] $Matches[6]
                afterMotion = [int] $Matches[7]
                rawVisual = [int] $Matches[8]
                stableVisual = [int] $Matches[9]
                identityLinked = if ($Matches[10]) { [int] $Matches[10] } else { 0 }
                identityUnlinked = if ($Matches[11]) { [int] $Matches[11] } else { 0 }
                pendingVisual = if ($Matches[12]) { [int] $Matches[12] } else { 0 }
                deferredUnlinked = if ($Matches[13]) { [int] $Matches[13] } else { 0 }
                supplementedTracks = if ($Matches[14]) { [int] $Matches[14] } else { 0 }
                confirmationRequested = $Matches[15] -eq 'true'
                batchState = 'UNKNOWN'
                cacheGeneration = $null
            }
            if ($line -match 'qualityBatchState=(\S+) cacheGeneration=(\d+)') {
                $qualityItem.batchState = $Matches[1]
                $qualityItem.cacheGeneration = [int] $Matches[2]
            }
            $quality.Add($qualityItem)
            continue
        }
        if ($line -match 'QUALITY_STREAM_CACHE scrollId=(\d+) captureAgeMs=(\d+)(?: bitmapPrepareMs=(\d+))? inferenceMs=(\d+) preprocessMs=(\d+) runtimeMs=(\d+) postprocessMs=(\d+) afterMotionMs=(-?\d+) rawVisual=(\d+) stableVisual=(\d+) pendingVisual=(\d+)(?: completeScene=(true|false)(?: cachePreserved=(true|false))? identityLinked=(\d+) identityUnlinked=(\d+) retiredQualityTracks=(\d+))? sourceGeneration=(\d+) cacheGeneration=(\d+)(?: sourceFastSequence=\d+ currentFastSequence=\d+)? reproject=(-?\d+),(-?\d+) dropped=(\d+)') {
            $streamingQuality.Add([pscustomobject]@{
                time = $time
                scrollId = [int] $Matches[1]
                captureAge = [int] $Matches[2]
                bitmapPrepare = if ($Matches[3]) { [int] $Matches[3] } else { 0 }
                inference = [int] $Matches[4]
                preprocess = [int] $Matches[5]
                runtime = [int] $Matches[6]
                postprocess = [int] $Matches[7]
                afterMotion = [int] $Matches[8]
                rawVisual = [int] $Matches[9]
                stableVisual = [int] $Matches[10]
                pendingVisual = [int] $Matches[11]
                completeScene = $Matches[12] -eq 'true'
                cachePreserved = $Matches[13] -eq 'true'
                identityLinked = if ($Matches[14]) { [int] $Matches[14] } else { 0 }
                identityUnlinked = if ($Matches[15]) { [int] $Matches[15] } else { 0 }
                retiredQualityTracks = if ($Matches[16]) { [int] $Matches[16] } else { 0 }
                sourceGeneration = [int] $Matches[17]
                cacheGeneration = [int] $Matches[18]
                reprojectAbs = [math]::Abs([int] $Matches[19]) +
                    [math]::Abs([int] $Matches[20])
                dropped = [int] $Matches[21]
            })
            continue
        }
        if ($line -match 'QUALITY_READY id=(\S+) scrollId=(\d+) captureAgeMs=(\d+) bitmapPrepareMs=(\d+) inferenceMs=(\d+) preprocessMs=(\d+) runtimeMs=(\d+) postprocessMs=(\d+) afterMotionMs=(-?\d+) rawVisual=(\d+) acceptedVisual=(\d+).*?(?:(?:transactionStatus=(\S+))|(?:renderAuthority=(\S+) backfillStatus=(\S+) backfillMatched=(\d+) backfillInserted=(\d+) backfillPromoted=(\d+) backfillRefined=(\d+))).*? dropped=(\d+)') {
            $streamingQuality.Add([pscustomobject]@{
                time = $time
                sceneId = $Matches[1]
                scrollId = [int] $Matches[2]
                captureAge = [int] $Matches[3]
                bitmapPrepare = [int] $Matches[4]
                inference = [int] $Matches[5]
                preprocess = [int] $Matches[6]
                runtime = [int] $Matches[7]
                postprocess = [int] $Matches[8]
                afterMotion = [int] $Matches[9]
                rawVisual = [int] $Matches[10]
                stableVisual = [int] $Matches[11]
                pendingVisual = 0
                completeScene = $false
                cachePreserved = $false
                identityLinked = 0
                identityUnlinked = 0
                retiredQualityTracks = 0
                sourceGeneration = 0
                cacheGeneration = 0
                reprojectAbs = 0
                dropped = [int] $Matches[19]
                transactionStatus = if ($Matches[12]) {
                    $Matches[12]
                } else {
                    "SHADOW_$($Matches[14])"
                }
                renderAuthority = if ($Matches[13]) { $Matches[13] } else { "legacy" }
                backfillStatus = if ($Matches[14]) { $Matches[14] } else { "LEGACY" }
                backfillMatched = if ($Matches[15]) { [int] $Matches[15] } else { 0 }
                backfillInserted = if ($Matches[16]) { [int] $Matches[16] } else { 0 }
                backfillPromoted = if ($Matches[17]) { [int] $Matches[17] } else { 0 }
                backfillRefined = if ($Matches[18]) { [int] $Matches[18] } else { 0 }
            })
            continue
        }
        if ($line -match 'SCENE_BEGIN id=(\S+) mode=(\S+) quality(Expected|Observed)=(true|false)(?: qualityJoinExpected=(true|false))? joinDeadlineUptimeMs=(\d+) visibleDeadlineUptimeMs=(\d+)') {
            $qualityObserved = $Matches[4] -eq 'true'
            $qualityJoinExpected = if ($Matches[5]) {
                $Matches[5] -eq 'true'
            } else {
                $qualityObserved
            }
            $sceneBegins.Add([pscustomobject]@{
                time = $time
                id = $Matches[1]
                mode = $Matches[2]
                qualityObserved = $qualityObserved
                qualityExpected = $qualityJoinExpected
                joinDeadline = [long] $Matches[6]
                visibleDeadline = [long] $Matches[7]
            })
            continue
        }
        if ($line -match 'SCENE_COMMIT id=(\S+) kind=(\S+) captureAgeMs=(\d+) fast=(\d+) quality=(\d+) renderTracks=(\d+) visibleDeadlineMiss=(true|false)') {
            $sceneCommits.Add([pscustomobject]@{
                time = $time
                id = $Matches[1]
                kind = $Matches[2]
                captureAge = [int] $Matches[3]
                fast = [int] $Matches[4]
                quality = [int] $Matches[5]
                renderTracks = [int] $Matches[6]
                visibleDeadlineMiss = $Matches[7] -eq 'true'
            })
            continue
        }
        if ($line -match 'SCENE_TIMEOUT id=(\S+) status=(\S+) captureAgeMs=(\d+)') {
            $sceneTimeouts.Add([pscustomobject]@{
                time = $time
                id = $Matches[1]
                status = $Matches[2]
                captureAge = [int] $Matches[3]
            })
            continue
        }
        if ($line -match 'SCENE_LATE_DROP id=(\S+) lane=(\S+)(?: status=(\S+)| reason=(\S+))') {
            $sceneLateDrops.Add([pscustomobject]@{
                time = $time
                id = $Matches[1]
                lane = $Matches[2]
                reason = if ($Matches[3]) { $Matches[3] } else { $Matches[4] }
            })
            continue
        }
        if ($line -match 'SCENE_QUEUE_DROP id=(\S+) reason=(\S+)') {
            $sceneLateDrops.Add([pscustomobject]@{
                time = $time
                id = $Matches[1]
                lane = 'presenter'
                reason = $Matches[2]
            })
            continue
        }
        if ($line -match 'SCENE_INVALIDATE id=(\S+) reason=(\S+)') {
            $sceneInvalidations.Add([pscustomobject]@{
                time = $time
                id = $Matches[1]
                reason = $Matches[2]
            })
            continue
        }
        if ($line -match 'WORLD_CACHE_QUERY source=(\S+) entries=(\d+) inserted=(\d+) updated=(\d+) evicted=(\d+) viewportReset=(true|false) candidates=(\d+)') {
            $worldCacheQueries.Add([pscustomobject]@{
                time = $time
                source = $Matches[1]
                entries = [int] $Matches[2]
                inserted = [int] $Matches[3]
                updated = [int] $Matches[4]
                evicted = [int] $Matches[5]
                viewportReset = $Matches[6] -eq 'true'
                candidates = [int] $Matches[7]
            })
            continue
        }
        if ($line -match 'WORLD_CACHE_INVALIDATE reason=(\S+) removed=(\d+) documentEpoch=(\d+)') {
            $worldCacheInvalidations.Add([pscustomobject]@{
                time = $time
                reason = $Matches[1]
                removed = [int] $Matches[2]
                documentEpoch = [long] $Matches[3]
            })
            continue
        }
        if ($line -match 'WORLD_CACHE_REENTRY candidates=(\d+) generation=(\d+) camera=(-?\d+),(-?\d+) documentEpoch=(\d+)') {
            $worldCacheReentries.Add([pscustomobject]@{
                time = $time
                candidates = [int] $Matches[1]
                generation = [long] $Matches[2]
                cameraX = [long] $Matches[3]
                cameraY = [long] $Matches[4]
                documentEpoch = [long] $Matches[5]
            })
            continue
        }
        if ($line -match 'QUALITY_STREAM_DROP reason=(\S+) sourceGeneration=(\d+) currentGeneration=(\d+)') {
            $streamingQualityDrops.Add([pscustomobject]@{
                time = $time
                reason = $Matches[1]
                sourceGeneration = [int] $Matches[2]
                currentGeneration = [int] $Matches[3]
            })
            continue
        }
        if ($line -match 'QUALITY_BACKFILL_OFFER status=(\S+)') {
            $qualityBackfillOffers.Add([pscustomobject]@{
                time = $time
                status = $Matches[1]
            })
            continue
        }
        if ($line -match 'QUALITY_BACKFILL_DROP reason=(\S+)') {
            $qualityBackfillDrops.Add([pscustomobject]@{
                time = $time
                reason = $Matches[1]
            })
            continue
        }
        if ($line -match 'QUALITY_PREEMPT reason=(\S+) count=(\d+)') {
            $qualityPreempts.Add([pscustomobject]@{
                time = $time
                reason = $Matches[1]
                count = [long] $Matches[2]
            })
            continue
        }
        if ($line -match 'QUALITY_DROP reason=(\S+) cancellationMs=(\d+) cancelledRuns=(\d+) preemptions=(\d+)') {
            $qualityCancellations.Add([pscustomobject]@{
                time = $time
                reason = $Matches[1]
                cancellation = [long] $Matches[2]
                cancelledRuns = [long] $Matches[3]
                preemptions = [long] $Matches[4]
            })
            continue
        }
        if ($line -match 'QUALITY_GATE_SKIP reason=(\S+).*activeLane=(\S+)') {
            $qualityGateSkips.Add([pscustomobject]@{
                time = $time
                reason = $Matches[1]
                activeLane = $Matches[2]
            })
            continue
        }
        if ($line -match 'INFERENCE_GATE lane=fast waitMs=(\d+)') {
            $fastGateWaits.Add([pscustomobject]@{
                time = $time
                wait = [long] $Matches[1]
            })
            continue
        }
        if ($line -match 'QUALITY_WINDOW action=(\S+) activeMs=(\d+)') {
            $qualityWindows.Add([pscustomobject]@{
                time = $time
                action = $Matches[1]
                active = [long] $Matches[2]
            })
            continue
        }
        if ($line -match 'INFERENCE_NATIVE_BEGIN lane=(fast|quality) runId=(\d+) provider=(\S+) uptimeNanos=(\d+)') {
            $nativeBegins[$Matches[2]] = [pscustomobject]@{
                lane = $Matches[1]
                runId = [long] $Matches[2]
                provider = $Matches[3]
                startedNanos = [long] $Matches[4]
            }
            continue
        }
        if ($line -match 'QUALITY_PREPARE_BEGIN sourceFastSequence=(\d+) generation=(\d+)(?: oldFrame=(?:true|false))? uptimeNanos=(\d+)') {
            $qualityPrepareBegins[$Matches[1]] = [pscustomobject]@{
                sourceFastSequence = [long] $Matches[1]
                generation = [long] $Matches[2]
                startedNanos = [long] $Matches[3]
            }
            continue
        }
        if ($line -match 'QUALITY_PREPARE_END sourceFastSequence=(\d+) generation=(\d+)(?: oldFrame=(?:true|false))? durationMs=(\d+) uptimeNanos=(\d+)') {
            $begin = $qualityPrepareBegins[$Matches[1]]
            if ($null -ne $begin) {
                $qualityPrepares.Add([pscustomobject]@{
                    sourceFastSequence = $begin.sourceFastSequence
                    generation = $begin.generation
                    duration = [long] $Matches[3]
                    startedNanos = $begin.startedNanos
                    endedNanos = [long] $Matches[4]
                })
                $qualityPrepareBegins.Remove($Matches[1])
            }
            continue
        }
        if ($line -match 'INFERENCE_NATIVE_END lane=(fast|quality) runId=(\d+) status=(\S+) durationMs=(\d+) uptimeNanos=(\d+)') {
            $begin = $nativeBegins[$Matches[2]]
            if ($null -ne $begin) {
                $nativeRuns.Add([pscustomobject]@{
                    lane = $begin.lane
                    runId = $begin.runId
                    provider = $begin.provider
                    status = $Matches[3]
                    duration = [long] $Matches[4]
                    startedNanos = $begin.startedNanos
                    endedNanos = [long] $Matches[5]
                })
                $nativeBegins.Remove($Matches[2])
            }
            continue
        }
        if ($line -match 'STARTUP session=(\d+) phase=(\S+)') {
            $startupEvents.Add([pscustomobject]@{
                time = $time
                session = [long] $Matches[1]
                phase = $Matches[2]
                uptimeMs = if ($line -match 'uptimeMs=(\d+)') { [long] $Matches[1] } else { $null }
                durationMs = if ($line -match 'durationMs=(\d+)') { [long] $Matches[1] } else { $null }
            })
            continue
        }
        if ($line -match 'QUALITY_SUPPLEMENT_PUBLISH scrollId=(\d+) added=(\d+).*? afterMotionMs=(\d+)') {
            $qualitySupplements.Add([pscustomobject]@{
                time = $time
                scrollId = [int] $Matches[1]
                added = [int] $Matches[2]
                afterMotion = [int] $Matches[3]
            })
            continue
        }
        if ($line -match 'QUALITY_RETIRE_PUBLISH scrollId=(\d+) added=(\d+) retired=(\d+) afterMotionMs=(\d+)') {
            $qualityRetires.Add([pscustomobject]@{
                time = $time
                scrollId = [int] $Matches[1]
                added = [int] $Matches[2]
                retired = [int] $Matches[3]
                afterMotion = [int] $Matches[4]
            })
            continue
        }
        if ($line -match 'CAPTURE_PHASE requestToCaptureMs=(\d+) callbackDelayMs=(\d+) requestScroll=(-?\d+),(-?\d+) captureScroll=(-?\d+),(-?\d+) requestGeneration=(\d+) captureGeneration=(\d+) timelineResolved=(true|false)') {
            $capturePhases.Add([pscustomobject]@{
                time = $time
                requestToCapture = [int] $Matches[1]
                callbackDelay = [int] $Matches[2]
                scrollDeltaAbs = [math]::Abs(([int] $Matches[5]) - ([int] $Matches[3])) +
                    [math]::Abs(([int] $Matches[6]) - ([int] $Matches[4]))
                generationDelta = ([int] $Matches[8]) - ([int] $Matches[7])
                timelineResolved = $Matches[9] -eq 'true'
            })
            continue
        }
        if ($line.Contains('SCROLL_METADATA ')) {
            $rawScrollMetadata++
            if ($line -match 'SCROLL_METADATA schema=1 sourceUptimeMs=(\d+) motionToken=([0-9a-f]+) classKind=([0-6]) offset=(-?\d+),(-?\d+) max=(-?\d+),(-?\d+) indices=(-?\d+),(-?\d+),(-?\d+) explicit=(-?\d+),(-?\d+) sourcePresent=(true|false) nodes=(\d+) ownerDepth=(-?\d+) ownerKind=([0-3]) traversalFailed=(true|false)$') {
                $scrollMetadata.Add([pscustomobject]@{
                    sourceUptimeMs = [long] $Matches[1]; motionToken = $Matches[2]; classKind = [int] $Matches[3]
                    offsetX = [int] $Matches[4]; offsetY = [int] $Matches[5]
                    maxX = [int] $Matches[6]; maxY = [int] $Matches[7]
                    fromIndex = [int] $Matches[8]; toIndex = [int] $Matches[9]; itemCount = [int] $Matches[10]
                    explicitX = [int] $Matches[11]; explicitY = [int] $Matches[12]
                    sourcePresent = $Matches[13] -eq 'true'; nodes = [int] $Matches[14]
                    ownerDepth = [int] $Matches[15]; ownerKind = [int] $Matches[16]
                    traversalFailed = $Matches[17] -eq 'true'
                })
            }
            continue
        }
        if ($line -match 'SCROLL_EVENT id=(\d+) source=(\S+) gapMs=(\d+)(?: eventAgeMs=(\d+))? rawDx=(-?\d+) rawDy=(-?\d+) dx=(-?\d+) dy=(-?\d+)(?: evidence=(\S+) adjustedPx=(\d+) amplified=(true|false))?') {
            $rawX = [int] $Matches[5]
            $rawY = [int] $Matches[6]
            $appliedX = [int] $Matches[7]
            $appliedY = [int] $Matches[8]
            $scrolls.Add([pscustomobject]@{
                time = $time
                id = [int] $Matches[1]
                source = $Matches[2]
                gap = [int] $Matches[3]
                eventAge = if ($Matches[4]) { [int] $Matches[4] } else { $null }
                rawX = $rawX
                rawY = $rawY
                appliedX = $appliedX
                appliedY = $appliedY
                rawAbs = [math]::Abs($rawX) + [math]::Abs($rawY)
                appliedAbs = [math]::Abs($appliedX) + [math]::Abs($appliedY)
                evidence = if ($Matches[9]) { $Matches[9] } else { 'unknown' }
                adjusted = if ($Matches[10]) { [int] $Matches[10] } else {
                    [math]::Abs($rawX - $appliedX) + [math]::Abs($rawY - $appliedY)
                }
                explicitlyAmplified = $Matches[11] -eq 'true'
            })
            continue
        }
        if ($line -match 'SubHubReplay(?:\(\d+\))?: GESTURE_START index=(\d+) kind=(\S+) durationMs=(\d+) dx=(-?\d+) dy=(-?\d+)') {
            $gestureStarts.Add([pscustomobject]@{
                time = $time
                index = [int] $Matches[1]
                kind = $Matches[2]
                duration = [int] $Matches[3]
                deltaX = [int] $Matches[4]
                deltaY = [int] $Matches[5]
            })
            continue
        }
        if ($line -match 'CensorReplay(?:\(\d+\))?: GESTURE_START target=\d+ index=(\d+) id=\S+ kind=(\S+) durationMs=(\d+) dx=(-?\d+) dy=(-?\d+)') {
            $gestureStarts.Add([pscustomobject]@{
                time = $time
                index = [int] $Matches[1]
                kind = $Matches[2]
                duration = [int] $Matches[3]
                deltaX = [int] $Matches[4]
                deltaY = [int] $Matches[5]
            })
            continue
        }
        if ($line -match 'TEXT_SCAN (accepted|discarded=\S+).*durationMs=(\d+)') {
            $status = $Matches[1]
            if ($status -like 'discarded=*') { $status = $status.Substring(10) }
            $textScans.Add([pscustomobject]@{
                time = $time
                status = $status
                duration = [int] $Matches[2]
                bestEffort = $line -match 'bestEffort=true'
                candidates = if ($line -match 'candidates=(\d+)') { [int] $Matches[1] } else { 0 }
            })
            continue
        }
        if ($line -match 'TEXT_CONFIRM targeted.*durationMs=(\d+)') {
            $textConfirms.Add([pscustomobject]@{ time = $time; duration = [int] $Matches[1] })
            continue
        }
        if ($line -match 'TEXT_PUBLISH source=(\S+) regions=(\d+)') {
            $textPublishes.Add([pscustomobject]@{
                time = $time
                source = $Matches[1]
                regions = [int] $Matches[2]
            })
        }
    }

    $fast = @($publishes | Where-Object pass -eq 'fast')
    $activeFast = @($fast | Where-Object { $_.scrollId -gt 0 -and $_.afterMotion -ge 0 -and $_.afterMotion -le 500 })
    $settledFast = @($fast | Where-Object { $_.scrollId -eq 0 -or $_.afterMotion -gt 500 })
    $fastWithQuality = @($fast | Where-Object qualityActive)
    $fastWithoutQuality = @($fast | Where-Object { -not $_.qualityActive })
    $fastConcurrentQuality = @($fast | Where-Object qualityConcurrent)
    $intervals = for ($index = 1; $index -lt $fastPublishTimes.Count; $index++) {
        ($fastPublishTimes[$index] - $fastPublishTimes[$index - 1]).TotalMilliseconds
    }
    $rawAbs = ($scrolls | Measure-Object -Property rawAbs -Sum).Sum
    $appliedAbs = ($scrolls | Measure-Object -Property appliedAbs -Sum).Sum
    $rawNetX = ($scrolls | Measure-Object -Property rawX -Sum).Sum
    $rawNetY = ($scrolls | Measure-Object -Property rawY -Sum).Sum
    $appliedNetX = ($scrolls | Measure-Object -Property appliedX -Sum).Sum
    $appliedNetY = ($scrolls | Measure-Object -Property appliedY -Sum).Sum
    $amplified = @($scrolls | Where-Object { $_.appliedAbs -gt $_.rawAbs }).Count
    $amplifiedBySource = Get-GroupCounts @(
        $scrolls | Where-Object { $_.appliedAbs -gt $_.rawAbs }) 'source'
    $dropNonzero = @($fast | Where-Object dropped -gt 0).Count
    $geometryJumps = @($fast | Where-Object maxCenterDelta -ge 100).Count
    $activeDuplicateLike = @($activeFast | Where-Object {
            $_.cachedQuality -eq 0 -and $_.tracks -ge ($_.rawVisual + 2) })
    $swipeStarts = @($gestureStarts | Where-Object kind -eq 'swipe')
    $gestureToScroll = foreach ($gesture in $swipeStarts) {
        $deadline = $gesture.time.AddMilliseconds($gesture.duration + 600)
        $firstScroll = $scrolls | Where-Object {
            $_.time -ge $gesture.time -and $_.time -le $deadline -and $_.appliedAbs -gt 0
        } | Sort-Object time | Select-Object -First 1
        if ($null -ne $firstScroll) {
            [pscustomobject]@{
                index = $gesture.index
                latency = ($firstScroll.time - $gesture.time).TotalMilliseconds
                source = $firstScroll.source
            }
        }
    }
    $durationSeconds = if ($null -ne $firstTime -and $null -ne $lastTime) {
        [math]::Round(($lastTime - $firstTime).TotalSeconds, 3)
    } else { 0 }
    $fastNativeRuns = @($nativeRuns | Where-Object lane -eq 'fast')
    $qualityNativeRuns = @($nativeRuns | Where-Object lane -eq 'quality')
    $nativeOverlapPairs = @(
        foreach ($fastRun in $fastNativeRuns) {
            foreach ($qualityRun in $qualityNativeRuns) {
                if (($fastRun.startedNanos -lt $qualityRun.endedNanos) -and
                        ($qualityRun.startedNanos -lt $fastRun.endedNanos)) {
                    [pscustomobject]@{
                        fastRunId = $fastRun.runId
                        qualityRunId = $qualityRun.runId
                    }
                }
            }
        })
    $prepareFastOverlapPairs = @(
        foreach ($fastRun in $fastNativeRuns) {
            foreach ($prepare in $qualityPrepares) {
                if (($fastRun.startedNanos -lt $prepare.endedNanos) -and
                        ($prepare.startedNanos -lt $fastRun.endedNanos)) {
                    [pscustomobject]@{
                        fastRunId = $fastRun.runId
                        sourceFastSequence = $prepare.sourceFastSequence
                    }
                }
            }
        })
    $startupSessionSummaries = @(
        foreach ($group in @($startupEvents | Where-Object session -gt 0 |
                Group-Object session | Sort-Object Name)) {
            $activation = $group.Group | Where-Object phase -eq 'activation' |
                Select-Object -First 1
            $firstOverlay = $group.Group | Where-Object phase -eq 'first-fast-overlay' |
                Select-Object -First 1
            $qualityBegin = $group.Group | Where-Object phase -eq 'quality-init-begin' |
                Select-Object -First 1
            [pscustomobject]@{
                session = [long] $group.Name
                activationToFirstOverlayMs = if ($null -ne $activation.uptimeMs -and
                        $null -ne $firstOverlay.uptimeMs) {
                    $firstOverlay.uptimeMs - $activation.uptimeMs
                } else { $null }
                qualityStartedAfterFirstOverlay = if ($null -eq $qualityBegin) { $null } else {
                    $null -ne $firstOverlay.uptimeMs -and
                        $qualityBegin.uptimeMs -gt $firstOverlay.uptimeMs
                }
            }
        })
    $duplicateSceneCommits = @($sceneCommits | Group-Object id |
            Where-Object Count -gt 1)
    $begunSceneIds = @($sceneBegins.id | Sort-Object -Unique)
    $committedSceneIds = @($sceneCommits.id | Sort-Object -Unique)
    $terminalWithoutCommitSceneIds = @(
        @($sceneInvalidations.id) + @($sceneLateDrops.id) | Sort-Object -Unique)
    $uncommittedSceneIds = @($begunSceneIds | Where-Object {
            $committedSceneIds -notcontains $_ -and
            $terminalWithoutCommitSceneIds -notcontains $_ })
    $onTimeSceneCommits = @($sceneCommits | Where-Object {
            -not $_.visibleDeadlineMiss })

    $summary = [ordered]@{
        file = $resolved
        parsing = [ordered]@{
            overlayRecords = $rawOverlayRecords
            parsedOverlayRecords = $publishes.Count
            unparsedOverlayRecords = $rawOverlayRecords - $publishes.Count
            knownRenderSourceRecords = $knownRenderSourceRecords
            qualityRecords = $rawQualityRecords
            parsedQualityRecords = $quality.Count + $streamingQuality.Count
            rawQualityRetainedRecords = $rawQualityRetainedPresents
            parsedQualityRetainedRecords = $qualityRetainedPresents.Count
            unparsedQualityRecords = $rawQualityRecords - $quality.Count - $streamingQuality.Count
            scrollMetadataRecords = $rawScrollMetadata
            parsedScrollMetadataRecords = $scrollMetadata.Count
            unparsedScrollMetadataRecords = $rawScrollMetadata - $scrollMetadata.Count
            complete = ($rawOverlayRecords -eq $publishes.Count) -and
                ($rawQualityRecords -eq $quality.Count + $streamingQuality.Count) -and
                ($rawScrollMetadata -eq $scrollMetadata.Count)
        }
        bytes = (Get-Item -LiteralPath $resolved).Length
        selection = $selection
        durationSeconds = $durationSeconds
        fast = [ordered]@{
            publishes = $fast.Count
            captureAgeMs = Get-Distribution @($fast.captureAge)
            inferenceMs = Get-Distribution @($fast.inference)
            preprocessMs = Get-Distribution @($fast.preprocess)
            runtimeMs = Get-Distribution @($fast.runtime)
            postprocessMs = Get-Distribution @($fast.postprocess)
            publishIntervalMs = Get-Distribution @($intervals)
            maxDropped = if ($fast.Count) { ($fast.dropped | Measure-Object -Maximum).Maximum } else { 0 }
            nonzeroDropPublishes = $dropNonzero
            geometryChangedPublishes = @($fast | Where-Object geometryChanged -gt 0).Count
            centerJumpsAtLeast100px = $geometryJumps
            duplicateSuppressions = ($fast | Measure-Object -Property duplicatesSuppressed -Sum).Sum
            duplicateSuppressionPublishes = @($fast | Where-Object duplicatesSuppressed -gt 0).Count
            renderHandOffs = ($fast | Measure-Object -Property renderHandOffs -Sum).Sum
            qualityOnlyObservations = ($fast | Measure-Object -Property qualityOnly -Sum).Sum
            identityRealtimeLinked = ($fast | Measure-Object -Property identityRealtimeLinked -Sum).Sum
            identityQualityLinked = ($fast | Measure-Object -Property identityQualityLinked -Sum).Sum
            identityFused = ($fast | Measure-Object -Property identityFused -Sum).Sum
            identityCarriedQuality = ($fast | Measure-Object -Property identityCarriedQuality -Sum).Sum
            identityUnlinkedQuality = ($fast | Measure-Object -Property identityUnlinkedQuality -Sum).Sum
            reusedQualityCacheSkips = if ($fast.Count) {
                ($fast.qualityCacheReusesSkipped | Measure-Object -Maximum).Maximum
            } else { 0 }
            qualityPreemptions = if ($fast.Count) {
                ($fast.qualityPreemptions | Measure-Object -Maximum).Maximum
            } else { 0 }
            qualityCancelledRuns = if ($fast.Count) {
                ($fast.qualityCancelledRuns | Measure-Object -Maximum).Maximum
            } else { 0 }
            qualityOnlyTracks = Get-Distribution @($fast.qualityOnlyTracks)
            renderTracks = Get-Distribution @($fast.renderTracks)
            activeDuplicateLikePublishes = $activeDuplicateLike.Count
            whileQualityActive = [ordered]@{
                publishes = $fastWithQuality.Count
                captureAgeMs = Get-Distribution @($fastWithQuality.captureAge)
                runtimeMs = Get-Distribution @($fastWithQuality.runtime)
            }
            whileQualityIdle = [ordered]@{
                publishes = $fastWithoutQuality.Count
                captureAgeMs = Get-Distribution @($fastWithoutQuality.captureAge)
                runtimeMs = Get-Distribution @($fastWithoutQuality.runtime)
            }
            whileQualityConcurrent = [ordered]@{
                publishes = $fastConcurrentQuality.Count
                captureAgeMs = Get-Distribution @($fastConcurrentQuality.captureAge)
                runtimeMs = Get-Distribution @($fastConcurrentQuality.runtime)
            }
        }
        activeScrollFast = [ordered]@{
            publishes = $activeFast.Count
            captureAgeMs = Get-Distribution @($activeFast.captureAge)
            inferenceMs = Get-Distribution @($activeFast.inference)
            runtimeMs = Get-Distribution @($activeFast.runtime)
            geometryChangedPublishes = @(
                $activeFast | Where-Object geometryChanged -gt 0).Count
            centerJumpsAtLeast100px = @(
                $activeFast | Where-Object maxCenterDelta -ge 100).Count
            duplicateLikePublishes = $activeDuplicateLike.Count
        }
        settledFast = [ordered]@{
            publishes = $settledFast.Count
            captureAgeMs = Get-Distribution @($settledFast.captureAge)
            inferenceMs = Get-Distribution @($settledFast.inference)
            runtimeMs = Get-Distribution @($settledFast.runtime)
        }
        scenes = [ordered]@{
            begun = $sceneBegins.Count
            commits = $sceneCommits.Count
            commitKinds = Get-GroupCounts @($sceneCommits) 'kind'
            commitCaptureAgeMs = Get-Distribution @($sceneCommits.captureAge)
            fusedCommits = @($sceneCommits |
                Where-Object kind -eq 'SETTLED_FUSED').Count
            deadlineFastCommits = @($sceneCommits |
                Where-Object kind -eq 'DEADLINE_FAST').Count
            timeouts = $sceneTimeouts.Count
            timeoutCaptureAgeMs = Get-Distribution @($sceneTimeouts.captureAge)
            lateDrops = $sceneLateDrops.Count
            lateDropReasons = Get-GroupCounts @($sceneLateDrops) 'reason'
            invalidations = $sceneInvalidations.Count
            invalidationReasons = Get-GroupCounts @($sceneInvalidations) 'reason'
            duplicateCommitSceneIds = $duplicateSceneCommits.Count
            uncommittedSceneIds = $uncommittedSceneIds.Count
            onTimeVisibleCommits = $onTimeSceneCommits.Count
            visibleDeadlineMisses = @($sceneCommits |
                Where-Object visibleDeadlineMiss).Count
            onTimeVisibleCommitRate = if ($sceneCommits.Count) {
                [math]::Round($onTimeSceneCommits.Count / $sceneCommits.Count, 4)
            } else { $null }
        }
        worldCache = [ordered]@{
            queries = $worldCacheQueries.Count
            sources = Get-GroupCounts @($worldCacheQueries) 'source'
            entries = Get-Distribution @($worldCacheQueries.entries)
            inserted = ($worldCacheQueries | Measure-Object -Property inserted -Sum).Sum
            updated = ($worldCacheQueries | Measure-Object -Property updated -Sum).Sum
            evicted = ($worldCacheQueries | Measure-Object -Property evicted -Sum).Sum
            candidates = Get-Distribution @($worldCacheQueries.candidates)
            viewportResets = @($worldCacheQueries | Where-Object viewportReset).Count
            invalidations = $worldCacheInvalidations.Count
            invalidationReasons = Get-GroupCounts @($worldCacheInvalidations) 'reason'
            invalidatedEntries = ($worldCacheInvalidations |
                Measure-Object -Property removed -Sum).Sum
            reentryPublishes = $worldCacheReentries.Count
            reentryCandidates = Get-Distribution @($worldCacheReentries.candidates)
        }
        quality = [ordered]@{
            caches = $quality.Count
            captureAgeMs = Get-Distribution @($quality.captureAge)
            inferenceMs = Get-Distribution @($quality.inference)
            runtimeMs = Get-Distribution @($quality.runtime)
            skipFastPending = @($lines | Where-Object { $_ -match 'QUALITY_SKIP reason=fast-pending|fast-arrived-' }).Count
            sourceFrameFallbacks = @($lines | Where-Object { $_ -match 'SOURCE_FRAME_PUBLISH' }).Count
            identityLinked = ($quality | Measure-Object -Property identityLinked -Sum).Sum
            identityUnlinked = ($quality | Measure-Object -Property identityUnlinked -Sum).Sum
            pendingVisualCandidates = ($quality | Measure-Object -Property pendingVisual -Sum).Sum
            deferredUnlinkedCoverage = ($quality | Measure-Object -Property deferredUnlinked -Sum).Sum
            supplementedTracks = ($qualitySupplements | Measure-Object -Property added -Sum).Sum
            supplementPublishes = $qualitySupplements.Count
            supplementAfterMotionMs = Get-Distribution @($qualitySupplements.afterMotion)
            supplementScrollIds = Get-GroupCounts @($qualitySupplements) 'scrollId'
            retirePublishes = $qualityRetires.Count
            retiredTracks = ($qualityRetires | Measure-Object -Property retired -Sum).Sum
            retireAfterMotionMs = Get-Distribution @($qualityRetires.afterMotion)
            batchStates = Get-GroupCounts @($quality) 'batchState'
            confirmationRequests = @($quality | Where-Object confirmationRequested).Count
            confirmationRuns = @($lines | Where-Object {
                    $_ -match 'QUALITY_CONFIRMATION_RUN' }).Count
            cacheInvalidationsOnMotion = @($lines | Where-Object {
                    $_ -match 'QUALITY_CACHE_INVALIDATED reason=motion' }).Count
            cacheGenerationRejections = @($lines | Where-Object {
                    $_ -match 'QUALITY_CACHE_REJECTED reason=motion-generation' }).Count
            streamingCaches = $streamingQuality.Count
            streamingCaptureAgeMs = Get-Distribution @($streamingQuality.captureAge)
            streamingBitmapPrepareMs = Get-Distribution @($streamingQuality.bitmapPrepare)
            streamingInferenceMs = Get-Distribution @($streamingQuality.inference)
            streamingRuntimeMs = Get-Distribution @($streamingQuality.runtime)
            streamingPendingCandidates = ($streamingQuality |
                Measure-Object -Property pendingVisual -Sum).Sum
            streamingCompleteScenes = @($streamingQuality |
                Where-Object completeScene).Count
            atomicReadyStatuses = Get-GroupCounts @($streamingQuality) 'transactionStatus'
            streamingPreservedCaches = @($streamingQuality |
                Where-Object cachePreserved).Count
            streamingIdentityLinked = ($streamingQuality |
                Measure-Object -Property identityLinked -Sum).Sum
            streamingIdentityUnlinked = ($streamingQuality |
                Measure-Object -Property identityUnlinked -Sum).Sum
            streamingRetiredQualityTracks = ($streamingQuality |
                Measure-Object -Property retiredQualityTracks -Sum).Sum
            streamingReprojectAbsPx = Get-Distribution @($streamingQuality.reprojectAbs)
            streamingGenerationAdvance = Get-Distribution @($streamingQuality |
                ForEach-Object { $_.cacheGeneration - $_.sourceGeneration })
            streamingMaxDropped = if ($streamingQuality.Count) {
                ($streamingQuality.dropped | Measure-Object -Maximum).Maximum
            } else { 0 }
            backfillOffers = $qualityBackfillOffers.Count
            backfillOfferStatuses = Get-GroupCounts @($qualityBackfillOffers) 'status'
            backfillCoalesced = @($qualityBackfillOffers |
                Where-Object status -eq 'ACCEPTED_REPLACED_OLDER').Count
            backfillDrops = $qualityBackfillDrops.Count
            backfillDropReasons = Get-GroupCounts @($qualityBackfillDrops) 'reason'
            streamingStaleDrops = $streamingQualityDrops.Count
            streamingDropReasons = Get-GroupCounts @($streamingQualityDrops) 'reason'
            preemptionRequests = $qualityPreempts.Count
            preemptionReasons = Get-GroupCounts @($qualityPreempts) 'reason'
            cancelledRuns = $qualityCancellations.Count
            cancellationReasons = Get-GroupCounts @($qualityCancellations) 'reason'
            cancellationMs = Get-Distribution @($qualityCancellations.cancellation)
            gateSkips = $qualityGateSkips.Count
            gateSkipReasons = Get-GroupCounts @($qualityGateSkips) 'reason'
            stableCaptureWindows = Get-GroupCounts @($qualityWindows) 'action'
            stableCaptureWindowMs = Get-Distribution @($qualityWindows.active)
            lateStages = $qualityLateStages.Count
            lateStageCaptureAgeMs = Get-Distribution @($qualityLateStages.captureAge)
            latePresents = $qualityLatePresents.Count
            retainedPresents = $qualityRetainedPresents.Count
            retainedCaptureAgeMs = Get-Distribution @($qualityRetainedPresents.captureAge)
            immediatePresents = $qualityImmediatePresents.Count
            immediateReadyToPresentMs = Get-Distribution @($qualityImmediatePresents.readyToPresent)
            immediateCaptureAgeMs = Get-Distribution @($qualityImmediatePresents.captureAge)
            lateReadyToPresentMs = Get-Distribution @($qualityLatePresents.readyToPresent)
            lateDrops = $qualityLateDrops.Count
            lateDropReasons = Get-GroupCounts @($qualityLateDrops) 'reason'
            concurrencyActions = Get-GroupCounts @($qualityConcurrency) 'action'
            concurrencyFastRuntimeMs = Get-Distribution @($qualityConcurrency.fastRuntime)
            concurrencyIdleRuntimeEmaMs = Get-Distribution @(
                $qualityConcurrency.idleRuntimeEma)
        }
        inferenceGate = [ordered]@{
            fastWaitMs = Get-Distribution @($fastGateWaits.wait)
            nativeRuns = $nativeRuns.Count
            incompleteNativeRuns = $nativeBegins.Count
            fastNativeRuntimeMs = Get-Distribution @($fastNativeRuns.duration)
            qualityNativeRuntimeMs = Get-Distribution @($qualityNativeRuns.duration)
            qualityNativeStatuses = Get-GroupCounts @($qualityNativeRuns) 'status'
            fastQualityNativeOverlapPairs = $nativeOverlapPairs.Count
            qualityPrepareMs = Get-Distribution @($qualityPrepares.duration)
            incompleteQualityPrepares = $qualityPrepareBegins.Count
            qualityPrepareFastNativeOverlapPairs = $prepareFastOverlapPairs.Count
        }
        startup = [ordered]@{
            events = $startupEvents.Count
            sessions = @($startupEvents.session | Sort-Object -Unique).Count
            phases = Get-GroupCounts @($startupEvents) 'phase'
            phaseEvents = @($startupEvents)
            activationToFirstOverlayMs = Get-Distribution @(
                $startupSessionSummaries.activationToFirstOverlayMs)
            qualityBeforeFirstOverlayViolations = @($startupSessionSummaries |
                Where-Object qualityStartedAfterFirstOverlay -eq $false).Count
        }
        capturePhase = [ordered]@{
            samples = $capturePhases.Count
            requestToCaptureMs = Get-Distribution @($capturePhases.requestToCapture)
            callbackDelayMs = Get-Distribution @($capturePhases.callbackDelay)
            requestToCaptureScrollDeltaAbsPx = Get-Distribution @($capturePhases.scrollDeltaAbs)
            generationChangedSamples = @($capturePhases | Where-Object generationDelta -ne 0).Count
            timelineResolvedSamples = @($capturePhases | Where-Object timelineResolved).Count
        }
        scrollMetadata = [ordered]@{
            records = $scrollMetadata.ToArray()
            classKinds = Get-GroupCounts @($scrollMetadata) 'classKind'
            missingSources = @($scrollMetadata | Where-Object { -not $_.sourcePresent }).Count
            failedTraversals = @($scrollMetadata | Where-Object traversalFailed).Count
        }
        scroll = [ordered]@{
            sessions = @($scrolls.id | Sort-Object -Unique).Count
            events = $scrolls.Count
            sources = Get-GroupCounts @($scrolls) 'source'
            eventAgeMs = Get-Distribution @($scrolls.eventAge)
            rawAbs = $rawAbs
            appliedAbs = $appliedAbs
            appliedToRawRatio = if ($rawAbs) { [math]::Round($appliedAbs / $rawAbs, 4) } else { 0 }
            rawNet = @($rawNetX, $rawNetY)
            appliedNet = @($appliedNetX, $appliedNetY)
            amplifiedEvents = $amplified
            amplifiedBySource = $amplifiedBySource
            evidence = Get-GroupCounts @($scrolls) 'evidence'
            adjustedPixels = ($scrolls | Measure-Object -Property adjusted -Sum).Sum
            explicitlyAmplifiedEvents = @($scrolls | Where-Object explicitlyAmplified).Count
        }
        gestureToScroll = [ordered]@{
            swipeMarkers = $swipeStarts.Count
            matched = @($gestureToScroll).Count
            missed = $swipeStarts.Count - @($gestureToScroll).Count
            firstAppliedEventMs = Get-Distribution @($gestureToScroll.latency)
            sources = Get-GroupCounts @($gestureToScroll) 'source'
        }
        displayMotion = [ordered]@{
            inputs = $motionInputs.Count
            inputSources = Get-GroupCounts @($motionInputs) 'source'
            predictionAmplitudeAbsPx = Get-Distribution @($motionInputs.predictionAbs)
            predictionPeakMs = Get-Distribution @($motionInputs.predictionPeak)
            draws = $motionDraws.Count
            inputToDrawMs = Get-Distribution @($motionDraws.inputToDraw)
            explicitUptimeDraws = @($motionDraws | Where-Object explicitDrawClock).Count
            legacyUnknownClockDraws = @($motionDraws | Where-Object { !$_.explicitDrawClock }).Count
            inputToPresentationMs = Get-Distribution @($motionDraws.inputToPresentation | Where-Object { $null -ne $_ })
            renderTickMs = Get-Distribution @($motionDraws.renderTick)
            viewportLeadAbsPx = Get-Distribution @($motionDraws.leadAbs)
            settles = $motionSettles.Count
            inputToSettledMs = Get-Distribution @($motionSettles.inputToSettled)
            consolidationPublishes = $renderConsolidations.Count
            consolidatedRegions = ($renderConsolidations |
                Measure-Object -Property merged -Sum).Sum
            consolidationInput = Get-Distribution @($renderConsolidations.input)
            consolidationOutput = Get-Distribution @($renderConsolidations.output)
        }
        anchorPolling = [ordered]@{
            selections = $anchorSets.Count
            selectedAnchors = Get-Distribution @($anchorSets.count)
            selectionCandidates = Get-Distribution @($anchorSets.candidates)
            selectionMs = Get-Distribution @($anchorSets.selection)
            phaseLogs = $anchorPhases.Count
            phaseDisplacementPx = Get-Distribution @($anchorPhases.displacement)
            latencyMs = Get-Distribution @($anchorPhases.latency)
            intervalMs = Get-Distribution @($anchorPhases.interval)
            contributors = Get-Distribution @($anchorPhases.contributors)
            rejects = $anchorRejects.Count
            rejectReasons = Get-GroupCounts @($anchorRejects) 'reason'
            viewportPollStarts = $viewportPollStarts.Count
            viewportPollAcceptedStarts = @($viewportPollStarts |
                Where-Object started).Count
            viewportPollAnchors = Get-Distribution @($viewportPollStarts.anchors)
        }
        text = [ordered]@{
            scans = $textScans.Count
            scanStatuses = Get-GroupCounts @($textScans) 'status'
            acceptedDurationMs = Get-Distribution @($textScans | Where-Object status -eq 'accepted' | Select-Object -ExpandProperty duration)
            bestEffortAccepted = @($textScans | Where-Object { $_.status -eq 'accepted' -and $_.bestEffort }).Count
            confirms = $textConfirms.Count
            confirmDurationMs = Get-Distribution @($textConfirms.duration)
            publishes = $textPublishes.Count
            publishSources = Get-GroupCounts @($textPublishes) 'source'
            zeroRegionPublishes = @($textPublishes | Where-Object regions -eq 0).Count
        }
    }
    $summary | ConvertTo-Json -Depth 8
}
