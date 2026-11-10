"""Run a fixed, explicitly selected native suite on one fresh, owned Linux emulator.

This producer is separate from canonical unit/lint CI and never signs or publishes a
release. Each selected method runs once; a failure retains its log and the remaining
inventory. A later explicit workflow dispatch may select only a named subset.
"""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import time
import traceback


ROOT = Path(__file__).resolve().parents[1]
API = 35
BUILD_TOOLS_VERSION = "35.0.0"
SYSTEM_IMAGE = "system-images;android-35;google_apis;x86_64"
RUNNER = "com.subhub.app.test/com.subhub.app.SubHubTestRunner"
GIB = 1024 ** 3
MIB = 1024 ** 2
# Android's first API>=24 userdata setup needs 6 GiB plus 20 percent free space.
# Keep a separate runner reserve; never lower this first-boot requirement.
FIRST_BOOT_FREE = math.ceil(6 * GIB * 1.2) + 3 * GIB
RUNTIME_FREE = 3 * GIB
MEMORY_AVAILABLE = (2560 + 2048) * MIB
MAX_LOG = 16 * MIB
MAX_ARCHIVE = 64 * MIB
MAX_MEMBER = 16 * MIB
# Ubuntu 24.04's client-library package supplies the missing emulator SONAME.
# Never infer arbitrary apt package names from loader output.
EMULATOR_RUNTIME_PACKAGES = {"libpulse.so.0": "libpulse0"}
NATIVE = {
    "com.subhub.app.remote.ExtraTimeRequestAndroidTest": [
        "retainedCapabilityCannotBypassConsentWorkspaceOrRevocation",
        "submissionChargesOnceAndApprovalAppliesOnlyItsTemporaryAppCredit",
        "declineDoesNotChargeAndChangedQuoteOrConfigurationCannotBeApplied",
        "foreignDecisionAndReplayCannotManufactureTime",
    ],
    "com.subhub.app.remote.ExtraTimeTributeAndroidTest": [
        "failedAcknowledgementRecoversOneDebitAndOneProjectionWithoutInventingPayment",
        "configuredPriceCapsAndWalletOptoutApplyAtSubmissionNotDecision",
    ],
    "com.subhub.app.remote.ExtraTimeWireAndroidTest": [
        "negotiatedPhaseRunsBeforeDrainForBothInitiatorsAndPhasePlans",
        "legacyPeerSkipsPhaseInBothDirectionsAndClearsOldCapability",
        "failedHandshakePreservesOnlyPreviouslyNegotiatedCapability",
        "lostReceiptReconnectDoesNotRepeatCommittedApplication",
        "failedDurabilityIsNotAcknowledgedOrMarkedApplied",
        "revokedPairCannotApplyInFlightDecision",
        "subDisabledAvailabilityExchangesNoPrivateRequestState",
    ],
    "com.subhub.app.appmode.ExtraTimeUiAndroidTest": [
        "requestApproveAndDeclineRetainAllowanceAndInputDrafts",
        "remoteRequestIconArrivesWithoutPollingAndRevocationClearsIt",
        "unavailableRequestExplainsItsStateAndRecoversBeforeOneTapSubmission",
        "unsupportedPeerExplainsCapabilityAndRecoversAfterNegotiation",
        "largeFontNarrowReviewKeepsControlsAndReceiptsReadable",
    ],
    "com.subhub.app.penance.TributeSettingsRestoreAndroidTest": [
        "staleEnabledWidgetCannotReenableASavedOffRule",
        "staleDisabledWidgetCannotDisableASavedOnRule",
        "rulesAndSpendingLimitsRemainDistinctAfterNativeNavigationAndRecreation",
    ],
}
# Reviewed single-guest integration contracts. These do not include the two-device
# RemoteFlow/RemoteActivityFlow suites or every method of their shared test classes.
SUBHUB_PASS_NATIVE = {
    "com.subhub.app.commitment.DurationChoicesAndroidTest": [
        "sixOriginalChoicesBindSilentlyAndDispatchOnlyChangedEditableSelections",
        "nativeChoiceLabelsFitNarrowAndLargeFontLayouts"
    ],
    "com.subhub.app.commitment.ServiceDurationRetentionAndroidTest": [
        "refreshedPickerAdoptsExternalSettingsAndKeepsIncompleteInput",
        "hiddenToggleCannotRestoreStaleDurationOrBoundsEvenBeforeRefresh",
        "durationChoiceCannotRestoreStaleVisibilityOrBounds"
    ],
    "com.subhub.app.people.PersonRefreshAndroidTest": [
        "telemetryAndDeferredRefreshKeepTheProfileDraftAndDeliveryTimeScroll",
        "changedIdentityRecreationAndBackRetainPositionButRoleRevocationClosesPage",
        "retainedLocalDurationRefreshesOnReturnWithoutLosingAnIncompleteDraft"
    ],
    "com.subhub.app.remote.RemoteAppIconPresentationAndroidTest": [
        "sharedNativeLimitsAndAppSelectorKeepRemoteIconScope"
    ],
    "com.subhub.app.remote.RemoteConfigurationAndroidTest": [
        "autoEditsCoalesceAcrossRestartAndWaitForAppliedReceipt",
        "rejectedAutoEditStopsUntilExplicitDiscardAndNeverErasesPendingCommand",
        "identicalPendingSnapshotDoesNotAllocateAnotherRevisionAfterAck",
        "explicitConflictReloadKeepsNextRevisionAboveTheReceiver",
        "staleWorkspaceRevisionCannotSaveOrDiscardConfigurationEdits"
    ],
    "com.subhub.app.remote.RemoteDurationAndroidTest": [
        "staleInlineEditCannotQueueAfterDeviceCapabilityOrRelationshipRevocation"
    ],
    "com.subhub.app.remote.RemoteEditorAndroidTest": [
        "nativeSpinnerChoiceAutomaticallyQueuesRemoteDuration",
        "editorOpeningAndRecreationNeverQueueUnchangedConfiguration",
        "rapidInputCoalescesAndClosingFlushesTheLastValidEdit",
        "newerEditsRemainVisibleAndDurableWhileFirstCommandIsPending",
        "automaticSaveRetainsTheFocusedEditorAndCursor"
    ],
    "com.subhub.app.remote.RemoteSessionControlsAndroidTest": [
        "oneActionTracksActiveAndStaleStateWithoutGrantingAuthority",
        "repeatedActionClickWhileReviewIsPendingQueuesOnlyOnce",
        "actualDurationAutosaveAndPendingReceiptFenceTheSharedStartControls"
    ],
    "com.subhub.app.stats.LazySessionDurationAndroidTest": [
        "backgroundSyncOffUnchangedSessionSurvivesReopenAndStopsExactlyOnce",
        "existingScreenTimeMigratesWithoutOverlapAndNewSessionsAddNormally",
        "legacyHistoryAndUnknownOlderDailyEvidenceRemainALowerBound",
        "sameTimestampDifferentIdentitiesAndZoneChangesDoNotReplaceHistory",
        "failedLedgerWriteRollsBackMigrationAndCanRetry"
    ],
    "com.subhub.app.util.PageScrollViewAndroidTest": [
        "replacementRestoresAfterLayoutAndUsesTheLatestPosition",
        "newerNavigationAndGestureCancelRestorationAndShortPagesClamp",
        "pendingPositionSurvivesMultipleReplacementsBeforeTheFirstFrame"
    ]
}
# Retain exact class-wide source drift checks without admitting these older methods
# as runnable selectors. Adding/renaming a test requires an explicit inventory review.
SUBHUB_PASS_SOURCE_ONLY = {
    "com.subhub.app.remote.RemoteAppIconPresentationAndroidTest": [
        "actualActivityPreviewOpensFullHistoryAndUsesArrivingRemotePixels",
        "actualHomeOverviewReprojectsRecordedActivityAfterDescriptorArrival",
        "actualPersonContractReprojectsArrivalAndRevocationWithoutManualRefresh",
        "actualRemoteStatsReprojectsVisibleAllowanceAfterDescriptorArrival",
        "actualRemoteEditorRebindsIconsWithoutChangingIncompleteDraft",
        "realCallerLegacyAndWrongScopesNeverBorrowViewerPackageIcons"
    ],
    "com.subhub.app.remote.RemoteConfigurationAndroidTest": [
        "reconnectDrainsPreserveSentCommandAndPublishLatestEditOnlyAfterAppliedReceipt",
        "reconnectDrainsNeverRetryRejectedConfiguration",
        "reconnectDrainsNeverRetryConfigurationConflict",
        "reconnectDrainRechecksNewConsentBeforePublishingQueuedEdit",
        "appliedReceiptIsSeparateFromTransportAckAndReplay",
        "waitingConfigurationWriterLeavesAuthorityAvailableForActualTaskSweep",
        "failedSecondStoreRestoresTypedValuesAndRetainedSettings",
        "processDeathRollsBackBeforeRetryAndRecoverySurvivesRevocation",
        "staleRevisionUnavailableAppAndRevocationDoNotApply",
        "assetsMustFinishAndCommandIdentityCannotChange",
        "protocolRejectsSecretsCoercionsIncompleteActiveAndConcurrentDraft",
        "validatedCheckpointRecordsContactWithoutCompletingSyncAndRejectsInvalidOrRevokedHeads",
        "legacyContactUsesOnlyLocalRoleCorrectPastCheckpointsUntilExplicitObservationExists",
        "unchangedHeadsRefreshOnlyCaughtUpStreams",
        "terminalReceiptCannotRegressAndCommittedJournalKeepsAppliedSettings",
        "removedArtworkDoesNotReactivateLocalSelectionsAndMetadataSurvivesUnpair",
        "queuedFeatureChangeIsRejectedWhenSubOptsOutBeforeDeliveryAndReplayStaysRejected",
        "optedOutRuntimeFeaturesStayOffWithoutReplacingSavedRulesOrCredentials",
        "hardcoreStateCannotOverrideAnySavedFeatureChoice",
        "hardcoreStateCannotBypassQueuedFeatureRefusal",
        "remoteSnapshotsCannotContainLocalConsentOrHardcoreAuthority",
        "optingOutOfOneFeatureStillAllowsAnUnrelatedDurationChange",
        "disabledLimitsAlsoRefuseChangesToIndividualAllowances",
        "contractDomProfileFollowsAppliedOwnerDespiteOtherRelationshipsAndUpdates",
        "rePairingCannotAttributeRetainedContractToTheNextDomOrGeneration",
        "optionalNotificationChoicesDoNotChangeAppliedWriterReceiptsOrReplay",
        "incompletePriorNotificationDocumentCannotFailCommittedConfiguration"
    ],
    "com.subhub.app.remote.RemoteDurationAndroidTest": [
        "nativeInlineChoiceSurvivesRecreationAndQueuesOnlyTheSelectedPeersDuration",
        "inlineRemoteProfileFitsTheRequestedNativeFontAndShowsRandomBoundsInPlace"
    ],
    "com.subhub.app.remote.RemoteEditorAndroidTest": [
        "draftAndInvalidRawBudgetSurviveRecreation",
        "atmosphereRouteOpensSubliminalAndRoleLossClosesEditor",
        "dailyStatisticsRenderSourceUnitsAndUnknownDataHonestly",
        "focusedSectionsExposeOnlyTheirOwnDirectProfileActions",
        "removingCensorArtworkPreservesPopupArtworkAndPendingPickerGroup",
        "fixedDurationIgnoresHiddenRandomInputsAndDraftHasNoDestructiveRetry",
        "failedInitialLoadOffersRetryAndStatisticsRetainSelectedPageAndExpire",
        "pairedScreensFitCurrentDisplayAndFontConfiguration",
        "sharedNativeFeaturePagesUseRemoteDraftsAndPreserveDomDeviceState",
        "sharedCensorNativeSelectionAppliesOnlyToItsPairedIdentity",
        "sharedStatisticsPageUsesMirroredHistoryWithoutReadingLocalTotals",
        "sharedWhispersEnableIsQueuedWithoutChangingTheDomModule",
        "sharedPopupEnableDoesNotStartOrConfigureTheDomRuntime",
        "sharedTributeRuleSavesWithoutOpeningOrChangingPaymentCredentials",
        "editorDerivedFilesSurviveRecreationAndCloseWithoutDeletingPeerAssets",
        "peerNameChangesKeepRelationshipAndPersonIdentitiesStable"
    ],
    "com.subhub.app.remote.RemoteSessionControlsAndroidTest": [
        "unsupportedPeerHasOneDisabledStartAndRecreationQueuesNothing",
        "delayedReviewCannotConfirmAfterRecreationOrControllerLock",
        "failedSnapshotInvalidatesAnOutstandingReviewBeforeItCanConfirm",
        "dirtyDurationBlocksStartAndReceiptRemainsSeparateFromAppliedProof",
        "changedSourceReviewCannotQueueAndStopDoesNotDependOnDurationDraftOrHardcore",
        "nativeStartStopControlsFitAndKeepTheirAppliedDurationVisible",
        "snapshotFailureRecoversAutomaticallyAndObservationResumesWithoutCommands"
    ]
}
RECONNECT_NATIVE = {
    "com.subhub.app.remote.RemoteFeatureSchedulingAndroidTest": [
        "stableReconnectClearsOfflineBackoffAndCoalescesExistingBackgroundWork",
        "stableReconnectCannotBypassBackgroundOptOutOrRetiredRuntime",
    ],
    "com.subhub.app.remote.RemoteConfigurationAndroidTest": [
        "reconnectDrainsPreserveSentCommandAndPublishLatestEditOnlyAfterAppliedReceipt",
        "reconnectDrainsNeverRetryRejectedConfiguration",
        "reconnectDrainsNeverRetryConfigurationConflict",
        "reconnectDrainRechecksNewConsentBeforePublishingQueuedEdit",
    ],
}
RECONNECT_SOURCE_ONLY = {
    "com.subhub.app.remote.RemoteFeatureSchedulingAndroidTest": [
        "retainedRevocationsCannotConsumeForegroundIncomingOrCurrentPeerCapacity",
        "cancellationRetainsRunningAdmissionAndRejectedQueuedWorkReleasesOwnership",
        "backgroundResponderRetainsWindowAndRespectsFutureDueTimes",
        "completedOutgoingDeliveryStillLeavesTheBackgroundResponderAvailable",
        "stoppedBackgroundWindowReleasesOwnershipWithoutWaitingForDeadline",
        "failedBackgroundPeerRetriesOnlyAfterItsBackoff",
        "preexistingBusyOwnerCanSatisfyWindowWithAnExactCompletedSync",
        "preexistingBusyOwnerDoesNotHideMutationOrMissingCompletion",
        "dirtyMutationWhileBusyRemainsDueWhenTheOldExchangeExits",
        "completionCannotClobberMutationAndFailuresBackOffToABoundedDelay",
        "connectedServiceLeaseIsSeparateFromPairingAndOldCloseCannotRetireANewOwner",
        "connectedServiceRefusesPendingOnlyPeersWithoutBorrowingPairingAuthority",
        "connectedServiceStartsFromVisibleLifecycleButNotBackgroundNetworkWork",
        "connectedServiceBackpressureKeepsOneFutureDueWakeInsteadOfSpinning",
        "backgroundOptOutRetiresOnlyBackgroundOwnersAndForegroundSyncContinues",
        "queuedServiceStartAndLeaseAcquisitionCannotOutrunPersistedOptOut",
        "optOutDrainsOwnedBackgroundWindowWithoutClosingForegroundEndpoint",
        "quietBackgroundCadenceKeepsNewDataFastWithoutResettingOfflineBackoff",
        "dirtyBackgroundFailureBacksOffButNetworkResetKeepsRetryDue",
        "backgroundHidingPreservesBackoffAndForegroundResetsIt",
        "foregroundPollingStopsWhileHiddenAndRestartsOnce",
        "pairingPollsStopAtLastLeaseAndRestartForReplacement",
        "deviceDataWakeIsContextScopedAndOptOutCancelsItsEffect",
        "deviceDataRequiresAnActiveSubAndPreservesOfflineBackoff",
        "sourceObserversDoNotCrossContextsAndRetiredObserversCannotWake",
        "selectedAppActivityPersistsOnceAndQueuesTheExistingSourceWake",
        "recordedUsageAndStatsWakeButRejectedUsageAndTelemetryReadsDoNot",
    ],
    "com.subhub.app.remote.RemoteConfigurationAndroidTest": [
        method for method in (SUBHUB_PASS_NATIVE["com.subhub.app.remote.RemoteConfigurationAndroidTest"]
                              + SUBHUB_PASS_SOURCE_ONLY["com.subhub.app.remote.RemoteConfigurationAndroidTest"])
        if method not in RECONNECT_NATIVE["com.subhub.app.remote.RemoteConfigurationAndroidTest"]
    ],
}
# Full reviewed scheduler coverage plus real inbound transport and independent deadlines.
BATTERY_NATIVE = {
    "com.subhub.app.remote.RemoteFeatureSchedulingAndroidTest": (
        RECONNECT_NATIVE["com.subhub.app.remote.RemoteFeatureSchedulingAndroidTest"]
        + RECONNECT_SOURCE_ONLY["com.subhub.app.remote.RemoteFeatureSchedulingAndroidTest"]
    ),
    "com.subhub.app.remote.RemoteBackgroundResponderAndroidTest": [
        "delayedAuthenticatedInboundExchangesAfterOutgoingHasFinished",
    ],
    "com.subhub.app.remote.PairingLifecycleAndroidTest": [
        "productionDeadlineProducerRunsWhileOtherWorkQueuesAreBlocked",
    ],
}
BATTERY_SOURCE_ONLY = {
    "com.subhub.app.remote.PairingLifecycleAndroidTest": [
        "originalClocksOneUseCancellationAndCommittedOutcomeStaySeparate",
        "cheapLeaseDeadlinePublishesClosureWithoutSqlMutation",
        "observedFirstApprovalRearmsCheapDeadlineOnceWithoutRenewingItsBound",
        "stagedFirstApprovalDefeatsStaleExpiryAndFailedSqlCannotRetainItsLease",
    ],
}
# Combined source-data/profile pass. Exact UI members are reviewed alongside battery ownership.
DEVICE_PASS_NATIVE = {
    **BATTERY_NATIVE,
    "com.subhub.app.journal.JournalUiAndroidTest": [
        "profileAndOverviewPreviewsShowOnlyTitleAndUseCreationTimeFallback",
        "previewStopsPollingWhileHiddenAndRefreshesOnReturnAndReattachment",
    ],
    "com.subhub.app.tasks.TasksAndroidTest": [
        "completedCounterTracksAcceptedOnlyAcrossRolesAndRecreation",
        "domOverviewCompletedCounterRefreshesForAcceptedReviews",
    ],
    "com.subhub.app.appmode.AllowanceProgressAndroidTest": [
        "nativeRoundedMetersKeepExactValuesAndAccessibility",
        "nativeRoundedMetersMirrorForRtlWithoutInflatingSmallUsage",
    ],
    "com.subhub.app.people.ProfileStatsActivityAndroidTest": [
        "profileAppTimeSharesSessionCardAcrossRefreshAndRecreation",
        "profileOpensLocalStatsDirectlyWithTotalsAllowancesAndHistoryOnOnePage",
    ],
    "com.subhub.app.remote.RemoteAppIconPresentationAndroidTest": [
        "actualRemoteStatsReprojectsVisibleAllowanceAfterDescriptorArrival",
    ],
    "com.subhub.app.people.ProfileActionRowAndroidTest": [
        "actionPairsReverseOnlyWhenWideAndRetainTheirListeners",
        "actionPairsStackForLargeTextAndLongLabelsWithoutTruncation",
    ],
    "com.subhub.app.people.ProfileAndroidTest": [
        "profileEditorPairsActionsAndKeepsAvatarRemovalAsDraft",
        "emptyAboutMeIsOptionalAndOnlyChangesOnSaveForEitherRole",
        "avatarSelectionChangeRemovalAndCancellationKeepRoundedNativePixels",
    ],
    "com.subhub.app.people.AtmosphereContractDetailsAndroidTest": [
        "localSummaryListsSelectedCategoriesAndQualitativePopupDetails",
        "disabledEmptyAndCustomSettingsRemainExplicitWithoutInventedSelections",
        "remoteSummaryMatchesLocalButReadsOnlyItsSuppliedSnapshot",
        "remoteConsentIsNeverInferredFromMissingOrStaleObservations",
        "subContractExpandsWithExistingBoldStyleAndRestoresAfterRecreation",
        "domContractShowsDetailsWhileKeepingItsExistingEditRoute",
        "unknownContractHidesDetailsWhileStaleContractKeepsLabeledSavedValues",
    ],
    "com.subhub.app.remote.CachedPeerReadModelAndroidTest": [
        "atmosphereSummaryUsesOnlyThePeerBaselineAndKeepsConsentFreshnessSeparate",
    ],
}
DEVICE_PASS_SOURCE_ONLY = {
    **BATTERY_SOURCE_ONLY,
    "com.subhub.app.journal.JournalUiAndroidTest": [
        "textDraftIsBoundedPrivateSavedAndRecreatedWithoutOpeningKeyboard",
        "pickerCopiesFiveDocumentsAndRejectsDeclaredAndActualOversize",
        "sharedViewAndProviderRejectWithdrawnEntriesAndWrongRole",
        "deniedMicrophoneLeavesTextEditingAndSavingAvailable",
        "nativeRecordingStartsOnlyOnTapStopsOnRecreationAndPlays",
        "nativeRecordingLimitStopsAtFiveMinutesAndKeepsPlayableBoundedAudio",
        "boundDomPreviewJournalOpensSharedReadOnlyTargetAndEmptyBioLeavesNoGap",
        "bothOwnedProfilesPlaceJournalBelowAboutAndCapturePopulatedEditor",
        "unifiedPreviewsAndOptionalEditorRenderWithoutLeakingPrivateJournalEntries",
        "sharedPendingWithdrawalLoadErrorAndBusyStatesKeepTheirNativeHierarchy",
        "retainedVoiceFixtureRendersTheSameNativePlayerAndRemovalControls",
    ],
    "com.subhub.app.tasks.TasksAndroidTest": [
        "createWithNativeControlsAndRetainAuthorDraftAfterRecreation",
        "subOnlyHeaderSurvivesDetailBackAndRecreation",
        "bothSubHeaderSurvivesDetailBackAndRecreation",
        "bothDomHeaderSurvivesDetailBackAndRecreation",
        "subRequestsCompletionWithNativeTextThenDomAcceptsTheExactSubmission",
        "outsideRequestCanBeDeniedWithNativeReviewControls",
        "refuseOneOccurrenceThenCancelARecurringSeriesWithNativeTaps",
        "resumeRevalidatesRoleAndDropsAnAuthorDraft",
        "localSubOwnsOptOutAcrossRecreationAndDomCannotEditIt",
        "documentPickerUsesSupportedMimeTypesAndCancellationRetainsForm",
        "mediaViewerRejectsAnAttachmentOutsideTheRelationshipProjection",
        "skippedOccurrencesAppearOnlyInHistoryWithoutUserActions",
        "historyPreviewsKeepOutcomeOrderAndCompletionRequestTimesAcrossRefreshAndRecreation",
        "visibleImageClosesOnRoleOrOptOutChangeWithoutPausing",
        "taskListRefreshRetainsUnchangedCardsAndPositionWhenAnotherTaskArrives",
        "subHomeTaskCountUpdatesWithoutReplacingOtherControlsOrPosition",
        "tasksTabRetainsListDetailAndSubmissionNavigation",
        "domTasksOverviewOpensTheSelectedProfileAndReturnsFromItsList",
        "profileGroupsTasksImmediatelyAfterContractAndKeepsItsNativeRoute",
        "homeActiveCountTracksZeroSingularPluralAndReplayedTerminalOutcomes",
        "domCancellationRequiresConfirmationRetainsAuditAndStopsPendingEffects",
        "domCanArchiveOnlyOneOccurrenceAndArchiveWhileTasksAreDisabled",
    ],
    "com.subhub.app.people.ProfileStatsActivityAndroidTest": [
        "inlineDurationChangesFutureSelectionWithoutChangingTheActiveSession",
        "aDomOnlyModeChangeRejectsTheAlreadyDeliveredDurationListener",
        "oldStatsSectionRedirectsOnceAndRecreationReturnsToProfile",
        "remoteDurationAndStatsStayBoundToTheSelectedPerson",
        "recentActivitySitsBelowDestinationsAndRefreshesOnlyWhileStarted",
        "activityChangesCoalesceBehindOneInFlightRead",
        "switchingToSubOnlyRejectsAnInFlightPreviewAndItsStaleViewAllAction",
        "selectedPreviewRejectsFactsFromAnotherPerson",
        "emptyLocalPreviewOpensLocalHistoryWithoutRequiringAnEntryKey",
        "switchingToSubOnlyRejectsAnInFlightRemoteProfileWithoutClearingLocalAuthentication",
        "localProfileInlineDurationAndFlatStatsFitActualRequestedFontAndViewport",
    ],
    "com.subhub.app.remote.RemoteAppIconPresentationAndroidTest": [
        "actualActivityPreviewOpensFullHistoryAndUsesArrivingRemotePixels",
        "actualHomeOverviewReprojectsRecordedActivityAfterDescriptorArrival",
        "actualPersonContractReprojectsArrivalAndRevocationWithoutManualRefresh",
        "actualRemoteEditorRebindsIconsWithoutChangingIncompleteDraft",
        "sharedNativeLimitsAndAppSelectorKeepRemoteIconScope",
        "realCallerLegacyAndWrongScopesNeverBorrowViewerPackageIcons",
    ],
    "com.subhub.app.people.ProfileAndroidTest": [
        "profileNavigationRetainsStatisticsAndAchievementsInTheSameHost",
        "editsSaveAtomicallyAndCancelledDraftsLeaveBothRoleProfilesUnchanged",
        "subParticipationRemainsAccessibleWithoutDomUnlockAndSurvivesRecreation",
        "subProfilePlacesFeatureChoicesBeforeJournalAndKeepsTasksConsentLocal",
        "nativeRoleSwitchesKeepDomAndSubIdentitySeparate",
        "returningToProfileAfterAnotherWorkspaceChangeUsesTheCurrentRoleOnFirstResume",
        "domOnlyOwnProfileStaysEditableWhileStatisticsRequireTheExistingPin",
        "pairedSubContractStartsWithReadOnlyAllowancesAndProfileHasNoDeviceFiller",
        "avatarDecoderRejectsBrokenOrOversizedImageDimensionsBeforeSaving",
        "renderedRoleProfilesRetainReadableNavigationStatisticsAndOriginalAchievements",
        "contractDomPreviewKeepsItsOwnIdentityBioAndRoundedAvatar",
        "compactAllowancesRetainNativeTargetsExplicitStatesAndPersistence",
        "compactReadOnlyAllowancesKeepUnknownAndHardcoreContextWithoutEditControls",
        "largeTextCaptureCoverageShowsWholeControlsAndExpandedBioEnd",
        "bothRoleProfileCardsUseSwitchAndSingleRolesKeepTheirIdentity",
        "profileSelectorsUseEqualHalvesOrAReadableStack",
    ],
    "com.subhub.app.remote.CachedPeerReadModelAndroidTest": [
        "offlineMirrorsStayIndependentAndContactCannotCrossGenerationsOrBypassContinuity",
    ],
}
LARGE_FONT = "com.subhub.app.appmode.ExtraTimeUiAndroidTest#largeFontNarrowReviewKeepsControlsAndReceiptsReadable"
ALL_SELECTORS = [f"{cls}#{method}" for cls, methods in NATIVE.items() for method in methods]
ALL_SELECTORS.remove(LARGE_FONT)
ALL_SELECTORS.append(LARGE_FONT)
# Keep the global font-scale mutation last in both suites. New rendering fixtures
# otherwise run at 1.0; DurationChoices tests supply their own scoped font contexts.
SUBHUB_PASS_SELECTORS = ALL_SELECTORS[:-1] + [
    f"{cls}#{method}" for cls, methods in SUBHUB_PASS_NATIVE.items() for method in methods
] + [LARGE_FONT]
RECONNECT_SELECTORS = [f"{cls}#{method}" for cls, methods in RECONNECT_NATIVE.items() for method in methods]
BATTERY_SELECTORS = [f"{cls}#{method}" for cls, methods in BATTERY_NATIVE.items() for method in methods]
DEVICE_PASS_SELECTORS = [f"{cls}#{method}" for cls, methods in DEVICE_PASS_NATIVE.items() for method in methods]
SUITES = {"extra-time": ALL_SELECTORS, "subhub-pass": SUBHUB_PASS_SELECTORS, "reconnect": RECONNECT_SELECTORS, "battery": BATTERY_SELECTORS, "device-pass": DEVICE_PASS_SELECTORS}
SUITE_COUNTS = {"extra-time": 21, "subhub-pass": 52, "reconnect": 6, "battery": 31, "device-pass": 53}
PR_SUBSET_LABEL = "native-subset"
PR_SUBSET_START = "<!-- native-selectors:start -->"
PR_SUBSET_END = "<!-- native-selectors:end -->"
MAX_EVENT_BYTES = 2 * MIB
MAX_PR_BODY_BYTES = 64 * 1024
# These existing profile capture helpers retain native PNGs without a composition receipt.
# Keep their required visual artifacts distinct from the stronger UI_WITNESSES contract.
UI_SCREENSHOTS = {
    "com.subhub.app.people.ProfileStatsActivityAndroidTest#profileAppTimeSharesSessionCardAcrossRefreshAndRecreation": [
        "local-session-app-time-active", "local-session-app-time-idle"],
    "com.subhub.app.people.ProfileAndroidTest#profileEditorPairsActionsAndKeepsAvatarRemovalAsDraft": [
        "editor-avatar-action-row", "editor-save-cancel-row"],
}

