package com.subhub.app.internal;

import org.junit.Test;

public final class InternalStorageTest {
    @Test public void targetNeverAliasesLocalOrPeer() { InternalStorageChecks.targetNeverAliasesLocalOrPeer(); }
    @Test public void binaryCodecPreservesAllPreferenceTypes() { InternalStorageChecks.binaryCodecPreservesAllPreferenceTypes(); }
    @Test public void binaryCodecRejectsCorruptionTruncationAndExtraBytes() { InternalStorageChecks.binaryCodecRejectsCorruptionTruncationAndExtraBytes(); }
    @Test public void invalidFieldsAndUnicodeFailBeforeAcknowledgement() { InternalStorageChecks.invalidFieldsAndUnicodeFailBeforeAcknowledgement(); }
    @Test public void visibilityRotatesGenerationAndRetainsData() { InternalStorageChecks.visibilityRotatesGenerationAndRetainsData(); }
    @Test public void configurationSavesAdvanceWithoutWaitingForNextSession() { InternalStorageChecks.configurationSavesAdvanceWithoutWaitingForNextSession(); }
    @Test public void staleGenerationAndFullRecordWritesConflict() { InternalStorageChecks.staleGenerationAndFullRecordWritesConflict(); }
    @Test public void uncertainCommitNeverPublishesEvenIfBackendMemoryChanged() { InternalStorageChecks.uncertainCommitNeverPublishesEvenIfBackendMemoryChanged(); }
    @Test public void definiteFailurePublishesNothingAndPreservesReadableState() { InternalStorageChecks.definiteFailurePublishesNothingAndPreservesReadableState(); }
    @Test public void listenersRunAfterTransactionsAndDoNotReclassifySavedData() { InternalStorageChecks.listenersRunAfterTransactionsAndDoNotReclassifySavedData(); }
    @Test public void exhaustedAndInvalidTransitionsFailClosed() { InternalStorageChecks.exhaustedAndInvalidTransitionsFailClosed(); }
    @Test public void semanticValidatorPreventsVisibilityOverCorruptConfiguration() { InternalStorageChecks.semanticValidatorPreventsVisibilityOverCorruptConfiguration(); }
    @Test public void noOpWritesDoNotInventAcknowledgedRevisions() { InternalStorageChecks.noOpWritesDoNotInventAcknowledgedRevisions(); }
    @Test public void totalEncodingSizeIsBoundedDuringWrites() { InternalStorageChecks.totalEncodingSizeIsBoundedDuringWrites(); }
    @Test public void diskRoundTripRetainsOwnedNamespacesAndNeverTouchesSibling() throws Exception { InternalStorageChecks.diskRoundTripRetainsOwnedNamespacesAndNeverTouchesSibling(); }
    @Test public void missingEstablishedRecordAndLockNeverReseed() throws Exception { InternalStorageChecks.missingEstablishedRecordAndLockNeverReseed(); }
    @Test public void corruptEstablishedRecordNeverReseeds() throws Exception { InternalStorageChecks.corruptEstablishedRecordNeverReseeds(); }
    @Test public void pathsAndSymlinksCannotAliasSiblingData() throws Exception { InternalStorageChecks.pathsAndSymlinksCannotAliasSiblingData(); }
    @Test public void concurrentBackendsCompareTheEntireExpectedRecord() throws Exception { InternalStorageChecks.concurrentBackendsCompareTheEntireExpectedRecord(); }
    @Test public void trustedPhysicalParentAliasIsCanonicalizedOnlyAtBoundary() throws Exception { InternalStorageChecks.trustedPhysicalParentAliasIsCanonicalizedOnlyAtBoundary(); }
    @Test public void ownedRootSymlinkIsNeverCanonicalizedIntoSiblingData() throws Exception { InternalStorageChecks.ownedRootSymlinkIsNeverCanonicalizedIntoSiblingData(); }
    @Test public void lazyOwnedDirectoryCreationIsConcurrentAndRejectsNonDirectories() throws Exception { InternalStorageChecks.lazyOwnedDirectoryCreationIsConcurrentAndRejectsNonDirectories(); }
}
