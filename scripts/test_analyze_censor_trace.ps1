$ErrorActionPreference = 'Stop'

function Assert-Equal($Expected, $Actual, [string] $Label) {
    if ($Expected -ne $Actual) {
        throw "$Label expected '$Expected', got '$Actual'."
    }
}

function Assert-Throws([scriptblock] $Work, [string] $ExpectedMessage) {
    $caught = $null
    try {
        & $Work | Out-Null
    } catch {
        $caught = $_
    }
    if ($null -eq $caught) {
        throw "Expected failure containing '$ExpectedMessage'."
    }
    if ($caught.Exception.Message -notlike "*$ExpectedMessage*") {
        throw "Expected failure containing '$ExpectedMessage', got '$($caught.Exception.Message)'."
    }
}

function Write-Fixture([string] $Path, [string[]] $Lines) {
    [IO.File]::WriteAllLines($Path, $Lines, (New-Object Text.UTF8Encoding($false)))
}

$analyzer = Join-Path $PSScriptRoot 'analyze_censor_trace.ps1'
$temporaryParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryRoot = Join-Path $temporaryParent ("subhub-trace-analyzer-" + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null

try {
    $normal = Join-Path $temporaryRoot 'normal.log'
    Write-Fixture $normal @(
        ' 1788590000.000 1 1 I ScreenshotA11y: TEXT_PUBLISH source=pre regions=1',
        ' 1788590001.000 1 1 I CensorReplay: RUN_START session=fixture',
        ' 1788590002.000 1 1 I ScreenshotA11y: TEXT_PUBLISH source=in-run regions=2',
        ' 1788590003.000 1 1 I CensorReplay: RUN_END session=fixture',
        ' 1788590004.000 1 1 I ScreenshotA11y: TEXT_PUBLISH source=post regions=3'
    )

    $unscoped = & $analyzer $normal | ConvertFrom-Json
    Assert-Equal 'unscoped' $unscoped.selection.mode 'whole-file mode'
    Assert-Equal 5 $unscoped.selection.sourceLineCount 'whole-file source count'
    Assert-Equal 5 $unscoped.selection.selectedLineCount 'whole-file selected count'
    Assert-Equal 3 $unscoped.text.publishes 'whole-file parsed events'

    $bounded = & $analyzer $normal `
        -StartMarker 'RUN_START session=fixture' `
        -EndMarker 'RUN_END session=fixture' | ConvertFrom-Json
    Assert-Equal 'bounded' $bounded.selection.mode 'bounded mode'
    Assert-Equal 2 $bounded.selection.startLine 'bounded start line'
    Assert-Equal 4 $bounded.selection.endLine 'bounded end line'
    Assert-Equal 3 $bounded.selection.selectedLineCount 'bounded selected count'
    Assert-Equal 1 $bounded.selection.excludedBefore 'bounded prefix exclusion'
    Assert-Equal 1 $bounded.selection.excludedAfter 'bounded suffix exclusion'
    Assert-Equal 1 $bounded.text.publishes 'bounded parsed events'
    Assert-Equal 2 $bounded.durationSeconds 'bounded duration'

    $immediate = Join-Path $temporaryRoot 'immediate.log'
    Write-Fixture $immediate @(
        ' 1788590001.000 1 1 I ScreenshotA11y: QUALITY_IMMEDIATE_PRESENT sourceFastSequence=10 regions=2 readyToPresentMs=12 captureAgeMs=240'
    )
    $immediateResult = & $analyzer $immediate | ConvertFrom-Json
    Assert-Equal 1 $immediateResult.quality.immediatePresents 'immediate event count'
    Assert-Equal 12 $immediateResult.quality.immediateReadyToPresentMs.p50 'immediate ready wait'
    Assert-Equal 240 $immediateResult.quality.immediateCaptureAgeMs.p50 'immediate capture age'
    Write-Fixture $immediate @(
        ' 1788590001.000 1 1 I ScreenshotA11y: QUALITY_IMMEDIATE_PRESENT sourceFastSequence=10 regions=2'
    )
    Assert-Throws { & $analyzer $immediate } 'Incomplete QUALITY_IMMEDIATE_PRESENT record*'

    $retained = Join-Path $temporaryRoot 'retained.log'
    Write-Fixture $retained @(
        ' 1788590001.000 1 1 I ScreenshotA11y: QUALITY_RETAINED_PRESENT sourceFastSequence=10 consumerFastSequence=12 regions=2 captureAgeMs=900'
    )
    $retainedResult = & $analyzer $retained | ConvertFrom-Json
    Assert-Equal 1 $retainedResult.parsing.rawQualityRetainedRecords 'raw retained count'
    Assert-Equal 1 $retainedResult.parsing.parsedQualityRetainedRecords 'parsed retained count'
    Assert-Equal 1 $retainedResult.quality.retainedPresents 'retained event count'
    Assert-Equal 0 $retainedResult.quality.latePresents 'reuse is not first delivery'
    Assert-Equal 900 $retainedResult.quality.retainedCaptureAgeMs.p50 'retained age'
    foreach ($broken in @(
        'QUALITY_RETAINED_PRESENT sourceFastSequence=10 regions=2',
        'QUALITY_RETAINED_PRESENT sourceFastSequence=10 consumerFastSequence=12 regions=2 captureAgeMs=900 unknown=1'
    )) {
        Write-Fixture $retained @(' 1788590001.000 1 1 I ScreenshotA11y: ' + $broken)
        Assert-Throws { & $analyzer $retained } 'Incomplete QUALITY_RETAINED_PRESENT record*'
    }

    $candidate = Join-Path $temporaryRoot 'candidate.log'
    $qualityRecord = ' 1788590001.000 1 1 I ScreenshotA11y: QUALITY_READY id=fixture scrollId=1 captureAgeMs=200 bitmapPrepareMs=10 inferenceMs=130 preprocessMs=10 runtimeMs=115 postprocessMs=5 afterMotionMs=100 rawVisual=2 acceptedVisual=2 tile=1/2 tileBounds=0,0,100,180 renderAuthority=none backfillStatus=ACCEPTED backfillMatched=0 backfillInserted=2 backfillPromoted=0 backfillRefined=0 oldFrame=false sourceGeneration=1 sourceFastSequence=1 currentFastSequence=1 dropped=0 staleDropped=0 preemptions=0 cancelledRuns=0'
    Write-Fixture $candidate @(
        $qualityRecord,
        ' 1788590001.010 1 1 I ScreenshotA11y: QUALITY_LATE_STAGE sourceFastSequence=1 sourceGeneration=1 regions=2 replaced=false captureAgeMs=200',
        ' 1788590001.020 1 1 I ScreenshotA11y: QUALITY_LATE_PRESENT sourceFastSequence=1 consumerFastSequence=2 sourceGeneration=1 regions=2 readyToPresentMs=10',
        ' 1788590001.030 1 1 I ScreenshotA11y: OVERLAY_PUBLISH pass=fast scrollId=1 captureAgeMs=100 inferenceMs=30 preprocessMs=2 runtimeMs=25 postprocessMs=3 afterMotionMs=100 tracks=2 rawVisual=2 cachedQuality=0 qualityOnly=0 geometryMatched=2 geometryChanged=0 maxCenterDeltaPx=0 maxSizeDeltaPx=0 dropped=0 duplicatesSuppressed=0 renderHandOffs=0 qualityOnlyTracks=0 renderTracks=2 visibleRenderTracks=2 qualityActive=true qualityConcurrent=true qualityPreemptions=0 qualityCancelledRuns=0 renderSourceKnown=false renderSourceTime=-1 renderSourceBias=0,0'
    )
    $candidateResult = & $analyzer $candidate | ConvertFrom-Json
    Assert-Equal $true $candidateResult.parsing.complete 'candidate complete parse'
    Assert-Equal 1 $candidateResult.parsing.parsedOverlayRecords 'candidate fast count'
    Assert-Equal 1 $candidateResult.parsing.parsedQualityRecords 'candidate quality count'
    Assert-Equal 1 $candidateResult.quality.latePresents 'candidate normal handoff count'
    Write-Fixture $candidate @($qualityRecord + ' unexpected=1')
    Assert-Throws { & $analyzer $candidate } 'Unsupported QUALITY_READY field*'
    Write-Fixture $candidate @(' 1788590001.000 1 1 I ScreenshotA11y: QUALITY_READY broken')
    $incomplete = & $analyzer $candidate | ConvertFrom-Json
    Assert-Equal $false $incomplete.parsing.complete 'broken quality is not zero work'
    Assert-Equal 1 $incomplete.parsing.unparsedQualityRecords 'broken quality count'

    $jitter = Join-Path $temporaryRoot 'jitter.log'
    Write-Fixture $jitter @(
        ' 1788590800.000 1 1 I ScreenshotA11y: TEXT_PUBLISH source=buffered regions=1',
        ' 1788590803.291 1 1 I CensorReplay: ASTRA_JITTER_START',
        ' 1788590804.000 1 1 I ScreenshotA11y: TEXT_PUBLISH source=jitter regions=1',
        ' 1788590807.914 1 1 I CensorReplay: ASTRA_JITTER_END'
    )
    $jitterResult = & $analyzer $jitter `
        -StartMarker 'ASTRA_JITTER_START' -EndMarker 'ASTRA_JITTER_END' |
        ConvertFrom-Json
    Assert-Equal 1 $jitterResult.text.publishes 'jitter prefix exclusion'
    Assert-Equal 1 $jitterResult.selection.excludedBefore 'jitter excluded prefix'

    $missing = Join-Path $temporaryRoot 'missing.log'
    Write-Fixture $missing @(' 1788590000.000 1 1 I CensorReplay: RUN_START session=fixture')
    Assert-Throws {
        & $analyzer $missing -StartMarker 'RUN_START' -EndMarker 'RUN_END'
    } 'EndMarker matched 0 lines'

    $missingStart = Join-Path $temporaryRoot 'missing-start.log'
    Write-Fixture $missingStart @(' 1788590000.000 1 1 I CensorReplay: RUN_END session=fixture')
    Assert-Throws {
        & $analyzer $missingStart -StartMarker 'RUN_START' -EndMarker 'RUN_END'
    } 'StartMarker matched 0 lines'

    $ambiguous = Join-Path $temporaryRoot 'ambiguous.log'
    Write-Fixture $ambiguous @(
        ' 1788590000.000 1 1 I CensorReplay: RUN_START session=fixture',
        ' 1788590001.000 1 1 I CensorReplay: RUN_START session=fixture',
        ' 1788590002.000 1 1 I CensorReplay: RUN_END session=fixture'
    )
    Assert-Throws {
        & $analyzer $ambiguous -StartMarker 'RUN_START' -EndMarker 'RUN_END'
    } 'StartMarker matched 2 lines'

    $ambiguousEnd = Join-Path $temporaryRoot 'ambiguous-end.log'
    Write-Fixture $ambiguousEnd @(
        ' 1788590000.000 1 1 I CensorReplay: RUN_START session=fixture',
        ' 1788590001.000 1 1 I CensorReplay: RUN_END session=fixture',
        ' 1788590002.000 1 1 I CensorReplay: RUN_END session=fixture'
    )
    Assert-Throws {
        & $analyzer $ambiguousEnd -StartMarker 'RUN_START' -EndMarker 'RUN_END'
    } 'EndMarker matched 2 lines'

    $reversed = Join-Path $temporaryRoot 'reversed.log'
    Write-Fixture $reversed @(
        ' 1788590000.000 1 1 I CensorReplay: RUN_END session=fixture',
        ' 1788590001.000 1 1 I CensorReplay: RUN_START session=fixture'
    )
    Assert-Throws {
        & $analyzer $reversed -StartMarker 'RUN_START' -EndMarker 'RUN_END'
    } 'StartMarker must precede EndMarker'

    Assert-Throws {
        & $analyzer $normal -StartMarker 'RUN_START'
    } 'must be supplied together'

    $admissionFixture = Join-Path $temporaryRoot 'capture-admission.log'
    Write-Fixture $admissionFixture @(
        'ScreenshotA11y: CAPTURE_ADMISSION v=1 action=dispatch requestId=1 uptimeMs=1000 eligibleMs=1334 inFlight=true reason=ready',
        'ScreenshotA11y: CAPTURE_ADMISSION v=1 action=complete requestId=1 uptimeMs=1100 eligibleMs=1334 inFlight=false reason=released',
        'ScreenshotA11y: CAPTURE_ADMISSION v=1 action=defer requestId=0 uptimeMs=1334 eligibleMs=1334 inFlight=false reason=quality-reservation',
        'ScreenshotA11y: CAPTURE_ADMISSION v=1 action=dispatch requestId=2 uptimeMs=1400 eligibleMs=1734 inFlight=true reason=ready'
    )
    $admissionResult = & $analyzer $admissionFixture | ConvertFrom-Json
    Assert-Equal 4 $admissionResult.parsing.captureAdmissionRecords 'raw admission records'
    Assert-Equal 4 $admissionResult.parsing.parsedCaptureAdmissionRecords 'parsed admission records'
    Assert-Equal $true $admissionResult.parsing.complete 'admission parsing completeness'
    Assert-Equal 2 $admissionResult.captureAdmission.actions.dispatch 'dispatch count'
    Assert-Equal 1 $admissionResult.captureAdmission.reasons.'quality-reservation' 'reservation count'
    Assert-Equal 400 $admissionResult.captureAdmission.dispatchGapMs.p50 'actual dispatch gap'
    Assert-Equal 0 $admissionResult.captureAdmission.dispatchGapsBelowPlatformMinimum 'spacing violations'
    foreach ($badAdmission in @(
            'CAPTURE_ADMISSION v=2 future=1',
            'CAPTURE_ADMISSION v=1 action=dispatch requestId=1',
            'CAPTURE_ADMISSION v=1 action=dispatch requestId=1 uptimeMs=1000 eligibleMs=1334 inFlight=true reason=ready extra=1',
            'CAPTURE_ADMISSION v=1 action=dispatch requestId=0 uptimeMs=1000 eligibleMs=1334 inFlight=true reason=ready')) {
        Write-Fixture $admissionFixture @($badAdmission)
        Assert-Throws { & $analyzer $admissionFixture } '*CAPTURE_ADMISSION*incomplete*'
    }
    Write-Fixture $admissionFixture @(
        'CAPTURE_ADMISSION v=1 action=dispatch requestId=5 uptimeMs=1000 eligibleMs=1334 inFlight=true reason=ready',
        'CAPTURE_ADMISSION v=1 action=dispatch requestId=6 uptimeMs=1300 eligibleMs=1634 inFlight=true reason=ready',
        'CAPTURE_ADMISSION v=1 action=dispatch requestId=1 uptimeMs=500 eligibleMs=834 inFlight=true reason=ready'
    )
    $violationResult = & $analyzer $admissionFixture | ConvertFrom-Json
    Assert-Equal 1 $violationResult.captureAdmission.dispatchGapsBelowPlatformMinimum 'short gap remains visible'
    Assert-Equal 1 $violationResult.captureAdmission.dispatchClockOrIdentityResets 'reset not mistaken for interval'

    $lookupFixture = Join-Path $temporaryRoot 'scroll-lookup.log'
    Write-Fixture $lookupFixture @(
        'SCROLL_LOOKUP v=1 id=1 status=queued sourceUptimeMs=950 receivedUptimeMs=1000 startedUptimeMs=0 resolvedUptimeMs=0 appliedUptimeMs=0 callbackUs=250 outstanding=1',
        'SCROLL_LOOKUP v=1 id=1 status=applied sourceUptimeMs=950 receivedUptimeMs=1000 startedUptimeMs=1020 resolvedUptimeMs=1120 appliedUptimeMs=1130 callbackUs=0 outstanding=1',
        'SCROLL_LOOKUP v=1 id=1 status=released sourceUptimeMs=950 receivedUptimeMs=1000 startedUptimeMs=1020 resolvedUptimeMs=1120 appliedUptimeMs=1130 callbackUs=0 outstanding=1',
        'SCROLL_LOOKUP_GAP reason=queue-age')
    $lookupResult = & $analyzer $lookupFixture | ConvertFrom-Json
    Assert-Equal 3 $lookupResult.parsing.scrollLookupRecords 'raw lookup count'
    Assert-Equal 3 $lookupResult.parsing.parsedScrollLookupRecords 'parsed lookup count'
    Assert-Equal 250 $lookupResult.scrollLookup.callbackUs.p50 'main callback duration'
    Assert-Equal 20 $lookupResult.scrollLookup.queueWaitMs.p50 'queue wait'
    Assert-Equal 100 $lookupResult.scrollLookup.lookupWallMs.p50 'owner resolution'
    Assert-Equal 10 $lookupResult.scrollLookup.mainDeliveryMs.p50 'main delivery'
    Assert-Equal 180 $lookupResult.scrollLookup.sourceToApplyMs.p50 'source-to-apply delay'
    foreach ($bad in @(
            'SCROLL_LOOKUP v=2 future=1',
            'SCROLL_LOOKUP v=1 id=1 status=applied sourceUptimeMs=950 receivedUptimeMs=1000 startedUptimeMs=1020 resolvedUptimeMs=1010 appliedUptimeMs=1130 callbackUs=0 outstanding=1',
            'SCROLL_LOOKUP v=1 id=1 status=queued sourceUptimeMs=950 receivedUptimeMs=1000 startedUptimeMs=0 resolvedUptimeMs=0 appliedUptimeMs=0 callbackUs=0 outstanding=1 extra=1')) {
        Write-Fixture $lookupFixture @($bad)
        Assert-Throws { & $analyzer $lookupFixture } '*SCROLL_LOOKUP*incomplete*'
    }
    Write-Output 'analyze_censor_trace regression checks passed.'
} finally {
    $resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
    $isTaskTemporaryDirectory = $resolvedTemporaryRoot.StartsWith(
            $temporaryParent, [StringComparison]::OrdinalIgnoreCase)
    if ($isTaskTemporaryDirectory -and [IO.Directory]::Exists($resolvedTemporaryRoot)) {
        [IO.Directory]::Delete($resolvedTemporaryRoot, $true)
    }
}