def missing_screenshots(expected, exported):
    names = [PurePosixPath(item["path"]).name for item in exported]
    return [prefix for prefix in expected if not any(
        name.endswith(".png") and "-failure-" not in name
        and (name == prefix + ".png" or name.startswith(prefix + "-")) for name in names)]

UI_WITNESSES = {
    "com.subhub.app.appmode.ExtraTimeUiAndroidTest#requestApproveAndDeclineRetainAllowanceAndInputDrafts": [
        "extra-time-sub-request", "extra-time-sub-pending", "extra-time-dom-review",
        "extra-time-sub-approved", "extra-time-sub-declined", "extra-time-sub-repeated-request"],
    "com.subhub.app.appmode.ExtraTimeUiAndroidTest#remoteRequestIconArrivesWithoutPollingAndRevocationClearsIt": [
        "extra-time-dom-remote-icon"],
    "com.subhub.app.appmode.ExtraTimeUiAndroidTest#unavailableRequestExplainsItsStateAndRecoversBeforeOneTapSubmission": [
        "extra-time-sub-unavailable"],
    "com.subhub.app.appmode.ExtraTimeUiAndroidTest#unsupportedPeerExplainsCapabilityAndRecoversAfterNegotiation": [
        "extra-time-sub-support-unconfirmed"],
    LARGE_FONT: ["extra-time-sub-large-font", "extra-time-sub-shared-only-large-font", "extra-time-dom-large-font"],
    "com.subhub.app.penance.TributeSettingsRestoreAndroidTest#rulesAndSpendingLimitsRemainDistinctAfterNativeNavigationAndRecreation": [
        "tribute-rules-grouping", "tribute-spending-limits"],
}


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(MIB), b""):
            value.update(chunk)
    return value.hexdigest()


def notification_permission_state(info):
    """Read API35's default-device state for the installed target in its sole User 0.

    Android 15 omits zero-valued permission entries, and Settings omits the flags
    suffix when API flags are zero. This is not a general missing-line fallback:
    require a closed package dump, the requested permission, and a complete runtime
    section for the installed user before interpreting absence as denied/no flags.

    AOSP android-15.0.0_r1, under https://github.com/aosp-mirror/platform_frameworks_base:
    - services/core/java/com/android/server/pm/Settings.java:
      dumpRuntimePermissionsLPr / permissionFlagsToString.
    - services/permission/java/com/android/server/permission/access/permission/:
      PermissionService.kt:getLegacyPermissionState enumerates stored entries;
      AppIdPermissionPolicy.kt:getPermissionFlags / updatePermissionFlags default
      absent entries to 0 and remove zero entries via putWithDefault;
      PermissionFlags.kt:isPermissionGranted(0) is false, toApiFlags(0) is 0.
    The producer verifies API35 before calling this observer. Other layouts fail closed.
    """
    def section(lines, header, require_end=False):
        starts = [i for i, line in enumerate(lines) if re.fullmatch(header, line)]
        if len(starts) != 1:
            raise ValueError("Notification permission dump section is missing or ambiguous")
        start = starts[0]
        indent = len(lines[start]) - len(lines[start].lstrip())
        end = next((i for i in range(start + 1, len(lines)) if lines[i].strip()
                    and len(lines[i]) - len(lines[i].lstrip()) <= indent), len(lines))
        if require_end and (end == len(lines) or not re.fullmatch(r"[A-Za-z][A-Za-z ]*:", lines[end])):
            raise ValueError("Notification permission package dump is incomplete")
        return lines[start + 1:end]

    packages = section(info.splitlines(), r"Packages:", require_end=True)
    headers = [line for line in packages if line.startswith("  Package ")]
    if len(headers) != 1 or not re.fullmatch(r"  Package \[com\.subhub\.app\] \([0-9a-f]+\):", headers[0]):
        raise ValueError("Notification permission target package is missing or ambiguous")
    package = section(packages, re.escape(headers[0]))
    if any("sharedUser" in line for line in package):
        raise ValueError("Shared-UID notification permission state is unsupported")
    permission = "android.permission.POST_NOTIFICATIONS"
    requested = section(package, r"    requested permissions:")
    if requested.count("      " + permission) != 1:
        raise ValueError("Target does not uniquely request POST_NOTIFICATIONS")
    users = [line for line in package if line.startswith("    User ")]
    if len(users) != 1 or not re.fullmatch(r"    User 0: .+", users[0]) \
            or "installed=true" not in users[0].split():
        raise ValueError("Notification permission installed User 0 is missing or ambiguous")
    user = section(package, re.escape(users[0]))
    runtime = section(user, r"      runtime permissions:")
    states = {}
    for line in runtime:
        if not line.strip():
            continue
        match = re.fullmatch(r"        ([A-Za-z0-9_.]+): granted=(true|false)(?:, flags=\[([^]\r\n]*)\])?", line)
        if not match or match[1] in states:
            raise ValueError("Notification runtime permission record is malformed or duplicated")
        flags = [value.strip() for value in match[3].split("|")] if match[3] and match[3].strip() else []
        if any(not re.fullmatch(r"[A-Z][A-Z0-9_]*", flag) for flag in flags) or len(set(flags)) != len(flags):
            raise ValueError("Notification runtime permission flags are malformed or duplicated")
        states[match[1]] = {"granted": match[2] == "true", "flags": sorted(flags)}
    # Do not misread a displaced, malformed or install-permission record as absent.
    occurrences = sum(permission in line for line in package)
    if occurrences != 1 + (permission in states):
        raise ValueError("Notification permission appears outside its expected sections")
    return states.get(permission, {"granted": False, "flags": []})


def missing_loader_libraries(output):
    return set(re.findall(r"^\s*([A-Za-z0-9_+.-]+)\s+=>\s+not found\s*$", output, re.MULTILINE))


def runtime_packages(missing):
    unknown = missing - EMULATOR_RUNTIME_PACKAGES.keys()
    if unknown:
        raise RuntimeError("Unreviewed emulator host dependencies: " + ", ".join(sorted(unknown)))
    return sorted({EMULATOR_RUNTIME_PACKAGES[name] for name in missing})


def suite_selectors(suite):
    if suite not in SUITES:
        raise ValueError("Unknown native suite: " + suite)
    values = SUITES[suite]
    if len(values) != SUITE_COUNTS[suite] or len(set(values)) != SUITE_COUNTS[suite]:
        raise ValueError(f"The {suite} native inventory must contain exactly {SUITE_COUNTS[suite]} unique methods")
    if suite in ("extra-time", "subhub-pass") and values[-1] != LARGE_FONT:
        raise ValueError("The global large-font contract must run last")
    return list(values)


def source_inventory(suite="extra-time"):
    """Reconcile exact class sources, including reviewed but non-selected methods."""
    eligible = set(suite_selectors(suite))
    native = dict(NATIVE)
    source_only = {}
    if suite == "subhub-pass":
        native.update(SUBHUB_PASS_NATIVE)
        source_only = SUBHUB_PASS_SOURCE_ONLY
    elif suite == "reconnect":
        native = RECONNECT_NATIVE
        source_only = RECONNECT_SOURCE_ONLY
    elif suite == "battery":
        native = BATTERY_NATIVE
        source_only = BATTERY_SOURCE_ONLY
    elif suite == "device-pass":
        native = DEVICE_PASS_NATIVE
        source_only = DEVICE_PASS_SOURCE_ONLY
    inventory = {}
    pattern = re.compile(
        r"@(?:org\.junit\.)?Test\b(?:\([^)]*\))?"
        r"(?P<annotations>(?:\s*@[A-Za-z0-9_.]+(?:\([^)]*\))?)*)"
        r"\s+public\s+void\s+(?P<method>[A-Za-z0-9_]+)\s*\(", re.MULTILINE)
    for cls, selected_methods in native.items():
        path = ROOT / "app/src/androidTest/java" / (cls.replace(".", "/") + ".java")
        text = path.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([\w.]+)\s*;", text, re.MULTILINE)
        if not package or package[1] + "." + path.stem != cls:
            raise ValueError(f"Wrong selected native class: {path}")
        found = {}
        for match in pattern.finditer(text):
            name = match["method"]
            if name in found:
                raise ValueError(f"Duplicate native method: {cls}#{name}")
            annotation = match["annotations"]
            minimum = re.findall(r"(?:minSdkVersion\s*=\s*)(\d+)", annotation)
            maximum = re.findall(r"(?:maxSdkVersion\s*=\s*)(\d+)", annotation)
            if f"{cls}#{name}" in eligible and ("RequiresApi" in annotation or "Ignore" in annotation
                    or any(int(v) > API for v in minimum) or any(int(v) < API for v in maximum)):
                raise ValueError(f"Selected native method is not eligible at API {API}: {cls}#{name}")
            found[name] = {"annotations": annotation.strip(), "line": text.count("\n", 0, match.start()) + 1}
        excluded = source_only.get(cls, [])
        expected = selected_methods + excluded
        if len(set(expected)) != len(expected) or set(found) != set(expected):
            raise ValueError(f"Native inventory changed for {cls}: expected {expected}, found {list(found)}")
        inventory[cls] = {"path": path.relative_to(ROOT).as_posix(), "sha256": digest(path), "methods": found,
                          "selectedMethods": selected_methods, "sourceOnlyMethods": excluded}
    if {f"{cls}#{method}" for cls, methods in native.items() for method in methods} != eligible:
        raise ValueError("Native source inventory differs from the selected suite")
    return inventory


def selectors(raw, suite="extra-time"):
    known = suite_selectors(suite)
    requested = [value.strip() for value in re.split(r"[,\n]", raw) if value.strip()]
    if not requested:
        return known
    if len(set(requested)) != len(requested) or not set(requested).issubset(known):
        raise ValueError("Selectors must be a unique subset of the selected suite's fixed native inventory")
    return [value for value in known if value in requested]


def pr_subset(body, suite):
    """Treat the PR body as bounded data, never as shell or workflow expressions."""
    if not isinstance(body, str) or len(body.encode("utf-8")) > MAX_PR_BODY_BYTES:
        raise ValueError("Native subset requires a bounded PR body")
    # Reject duplicate, misspelled, reordered, or extra lookalike marker comments.
    markers = re.findall(r"<!--\s*native-selectors\b", body, re.IGNORECASE)
    if len(markers) != 2 or body.count(PR_SUBSET_START) != 1 or body.count(PR_SUBSET_END) != 1:
        raise ValueError("Native subset requires exactly one correctly delimited selector block")
    start = body.index(PR_SUBSET_START) + len(PR_SUBSET_START)
    end = body.index(PR_SUBSET_END)
    if end < start:
        raise ValueError("Native subset selector markers are out of order")
    try:
        requested = json.loads(body[start:end])
    except (ValueError, RecursionError) as failure:
        raise ValueError("Native subset selector block must be a JSON array") from failure
    known = suite_selectors(suite)
    if not isinstance(requested, list) or not requested or len(requested) > len(known) \
            or any(not isinstance(value, str) for value in requested):
        raise ValueError("Native subset must be a nonempty JSON array of exact method strings")
    if len(set(requested)) != len(requested) or not set(requested).issubset(known):
        raise ValueError("Native subset must contain unique exact selectors from the selected suite")
    return [value for value in known if value in requested]


def selected_methods(suite):
    """Read opt-in PR subsets before producer directories, builds, or guest work."""
    if os.environ.get("GITHUB_EVENT_NAME") == "pull_request":
        path = os.environ.get("GITHUB_EVENT_PATH")
        if not path:
            raise ValueError("PR native selection requires GITHUB_EVENT_PATH")
        with Path(path).open("rb") as source:
            raw = source.read(MAX_EVENT_BYTES + 1)
        if len(raw) > MAX_EVENT_BYTES:
            raise ValueError("PR event exceeds the native selection size bound")
        try:
            event = json.loads(raw)
        except (ValueError, RecursionError) as failure:
            raise ValueError("Native selection requires valid PR event JSON") from failure
        pr = event.get("pull_request") if isinstance(event, dict) else None
        labels = pr.get("labels") if isinstance(pr, dict) else None
        if not isinstance(labels, list) or any(not isinstance(label, dict) or not isinstance(label.get("name"), str)
                                              for label in labels):
            raise ValueError("Native selection requires the PR event's label list")
        if any(label["name"] == PR_SUBSET_LABEL for label in labels):
            return pr_subset(pr.get("body"), suite), "pull_request_body"
    raw = os.environ.get("EXTRA_TIME_SELECTORS", "")
    return selectors(raw, suite), "workflow_selectors" if raw.strip() else "suite_default"


def properties(path):
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            if key.strip() in values:
                raise ValueError("Duplicate source version property")
            values[key.strip()] = value.strip()
    if not re.fullmatch(r"[1-9][0-9]*", values.get("VERSION_CODE", "")):
        raise ValueError("Invalid source Android version code")
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", values.get("VERSION_NAME", "")):
        raise ValueError("Invalid source version name")
    return int(values["VERSION_CODE"]), values["VERSION_NAME"]


def signer_certificate(signature):
    declarations = [line.strip() for line in signature.splitlines() if "certificate SHA-256 digest:" in line]
    if len(declarations) != 1:
        raise ValueError("APK signature must declare exactly one signer certificate")
    certificate = re.fullmatch(r"(?:Signer #1 certificate|V2 Signer: certificate) SHA-256 digest: ([0-9a-f]{64})", declarations[0])
    if not certificate:
        raise ValueError("Unsupported signer declaration or malformed certificate digest")
    return certificate[1]


def apk_metadata(kind, badging, manifest, signature, source_code, source_name):
    """Read literal APK declarations, keeping an absent test version separate."""
    package = re.search(r"package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", badging)
    certificate = signer_certificate(signature)
    if not package:
        raise ValueError(f"Incomplete actual APK metadata: {kind}")
    code = int(package[2]) if package[2] else None
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.MULTILINE)
    if not minimum or int(minimum[1]) > API:
        raise ValueError(f"APK is not eligible for selected Android API: {kind}")
    item = {"package": package[1], "declaredVersionCode": code, "declaredVersionName": package[3] or None,
            "minSdk": int(minimum[1]), "certificateSha256": certificate}
    if kind == "target":
        native = re.search(r"^native-code:\s*(.*)$", badging, re.MULTILINE)
        if package[1] != "com.subhub.app" or code != source_code or package[3] != source_name:
            raise ValueError("Target APK does not carry unexpanded source version metadata")
        item["abis"] = re.findall(r"'([^']+)'", native[1]) if native else []
        if item["abis"] != ["x86_64"]:
            raise ValueError("Native APK does not contain exactly the selected x86_64 ABI")
    elif kind == "test":
        if package[1] != "com.subhub.app.test":
            raise ValueError("Wrong instrumentation package")
        section = re.search(r"(?ms)^\s*E: instrumentation\b.*?(?=^\s*E:|\Z)", manifest)
        if not section or not re.search(r'android:name\([^)]*\)="com\.subhub\.app\.SubHubTestRunner"', section[0]) \
                or not re.search(r'android:targetPackage\([^)]*\)="com\.subhub\.app"', section[0]):
            raise ValueError("Actual test manifest does not declare the custom SubHub runner/target")
        item["declaredRunner"] = RUNNER
    else:
        raise ValueError("Unexpected APK metadata kind")
    return item


def instrumentation_result(text, selector, exit_code):
    """Require one exact started-and-passed test, never an ignored or assumed run."""
    if exit_code != 0 or any(token in text for token in (
            "FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED",
            "AssumptionViolatedException", "INSTRUMENTATION_RESULT: shortMsg=")):
        raise ValueError("Instrumentation failed, aborted, or assumed")
    selected_class, selected_method = selector.split("#", 1)
    bundle, statuses, final_codes = {}, [], []
    for index, line in enumerate(text.splitlines()):
        field = re.fullmatch(r"INSTRUMENTATION_STATUS: ([A-Za-z0-9_]+)=(.*)", line)
        if field:
            if field[1] in bundle:
                raise ValueError("Ambiguous instrumentation status field")
            bundle[field[1]] = field[2]
        code = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if code:
            statuses.append((int(code[1]), bundle, index)); bundle = {}
        final = re.fullmatch(r"INSTRUMENTATION_CODE: (-?\d+)", line)
        if final:
            final_codes.append((int(final[1]), index))
    if bundle or [item[0] for item in statuses] != [1, 0] or len(final_codes) != 1 \
            or final_codes[0][0] != -1 or final_codes[0][1] <= statuses[-1][2]:
        raise ValueError("Instrumentation did not report one start, exact success, and final RESULT_OK")
    for _, fields, _ in statuses:
        if fields.get("class") != selected_class or fields.get("test") != selected_method \
                or fields.get("numtests") != "1" or fields.get("current") != "1" or "stack" in fields:
            raise ValueError("Instrumentation selector or actual test count differs")
    if not re.search(r"\bOK \(1 tests?\)", text):
        raise ValueError("Instrumentation final summary is incomplete")
    return {"class": selected_class, "test": selected_method, "numtests": 1,
            "statusCodes": [1, 0], "instrumentationCode": -1}


class Producer:
    def __init__(self, suite="extra-time"):
        if sys.platform != "linux" or os.environ.get("GITHUB_ACTIONS") != "true":
            raise ValueError("Native production requires an ephemeral GitHub Linux runner")
        self.suite = suite
        self.selected, self.selection_source = selected_methods(suite)
        self.run_id = os.environ["GITHUB_RUN_ID"]
        self.attempt = os.environ["GITHUB_RUN_ATTEMPT"]
        if not self.run_id.isdigit() or not self.attempt.isdigit():
            raise ValueError("Invalid hosted run identity")
        self.run = f"extra-time-{self.run_id}-{self.attempt}"
        self.out = ROOT / "build/reports/extra-time-native" / self.run
        self.out.mkdir(parents=True, exist_ok=False)
        temp = Path(os.environ["RUNNER_TEMP"]).resolve()
        self.host = temp / self.run
        if self.host.exists() or self.host.resolve().parent != temp:
            raise ValueError("Owned emulator host directory is not fresh")
        self.host.mkdir()
        sdk_raw = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk_raw:
            raise ValueError("Hosted Android SDK is unavailable")
        self.sdk = Path(sdk_raw).resolve()
        self.env = dict(os.environ, ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk),
                        ANDROID_USER_HOME=str(self.host / "android-user"),
                        ANDROID_EMULATOR_HOME=str(self.host / "android-user"),
                        ANDROID_AVD_HOME=str(self.host / "avd-index"))
        self.serial = "emulator-5554"
        self.avd = "subhub_extra_time_api35"
        self.soft_deadline = time.monotonic() + 35 * 60
        self.record = {"schema": 1, "workflowPath": ".github/workflows/extra-time-native.yml",
                       "runId": int(self.run_id), "runAttempt": int(self.attempt), "evidenceRun": self.run,
                       "state": "admission", "profile": {"immediateQualityExperiment": False, "replacementLabCandidate": False},
                       "producerSoftTimeoutSeconds": 35 * 60,
                       "suite": self.suite, "suiteSelectors": suite_selectors(self.suite),
                       "selectionSource": self.selection_source,
                       "selected": self.selected, "methods": {}, "remaining": list(self.selected), "notSelected": [
                           value for value in suite_selectors(self.suite) if value not in self.selected], "hostedFreshGuest": True}
        self.index = 0
        self.emulator = None
        self.emulator_log = None
        self.original = None
        self.closing = False
        self.ready = False
        self.save()

    def save(self):
        temporary = self.out / "receipt.tmp"
        temporary.write_text(json.dumps(self.record, indent=2) + "\n", encoding="utf-8")
        temporary.replace(self.out / "receipt.json")

    def resource_guard(self, first_boot=False):
        if not self.closing and time.monotonic() >= self.soft_deadline:
            raise TimeoutError("Producer soft deadline; reserve the remaining job time for restoration, shutdown and artifact upload")
        probes = {"workspace": shutil.disk_usage(ROOT).free, "ownedGuest": shutil.disk_usage(self.host).free}
        minimum = min(probes.values())
        self.record["minimumFreeBytes"] = min(minimum, self.record.get("minimumFreeBytes", minimum))
        required = FIRST_BOOT_FREE if first_boot else RUNTIME_FREE
        if minimum < required:
            raise RuntimeError(f"Disk admission refused: {minimum} free, {required} required; no cleanup or floor bypass")
        if first_boot:
            available = re.search(r"^MemAvailable:\s+(\d+)\s+kB$", Path("/proc/meminfo").read_text(), re.MULTILINE)
            if not available or int(available[1]) * 1024 < MEMORY_AVAILABLE:
                raise RuntimeError("Available RAM cannot reserve 2560 MiB emulator plus 2048 MiB host headroom")
            self.record["admission"] = {"freeBytes": probes, "requiredFirstBootBytes": FIRST_BOOT_FREE,
                                        "memoryAvailableBytes": int(available[1]) * 1024,
                                        "requiredAvailableMemoryBytes": MEMORY_AVAILABLE,
                                        "guestMemoryMiB": 2560, "guestCores": 2}

    def command(self, args, timeout=30, check=True, label="command", input_bytes=None, quiet=False, limit=MAX_LOG):
        self.index += 1
        path = self.out / f"{self.index:03d}-{label}.log"
        if not self.closing:
            self.resource_guard()
        started = time.monotonic()
        with path.open("xb") as output:
            child = subprocess.Popen([str(v) for v in args], cwd=ROOT, env=self.env,
                                     stdin=subprocess.PIPE if input_bytes is not None else subprocess.DEVNULL,
                                     stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                if input_bytes is not None:
                    child.stdin.write(input_bytes)
                    child.stdin.close()
                while child.poll() is None:
                    if not self.closing:
                        self.resource_guard()
                    if path.stat().st_size > limit:
                        raise RuntimeError(f"Bounded command output exceeded {limit} bytes: {label}")
                    if time.monotonic() - started > timeout:
                        raise TimeoutError(f"Command deadline: {label}")
                    time.sleep(0.25)
            except BaseException:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait(timeout=10)
                raise
        if path.stat().st_size > limit:
            raise RuntimeError(f"Bounded command output exceeded {limit} bytes: {label}")
        text = path.read_text(encoding="utf-8", errors="replace")
        if check and child.returncode:
            raise RuntimeError(f"Command {label} exited {child.returncode}; see {path.name}")
        if quiet and child.returncode == 0:
            path.unlink()  # Task-owned successful polling output has no remaining diagnostic use.
        return text, child.returncode

    def git(self, *args):
        return self.command(["git", *args], label="git")[0].strip()

    def adb(self, *args, **kwargs):
        return self.command([self.sdk / "platform-tools/adb", "-s", self.serial, *args], **kwargs)[0]

    def shell(self, *args, **kwargs):
        return self.adb("shell", *args, **kwargs)

    def source(self):
        expected = os.environ["EXPECTED_HEAD"]
        if not re.fullmatch(r"[a-f0-9]{40}", expected):
            raise ValueError("Invalid immutable source head")
        if self.git("rev-parse", "HEAD") != expected or self.git("status", "--porcelain"):
            raise ValueError("Native producer must build the clean exact selected source head")
        code, name = properties(ROOT / "version.properties")
        self.record.update(sourceCommit=expected, sourceTree=self.git("rev-parse", "HEAD^{tree}"),
                           appTree=self.git("rev-parse", "HEAD:app"), sourceVersionCode=code, sourceVersionName=name,
                           inventory=source_inventory(self.suite))
        self.save()

    def discover(self, name):
        candidates = list((self.sdk / "cmdline-tools").glob(f"*/bin/{name}"))
        candidates.sort(key=lambda value: (value.parent.parent.name == "latest", value.parent.parent.name), reverse=True)
        for path in candidates:
            if path.is_file() and os.access(path, os.X_OK):
                return path
        raise RuntimeError(f"Installed Android command-line tool unavailable: {name}")

    def prepare_sdk(self):
        self.resource_guard(first_boot=True)
        if not Path("/dev/kvm").is_char_device():
            raise RuntimeError("The ephemeral runner has no KVM device")
        if not os.access("/dev/kvm", os.R_OK | os.W_OK):
            self.command(["sudo", "setfacl", "-m", f"u:{os.getuid()}:rw", "/dev/kvm"], label="owned-runner-kvm-access")
        if not os.access("/dev/kvm", os.R_OK | os.W_OK):
            raise RuntimeError("KVM device access unavailable; software-emulation fallback is not permitted")
        sdkmanager = self.discover("sdkmanager")
        self.record["sdkmanager"] = str(sdkmanager)
        self.command([sdkmanager, "--version"], label="sdkmanager-version")
        self.command([sdkmanager, "--licenses"], timeout=120, label="sdk-licenses", input_bytes=b"y\n" * 100)
        self.command([sdkmanager, "--install", "platform-tools", "emulator", "platforms;android-35",
                      f"build-tools;{BUILD_TOOLS_VERSION}", SYSTEM_IMAGE], timeout=900, label="sdk-install")
        self.command([sdkmanager, "--list_installed"], timeout=60, label="sdk-installed")
        self.avdmanager = self.discover("avdmanager")
        self.tools = self.sdk / "build-tools" / BUILD_TOOLS_VERSION
        if not all((self.tools / tool).is_file() and os.access(self.tools / tool, os.X_OK) for tool in ("aapt", "apksigner")):
            raise RuntimeError("The declared build-tools version lacks executable aapt or apksigner")
        tool_properties = (self.tools / "source.properties").read_text(encoding="utf-8")
        revision = re.search(r"^Pkg\.Revision\s*=\s*([^\r\n]+)", tool_properties, re.MULTILINE)
        if not revision or revision[1].strip() != BUILD_TOOLS_VERSION:
            raise RuntimeError("Installed build-tools package revision differs from the declared version")
        signer_version, _ = self.command([self.tools / "apksigner", "--version"], label="apksigner-version")
        aapt_version, aapt_version_exit = self.command([self.tools / "aapt", "version"], check=False, label="aapt-version")
        self.record["buildTools"] = BUILD_TOOLS_VERSION
        self.record["buildToolVersions"] = {"packageRevision": revision[1].strip(), "sourceProperties": tool_properties,
                                             "apksigner": signer_version.strip(), "aapt": aapt_version.strip(),
                                             "aaptVersionExit": aapt_version_exit}
        for relative in ("emulator/source.properties", "system-images/android-35/google_apis/x86_64/source.properties"):
            path = self.sdk / relative
            if not path.is_file():
                raise RuntimeError(f"Missing installed SDK package metadata: {relative}")
            self.record.setdefault("sdkPackages", {})[relative] = path.read_text(encoding="utf-8")
        if not os.access("/dev/kvm", os.R_OK | os.W_OK):
            raise RuntimeError("KVM device access unavailable; software-emulation fallback is not permitted")
        self.emulator_runtime()
        self.command([self.sdk / "emulator/emulator", "-accel-check"], label="kvm-check")
        self.command([self.sdk / "emulator/emulator", "-version"], label="emulator-version")
        self.save()

    def emulator_runtime(self):
        root = self.sdk / "emulator"
        binaries = [root / "emulator", root / "qemu/linux-x86_64/qemu-system-x86_64"]
        if not shutil.which("ldd") or not all(path.is_file() for path in binaries):
            raise RuntimeError("The installed emulator ELF loader inventory is unavailable")
        # The launcher supplies SDK-bundled libraries to QEMU. Include their actual
        # directories in this loader observation, without changing emulator startup.
        directories = {path.parent.resolve() for path in (root / "lib64").rglob("*.so*") if path.is_file()}
        if len(directories) > 128 or any(not path.is_relative_to(root.resolve()) for path in directories):
            raise RuntimeError("Unexpected emulator bundled-library directory inventory")
        self.record["emulatorRuntime"] = {"binaries": [str(path.relative_to(root)) for path in binaries],
                                          "bundledLibraryDirectories": [str(path.relative_to(root)) for path in sorted(directories)]}

        def probe(stage):
            missing = set()
            previous = self.env.get("LD_LIBRARY_PATH")
            self.env["LD_LIBRARY_PATH"] = ":".join([str(path) for path in sorted(directories)] + ([previous] if previous else []))
            try:
                for index, binary in enumerate(binaries):
                    output, code = self.command(["ldd", binary], check=False, label=f"emulator-ldd-{stage}-{index}")
                    absent = missing_loader_libraries(output)
                    if code and not absent:
                        raise RuntimeError("Emulator loader inspection failed; see its retained ldd log")
                    missing.update(absent)
            finally:
                if previous is None:
                    self.env.pop("LD_LIBRARY_PATH", None)
                else:
                    self.env["LD_LIBRARY_PATH"] = previous
            self.record["emulatorRuntime"]["missing" + stage.capitalize()] = sorted(missing)
            self.save()
            return missing

        missing = probe("before")
        packages = runtime_packages(missing)  # Unknown dependencies fail before any apt mutation.
        self.record["emulatorRuntime"]["packagesRequested"] = packages
        self.save()
        if packages:
            self.command(["sudo", "apt-get", "update"], timeout=180, label="emulator-runtime-package-index")
            self.command(["sudo", "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "--yes",
                          "--no-install-recommends", *packages], timeout=300, label="emulator-runtime-package-install")
            versions, _ = self.command(["dpkg-query", "--show", "--showformat=${Package} ${Version}\\n", *packages],
                                       label="emulator-runtime-package-versions")
            self.record["emulatorRuntime"]["installedPackageVersions"] = versions.strip()
            if probe("after"):
                raise RuntimeError("Installed emulator host dependencies remain unresolved; no install retry")
        self.save()

    def build(self):
        self.record["state"] = "paired_build"; self.save()
        self.resource_guard(first_boot=True)
        self.command(["bash", "./gradlew", "--no-daemon", "--no-parallel", "--max-workers=2",
                      "-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=768m",
                      "-PimmediateQualityExperiment=false", "-PreplacementLabCandidate=false",
                      "assembleDebug", "assembleDebugAndroidTest"], timeout=1500, label="paired-build")
        config = ROOT / "app/build/generated/source/buildConfig/debug/com/subhub/app/BuildConfig.java"
        text = config.read_text(encoding="utf-8")
        for field in ("IMMEDIATE_QUALITY_EXPERIMENT", "REPLACEMENT_LAB_CANDIDATE"):
            if len(re.findall(r"boolean\s+" + field + r"\s*=\s*false\s*;", text)) != 1:
                raise ValueError("Native candidate must retain the default experimental profile")
        if self.git("rev-parse", "HEAD") != self.record["sourceCommit"] or self.git("status", "--porcelain"):
            raise ValueError("Tracked or untracked source changed during paired build")
        self.record["build"] = {"pairedInvocation": True, "sourceUnchanged": True,
                                "buildConfigSha256": digest(config), "unitOrFullLintRepeated": False}
        self.retain_apk_pair()
        self.record["apks"] = {}
        for kind, relative in {"target": "app/build/outputs/apk/debug/app-x86_64-debug.apk",
                               "test": "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"}.items():
            path = ROOT / relative
            badging = self.command([self.tools / "aapt", "dump", "badging", path], label=f"{kind}-badging")[0]
            manifest = self.command([self.tools / "aapt", "dump", "xmltree", path, "AndroidManifest.xml"], label=f"{kind}-manifest")[0]
            signature = self.command([self.tools / "apksigner", "verify", "--print-certs", path], label=f"{kind}-signature")[0]
            item = apk_metadata(kind, badging, manifest, signature, self.record["sourceVersionCode"], self.record["sourceVersionName"])
            item.update(path=relative, bytes=path.stat().st_size, sha256=digest(path))
            self.record["apks"][kind] = item
        if len({value["certificateSha256"] for value in self.record["apks"].values()}) != 1:
            raise ValueError("Paired APK signatures differ")
        self.save()

    def retain_apk_pair(self):
        sources = {"target-x86_64-debug.apk": ROOT / "app/build/outputs/apk/debug/app-x86_64-debug.apk",
                   "test-debug-androidTest.apk": ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"}
        if any(not path.is_file() or path.is_symlink() or path.resolve() != path for path in sources.values()):
            raise ValueError("Recovery retains only the two regular task-built APK files")
        total = sum(path.stat().st_size for path in sources.values())
        if total <= 0 or total > 256 * MIB or any(path.stat().st_size <= 0 for path in sources.values()):
            raise ValueError("Retained APK pair exceeds its 256 MiB bound or is empty")
        folder = self.out / "apk-pair"
        folder.mkdir(exist_ok=False)
        recovery = {key: self.record[key] for key in ("sourceCommit", "sourceTree", "appTree", "profile",
                                                      "sourceVersionCode", "sourceVersionName", "runId", "runAttempt")}
        recovery.update(schema=1, buildConfigSha256=self.record["build"]["buildConfigSha256"],
                        metadataVerification="pending; retained bytes are not a native qualification", artifacts={})
        for name, source in sources.items():
            before = digest(source)
            destination = folder / name
            with source.open("rb") as original, destination.open("xb") as retained:
                shutil.copyfileobj(original, retained, MIB)
            if destination.stat().st_size != source.stat().st_size or digest(destination) != before or digest(source) != before:
                raise ValueError("APK bytes changed while retaining the paired build")
            recovery["artifacts"][name] = {"sourcePath": source.relative_to(ROOT).as_posix(),
                                           "bytes": destination.stat().st_size, "sha256": before}
        (folder / "recovery.json").write_text(json.dumps(recovery, indent=2) + "\n", encoding="utf-8")
        self.record["retainedApkPair"] = {"manifest": "apk-pair/recovery.json", "totalBytes": total,
                                          "metadataVerified": False}
        self.save()  # Preserve the complete pair and its source binding before any metadata observer.

    def boot(self):
        self.resource_guard(first_boot=True)
        devices = self.command([self.sdk / "platform-tools/adb", "devices"], label="initial-devices")[0]
        if any(re.fullmatch(r"\S+\s+(?:device|offline|unauthorized)", line) for line in devices.splitlines()):
            raise RuntimeError("Hosted native runner is not isolated from existing Android devices")
        for port in (5554, 5555):
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", port))
        profiles = self.command([self.avdmanager, "list", "device", "-c"], label="device-profiles")[0].splitlines()
        device = next((candidate for candidate in ("pixel_7", "pixel_6", "pixel_2") if candidate in profiles), None)
        if not device:
            raise RuntimeError("No selected phone hardware profile is installed")
        avd_path = self.host / "avd"
        self.command([self.avdmanager, "create", "avd", "--name", self.avd, "--package", SYSTEM_IMAGE,
                      "--path", avd_path, "--device", device], timeout=120, label="create-owned-avd", input_bytes=b"no\n")
        if (avd_path / "userdata-qemu.img").exists():
            raise RuntimeError("The new AVD unexpectedly contains initialized userdata")
        config_path = avd_path / "config.ini"
        self.record["avd"] = {"name": self.avd, "hardwareProfile": device, "configSha256": digest(config_path),
                              "api": API, "abi": "x86_64", "systemImage": SYSTEM_IMAGE}
        self.resource_guard(first_boot=True)
        gpu_help = self.command([self.sdk / "emulator/emulator", "-help-gpu"], label="gpu-modes")[0]
        gpu = "swiftshader" if re.search(r"\bswiftshader\b", gpu_help) else "swiftshader_indirect"
        if gpu not in gpu_help:
            raise RuntimeError("The installed emulator has no selected software graphics backend")
        self.emulator_log = (self.out / "emulator.log").open("xb")
        self.emulator = subprocess.Popen([str(self.sdk / "emulator/emulator"), "-avd", self.avd, "-port", "5554",
                                         "-datadir", str(avd_path), "-no-window", "-no-snapshot", "-no-audio",
                                         "-no-boot-anim", "-gpu", gpu, "-accel", "on", "-memory", "2560", "-cores", "2",
                                         "-camera-back", "none", "-camera-front", "none", "-timezone", "UTC"],
                                        env=self.env, stdout=self.emulator_log, stderr=subprocess.STDOUT,
                                        stdin=subprocess.DEVNULL, start_new_session=True)
        self.record.update(state="booting", emulatorPid=self.emulator.pid, graphicsBackend=gpu); self.save()
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            self.resource_guard()
            if self.emulator.poll() is not None:
                raise RuntimeError("The owned emulator exited during first boot")
            if (self.out / "emulator.log").stat().st_size > MAX_LOG:
                raise RuntimeError("Emulator diagnostic log exceeded its bound")
            try:
                value = self.shell("getprop", "sys.boot_completed", timeout=10, check=False, label="boot-probe", quiet=True)
            except TimeoutError:
                self.record["bootProbeTimeouts"] = self.record.get("bootProbeTimeouts", 0) + 1
                continue
            if value.strip() == "1":
                break
            time.sleep(1)
        else:
            raise TimeoutError("Owned first-boot deadline")
        if self.avd not in self.adb("emu", "avd", "name", label="owned-avd"):
            raise ValueError("ADB selected a different AVD")
        hardware = self.shell("getprop", "ro.hardware", label="hardware").strip()
        if hardware not in ("ranchu", "goldfish") or self.shell("getprop", "ro.build.version.sdk", label="api").strip() != str(API):
            raise ValueError("Actual guest is not the selected API35 emulator")
        if self.shell("getprop", "ro.product.cpu.abi", label="guest-abi").strip() != "x86_64":
            raise ValueError("Actual guest ABI differs")
        self.ready = True
        self.record["bootIdentityVerified"] = True; self.save()

    def install(self):
        for value in self.record["apks"].values():
            package = value["package"]
            if self.shell("pm", "list", "packages", package, label="fresh-package").strip():
                raise ValueError("Selected package exists before first install; no replacement or wipe is permitted")
        for kind, value in self.record["apks"].items():
            if digest(ROOT / value["path"]) != value["sha256"]:
                raise ValueError("APK bytes changed before installation")
            if "Success" not in self.adb("install", str(ROOT / value["path"]), timeout=120, label=f"install-{kind}"):
                raise ValueError("APK installation did not acknowledge success")
        declared = self.shell("pm", "list", "instrumentation", label="declared-runner")
        if f"instrumentation:{RUNNER} (target=com.subhub.app)" not in declared:
            raise ValueError("Installed instrumentation does not declare the required custom runner")
        installed = {}
        for kind, value in self.record["apks"].items():
            raw = self.shell("pm", "path", value["package"], label=f"{kind}-installed-path").strip()
            if not re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=+~-]+/base\.apk", raw):
                raise ValueError("Unexpected installed APK path or split set")
            path = raw[len("package:"):]
            remote = self.shell("sha256sum", path, label=f"{kind}-installed-hash").strip()
            if not re.fullmatch(r"[a-f0-9]{64}\s+" + re.escape(path), remote) or remote.split()[0] != value["sha256"]:
                raise ValueError("Actual installed APK digest differs")
            info = self.shell("dumpsys", "package", value["package"], label=f"{kind}-installed-package")
            codes = re.findall(r"\bversionCode=(\d+)\b", info)
            if not codes or len(set(codes)) != 1:
                raise ValueError("Installed package version is ambiguous")
            code = int(codes[0])
            if value["declaredVersionCode"] is not None and code != value["declaredVersionCode"]:
                raise ValueError("Installed package version differs from its declared version")
            installed[kind] = {"sha256": remote.split()[0], "actualVersionCode": code,
                               "declaredVersionCode": value["declaredVersionCode"],
                               "versionComparison": "matches declaration" if value["declaredVersionCode"] is not None
                               else "manifest declaration absent; actual platform default recorded separately"}
        self.record["installed"] = installed
        self.original = self.external_state()
        if self.original["a11Enabled"] not in ("0", "null") or self.original["a11Services"] not in ("", "null"):
            raise RuntimeError("Fresh owned guest unexpectedly has accessibility enabled")
        if self.original["postNotifications"]["granted"]:
            raise RuntimeError("Fresh target unexpectedly has notification access")
        self.record["originalExternalState"] = self.original; self.save()

    def external_state(self):
        info = self.shell("dumpsys", "package", "com.subhub.app", label="permission-state")
        notification = notification_permission_state(info)
        values = {"a11Services": self.shell("settings", "get", "secure", "enabled_accessibility_services", label="a11-services").strip(),
                  "a11Enabled": self.shell("settings", "get", "secure", "accessibility_enabled", label="a11-enabled").strip(),
                  "fontScale": self.shell("settings", "get", "system", "font_scale", label="font-scale").strip(),
                  "postNotifications": notification}
        for key in ("a11Services", "a11Enabled", "fontScale"):
            if not re.fullmatch(r"[A-Za-z0-9_./:+-]*", values[key]):
                raise ValueError("External state token cannot be restored safely")
        return values

    def settings(self, namespace, key, value):
        if value == "null":
            self.shell("settings", "delete", namespace, key, label="restore-setting")
        elif value == "":
            # ADB shell interprets this fixed empty-string token; preserve an existing empty row.
            self.shell("settings", "put", namespace, key, "''", label="restore-empty-setting")
        else:
            self.shell("settings", "put", namespace, key, value, label="set-setting")

    def method(self, selector, index):
        started = time.monotonic()
        self.record["methods"][selector] = {"state": "running", "index": index}
        self.record["remaining"].remove(selector)
        self.save()
        try:
            text, code = self.command([self.sdk / "platform-tools/adb", "-s", self.serial, "shell", "am", "instrument", "-w", "-r",
                                       "-e", "class", selector, "-e", "extra_time_ui_fixture", "true", "-e", "evidence_run", self.run,
                                       RUNNER], timeout=180, check=False, label=f"method-{index:02d}")
            actual = instrumentation_result(text, selector, code)
            self.record["methods"][selector].update(state="passed", exitCode=code, actualInstrumentation=actual,
                                                    seconds=round(time.monotonic() - started, 2))
        except BaseException as failure:
            self.record["methods"][selector].update(state="failed", failure=type(failure).__name__ + ": " + str(failure),
                                                    seconds=round(time.monotonic() - started, 2))
            raise
        finally:
            self.save()
            print(json.dumps({"method": selector, "state": self.record["methods"][selector]["state"]}), flush=True)

    def run_tests(self):
        self.record["state"] = "selected_native_running"; self.save()
        self.settings("system", "font_scale", "1.0")
        for index, selector in enumerate(self.selected, 1):
            if selector == LARGE_FONT:
                self.settings("system", "font_scale", "1.5")
            self.method(selector, index)
        self.record["state"] = "selected_native_passed"; self.save()

    def binary(self, args, path, limit):
        error = path.with_suffix(path.suffix + ".stderr.log")
        with path.open("xb") as output, error.open("xb") as errors:
            child = subprocess.Popen([str(v) for v in args], cwd=ROOT, env=self.env,
                                     stdout=output, stderr=errors, stdin=subprocess.DEVNULL, start_new_session=True)
            deadline = time.monotonic() + 45
            try:
                while child.poll() is None:
                    if time.monotonic() > deadline or path.stat().st_size > limit or error.stat().st_size > MIB:
                        raise RuntimeError("Synthetic binary export deadline or size bound")
                    time.sleep(0.1)
            except BaseException:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL); child.wait(timeout=10)
                raise
        if child.returncode or path.stat().st_size > limit:
            raise RuntimeError(f"Synthetic binary export failed: {path.name}")

    def export(self):
        expected = [name for selector in self.selected for name in UI_WITNESSES.get(selector, [])]
        self.record["expectedUiWitnesses"] = expected
        screenshots = [name for selector in self.selected for name in UI_SCREENSHOTS.get(selector, [])]
        self.record["expectedUiScreenshots"] = screenshots
        self.adb("logcat", "-d", "-t", "4000", timeout=30, check=False, label="guest-logcat")
        if self.record["state"] != "selected_native_passed":
            self.binary([self.sdk / "platform-tools/adb", "-s", self.serial, "exec-out", "screencap", "-p"],
                        self.out / "failure-screen.png", MAX_MEMBER)
            if not (self.out / "failure-screen.png").read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                raise ValueError("Failure screenshot is not actual PNG bytes")
        # Directory absence is expected when a pre-UI contract failed or a subset excludes UI.
        _, status = self.command([self.sdk / "platform-tools/adb", "-s", self.serial, "shell", "run-as",
                                       "com.subhub.app", "test", "-d", f"files/ui-v3/{self.run}"],
                                      check=False, label="ui-evidence-admission")
        if status:
            self.record["uiEvidence"] = "No run-specific UI directory was produced"
            if expected or screenshots:
                raise ValueError("Selected UI methods produced no required screenshot/composition directory")
            return
        archive = self.out / "synthetic-ui.tar"
        self.binary([self.sdk / "platform-tools/adb", "-s", self.serial, "exec-out", "run-as", "com.subhub.app",
                     "tar", "-cf", "-", "-C", "files/ui-v3", self.run], archive, MAX_ARCHIVE)
        extracted = self.out / "synthetic-ui"
        extracted.mkdir(exist_ok=False)
        exported = []
        with tarfile.open(archive) as data:
            members = data.getmembers()
            if len(members) > 128:
                raise ValueError("Too many synthetic UI evidence entries")
            total = 0
            for member in members:
                name = PurePosixPath(member.name)
                if "\\" in member.name or name.is_absolute() or ".." in name.parts or not name.parts or name.parts[0] != self.run \
                        or any(not re.fullmatch(r"[A-Za-z0-9_.-]+", part) for part in name.parts) \
                        or not (member.isdir() or member.isfile()):
                    raise ValueError("Untrusted or non-regular synthetic evidence archive entry")
                if member.isdir():
                    continue
                total += member.size
                if member.size > MAX_MEMBER or total > MAX_ARCHIVE or name.suffix not in (".png", ".json"):
                    raise ValueError("Synthetic UI evidence type or size exceeded its allowlist")
                path = extracted.joinpath(*name.parts)
                if not path.resolve().is_relative_to(extracted.resolve()):
                    raise ValueError("Synthetic evidence escaped its owned root")
                path.parent.mkdir(parents=True, exist_ok=True)
                with data.extractfile(member) as source, path.open("xb") as target:
                    shutil.copyfileobj(source, target, MIB)
                if path.stat().st_size != member.size:
                    raise ValueError("Synthetic evidence extraction was incomplete")
                if name.suffix == ".json":
                    json.loads(path.read_text(encoding="utf-8"))
                elif not path.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                    raise ValueError("Synthetic screenshot is not actual PNG bytes")
                exported.append({"path": path.relative_to(self.out).as_posix(), "bytes": member.size, "sha256": digest(path)})
        self.record["uiEvidence"] = exported
        self.record["uiArchiveSha256"] = digest(archive)
        missing_png = missing_screenshots(screenshots, exported)
        self.record["missingUiScreenshots"] = missing_png
        if missing_png:
            raise ValueError("Missing required native screenshots: " + ", ".join(missing_png))
        missing = []
        for name in expected:
            screenshot = extracted / self.run / (name + ".png")
            witness = extracted / self.run / (name + ".composition.json")
            if not screenshot.is_file() or not witness.is_file():
                missing.append(name)
            elif json.loads(witness.read_text(encoding="utf-8")).get("composed") is not True:
                missing.append(name + " (not composed)")
        self.record["missingUiWitnesses"] = missing
        if missing:
            raise ValueError("Missing required composed native screenshots: " + ", ".join(missing))

    def restore(self):
        if self.original is None:
            return
        self.settings("system", "font_scale", self.original["fontScale"])
        self.settings("secure", "enabled_accessibility_services", self.original["a11Services"])
        self.settings("secure", "accessibility_enabled", self.original["a11Enabled"])
        current = self.external_state()
        previous = self.original["postNotifications"]
        if current["postNotifications"] != previous:
            permission = "android.permission.POST_NOTIFICATIONS"
            self.shell("pm", "grant" if previous["granted"] else "revoke", "com.subhub.app", permission, label="restore-notification-grant")
            self.shell("pm", "clear-permission-flags", "com.subhub.app", permission, "user-set", "user-fixed", label="restore-notification-flags")
            for stored, flag in (("USER_SET", "user-set"), ("USER_FIXED", "user-fixed")):
                if stored in previous["flags"]:
                    self.shell("pm", "set-permission-flags", "com.subhub.app", permission, flag, label="restore-notification-flag")
        self.record["finalExternalState"] = self.external_state()
        if self.record["finalExternalState"] != self.original:
            raise RuntimeError("Exact font/accessibility/notification state was not restored")

    def finish(self):
        self.closing = True
        failures = []
        if self.emulator is not None:
            if self.ready:
                for name, action in (("evidenceExport", self.export), ("externalStateRestore", self.restore)):
                    try:
                        action()
                    except BaseException as failure:
                        failures.append({"stage": name, "failure": type(failure).__name__ + ": " + str(failure)})
            try:
                self.record["shutdownAcknowledgement"] = self.adb("emu", "kill", timeout=15, check=False, label="owned-emulator-stop").strip()
                self.emulator.wait(timeout=30)
                self.record["emulatorStopped"] = True
            except BaseException as failure:
                failures.append({"stage": "ownedEmulatorShutdown", "failure": type(failure).__name__ + ": " + str(failure)})
                # Exact owned process group only; retain the graceful refusal and all diagnostics.
                if self.emulator.poll() is None:
                    os.killpg(self.emulator.pid, signal.SIGTERM)
                    try:
                        self.emulator.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        os.killpg(self.emulator.pid, signal.SIGKILL); self.emulator.wait(timeout=10)
                self.record["emulatorStoppedAfterFallback"] = self.emulator.poll() is not None
            if self.emulator_log is not None:
                self.emulator_log.close()
        self.record["cleanupFailures"] = failures
        self.record["finalFreeBytes"] = shutil.disk_usage(ROOT).free
        self.record["passed"] = [name for name, result in self.record["methods"].items() if result["state"] == "passed"]
        self.record["notPassed"] = [name for name in self.selected if name not in self.record["passed"]]
        self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-inventory", action="store_true", help="Parse the fixed tracked selectors without a build or emulator")
    parser.add_argument("--suite", choices=tuple(SUITES), default=os.environ.get("EXTRA_TIME_SUITE", "extra-time"),
                        help="Fixed native suite (default: extra-time, or EXTRA_TIME_SUITE)")
    args = parser.parse_args()
    if args.check_inventory:
        selected, selection_source = selected_methods(args.suite)
        print(json.dumps({"suite": args.suite, "selectors": selected, "selectionSource": selection_source,
                          "suiteSelectors": suite_selectors(args.suite), "inventory": source_inventory(args.suite)}, indent=2))
        return
    producer = Producer(args.suite)
    try:
        producer.source()
        producer.prepare_sdk()
        producer.build()
        producer.boot()
        producer.install()
        producer.run_tests()
    except BaseException as failure:
        producer.record.update(state="failed_preserved", failure=type(failure).__name__ + ": " + str(failure))
        (producer.out / "failure.txt").write_text(traceback.format_exc(), encoding="utf-8")
    finally:
        producer.finish()
    if producer.record["state"] != "selected_native_passed" or producer.record["cleanupFailures"]:
        raise SystemExit(1)
    print(json.dumps({"state": producer.record["state"], "passedMethods": len(producer.record["passed"]),
                      "sourceCommit": producer.record["sourceCommit"], "receipt": str(producer.out / "receipt.json")}))


if __name__ == "__main__":
    main()
