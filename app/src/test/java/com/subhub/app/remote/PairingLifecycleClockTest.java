package com.subhub.app.remote;

import static org.junit.Assert.*;

import java.security.GeneralSecurityException;
import java.util.Collections;
import org.junit.Test;

/** Pure clock and proof contracts; durable first-approval-once behavior belongs to ledger tests. */
public final class PairingLifecycleClockTest {
    private static final long WALL = 1_000_000, ELAPSED = 100_000;
    private static final int BOOT = 7;
    private static final FormalRelationshipClock.Sample START = sample(0, 0);
    private static final String DOM = repeat("a", 64), SUB = repeat("b", 64);
    private static final String OTHER_PEER = repeat("c", 64);
    private static final String GENERATION = "pairing_1-A";
    private static final String CLIENT_NONCE = repeat("1", 64), SERVER_NONCE = repeat("2", 64);
    private static final String SECRET = repeat("A", 32), OTHER_SECRET = repeat("B", 32);

    @Test public void originalPublicTenMinutesSurviveObservationAndApprovalUnchanged() {
        PairingLifecycleClock.Bounds bounds = invitation();
        assertSame(START, bounds.created);
        assertEquals(WALL + 600_000, bounds.publicWall);
        assertEquals(ELAPSED + 600_000, bounds.publicElapsed);
        assertEquals(WALL + 1_200_000, bounds.outerWall);
        assertEquals(ELAPSED + 1_200_000, bounds.outerElapsed);
        assertEquals(600_000, PairingLifecycleClock.remaining(bounds, null, START));
        assertEquals(1, PairingLifecycleClock.remaining(bounds, null, sample(599_999, 599_999)));
        PairingLifecycleClock.firstApproval(bounds, PairingLifecycleClock.Direction.CREATE,
                sample(599_999, 599_999));
        assertEquals(PairingLifecycleClock.Verdict.EXPIRED,
                PairingLifecycleClock.publicVerdict(bounds, sample(600_000, 600_000)));
        assertEquals(0, PairingLifecycleClock.remaining(bounds, null, sample(600_001, 600_001)));
        assertSame(START, bounds.created);
        assertEquals(WALL + 600_000, bounds.publicWall);
        assertEquals(ELAPSED + 600_000, bounds.publicElapsed);
        assertEquals(WALL + 1_200_000, bounds.outerWall);
        assertEquals(ELAPSED + 1_200_000, bounds.outerElapsed);
    }

    @Test public void publicRemainingUsesWhicheverClockHasLessTime() {
        PairingLifecycleClock.Bounds bounds = invitation();
        for (FormalRelationshipClock.Sample now : new FormalRelationshipClock.Sample[] {
                sample(100_000, 102_000), sample(102_000, 100_000) }) {
            assertEquals(PairingLifecycleClock.Verdict.LIVE,
                    PairingLifecycleClock.publicVerdict(bounds, now));
            assertEquals(498_000, PairingLifecycleClock.remaining(bounds, null, now));
        }
    }

    @Test public void equalityOnEitherPublicClockExpiresInvitation() {
        PairingLifecycleClock.Bounds bounds = invitation();
        for (FormalRelationshipClock.Sample now : new FormalRelationshipClock.Sample[] {
                sample(600_000, 599_999), sample(599_999, 600_000), sample(600_000, 600_000) }) {
            assertEquals(PairingLifecycleClock.Verdict.EXPIRED,
                    PairingLifecycleClock.publicVerdict(bounds, now));
            assertEquals(0, PairingLifecycleClock.remaining(bounds, null, now));
        }
    }

    @Test public void uncertainPublicClocksCannotReleaseTimeOrAdmitApproval() {
        PairingLifecycleClock.Bounds bounds = invitation();
        for (FormalRelationshipClock.Sample now : uncertainSamples()) {
            assertEquals(PairingLifecycleClock.Verdict.UNCERTAIN,
                    PairingLifecycleClock.publicVerdict(bounds, now));
            assertEquals(0, PairingLifecycleClock.remaining(bounds, null, now));
            for (PairingLifecycleClock.Direction direction : PairingLifecycleClock.Direction.values()) {
                assertThrows(SecurityException.class,
                        () -> PairingLifecycleClock.firstApproval(bounds, direction, now));
            }
        }
    }

    @Test public void creatorApprovalMustPrecedeBothOriginalPublicDeadlines() {
        PairingLifecycleClock.Bounds bounds = invitation();
        PairingLifecycleClock.Approval last = PairingLifecycleClock.firstApproval(bounds,
                PairingLifecycleClock.Direction.CREATE, sample(599_999, 599_999));
        assertEquals(WALL + 1_199_999, last.deadlineWall);
        assertEquals(ELAPSED + 1_199_999, last.deadlineElapsed);
        for (FormalRelationshipClock.Sample first : new FormalRelationshipClock.Sample[] {
                sample(600_000, 599_999), sample(599_999, 600_000), sample(700_000, 700_000) }) {
            assertThrows(SecurityException.class, () -> PairingLifecycleClock.firstApproval(
                    bounds, PairingLifecycleClock.Direction.CREATE, first));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PairingLifecycleClock.firstApproval(bounds, null, START));
    }

    @Test public void firstApprovalGetsTenMinutesWithoutChangingPublicExpiry() {
        PairingLifecycleClock.Bounds bounds = invitation();
        FormalRelationshipClock.Sample first = sample(120_000, 120_000);
        for (PairingLifecycleClock.Direction direction : PairingLifecycleClock.Direction.values()) {
            PairingLifecycleClock.Approval approval = PairingLifecycleClock.firstApproval(bounds, direction, first);
            assertSame(first, approval.first);
            assertEquals(WALL + 720_000, approval.deadlineWall);
            assertEquals(ELAPSED + 720_000, approval.deadlineElapsed);
            assertEquals(600_000, PairingLifecycleClock.remaining(bounds, approval, first));
            assertEquals(PairingLifecycleClock.Verdict.EXPIRED,
                    PairingLifecycleClock.publicVerdict(bounds, sample(600_000, 600_000)));
            assertEquals(PairingLifecycleClock.Verdict.LIVE,
                    PairingLifecycleClock.completionVerdict(bounds, approval, sample(600_000, 600_000)));
            assertEquals(120_000, PairingLifecycleClock.remaining(bounds, approval, sample(600_000, 600_000)));
        }
    }

    @Test public void acceptMayObserveAlreadyApprovedResponseAfterPublicButOnlyWithinOriginalOuterCap() {
        PairingLifecycleClock.Bounds bounds = invitation();
        PairingLifecycleClock.Approval atPublicExpiry = PairingLifecycleClock.firstApproval(bounds,
                PairingLifecycleClock.Direction.ACCEPT, sample(600_000, 600_000));
        assertEquals(600_000, PairingLifecycleClock.remaining(bounds, atPublicExpiry, atPublicExpiry.first));
        PairingLifecycleClock.Approval late = PairingLifecycleClock.firstApproval(bounds,
                PairingLifecycleClock.Direction.ACCEPT, sample(700_000, 700_000));
        assertEquals(WALL + 1_200_000, late.deadlineWall);
        assertEquals(ELAPSED + 1_200_000, late.deadlineElapsed);
        assertEquals(500_000, PairingLifecycleClock.remaining(bounds, late, late.first));
        PairingLifecycleClock.Approval last = PairingLifecycleClock.firstApproval(bounds,
                PairingLifecycleClock.Direction.ACCEPT, sample(1_199_999, 1_199_999));
        assertEquals(1, PairingLifecycleClock.remaining(bounds, last, last.first));
        for (FormalRelationshipClock.Sample first : new FormalRelationshipClock.Sample[] {
                sample(1_200_000, 1_199_999), sample(1_199_999, 1_200_000),
                sample(1_200_001, 1_200_001) }) {
            assertThrows(SecurityException.class, () -> PairingLifecycleClock.firstApproval(
                    bounds, PairingLifecycleClock.Direction.ACCEPT, first));
        }
    }

    @Test public void lateAcceptCompletionDurationUsesTheEarlierOriginalOuterClock() {
        PairingLifecycleClock.Bounds bounds = invitation();
        for (long[] offsets : new long[][] {
                { 700_000, 698_000, 1_200_000, 1_198_000 },
                { 698_000, 700_000, 1_198_000, 1_200_000 } }) {
            PairingLifecycleClock.Approval approval = PairingLifecycleClock.firstApproval(bounds,
                    PairingLifecycleClock.Direction.ACCEPT, sample(offsets[0], offsets[1]));
            assertEquals(WALL + offsets[2], approval.deadlineWall);
            assertEquals(ELAPSED + offsets[3], approval.deadlineElapsed);
            assertEquals(500_000, PairingLifecycleClock.remaining(bounds, approval, approval.first));
        }
    }

    @Test public void shortenedOriginalInvitationAlsoShortensItsOuterCap() {
        PairingLifecycleClock.Bounds bounds = PairingLifecycleClock.begin(START, WALL + 60_000);
        assertEquals(ELAPSED + 60_000, bounds.publicElapsed);
        PairingLifecycleClock.Approval approval = PairingLifecycleClock.firstApproval(bounds,
                PairingLifecycleClock.Direction.ACCEPT, sample(100_000, 100_000));
        assertEquals(WALL + 660_000, approval.deadlineWall);
        assertEquals(ELAPSED + 660_000, approval.deadlineElapsed);
        assertEquals(560_000, PairingLifecycleClock.remaining(bounds, approval, approval.first));
    }

    @Test public void completionRemainingAndExpiryRespectBothClocks() {
        PairingLifecycleClock.Bounds bounds = invitation();
        PairingLifecycleClock.Approval approval = PairingLifecycleClock.firstApproval(bounds,
                PairingLifecycleClock.Direction.CREATE, sample(120_000, 120_000));
        for (FormalRelationshipClock.Sample now : new FormalRelationshipClock.Sample[] {
                sample(200_000, 202_000), sample(202_000, 200_000) }) {
            assertEquals(518_000, PairingLifecycleClock.remaining(bounds, approval, now));
        }
        assertEquals(1, PairingLifecycleClock.remaining(bounds, approval, sample(719_999, 719_999)));
        for (FormalRelationshipClock.Sample now : new FormalRelationshipClock.Sample[] {
                sample(720_000, 719_999), sample(719_999, 720_000), sample(720_001, 720_001) }) {
            assertEquals(PairingLifecycleClock.Verdict.EXPIRED,
                    PairingLifecycleClock.completionVerdict(bounds, approval, now));
            assertEquals(0, PairingLifecycleClock.remaining(bounds, approval, now));
        }
    }

    @Test public void completionRequiresConsistencyWithCreationAndFirstApproval() {
        PairingLifecycleClock.Bounds bounds = invitation();
        PairingLifecycleClock.Approval approval = PairingLifecycleClock.firstApproval(bounds,
                PairingLifecycleClock.Direction.CREATE, sample(10_000, 10_000));
        for (FormalRelationshipClock.Sample now : uncertainSamples()) {
            assertEquals(PairingLifecycleClock.Verdict.UNCERTAIN,
                    PairingLifecycleClock.completionVerdict(bounds, approval, now));
            assertEquals(0, PairingLifecycleClock.remaining(bounds, approval, now));
        }
        // Each observation is consistent with creation, but the relative drift is 4000 ms.
        PairingLifecycleClock.Approval skewed = PairingLifecycleClock.firstApproval(bounds,
                PairingLifecycleClock.Direction.CREATE, sample(12_000, 10_000));
        FormalRelationshipClock.Sample reversedSkew = sample(20_000, 22_000);
        assertEquals(PairingLifecycleClock.Verdict.LIVE,
                PairingLifecycleClock.publicVerdict(bounds, reversedSkew));
        assertEquals(PairingLifecycleClock.Verdict.UNCERTAIN,
                PairingLifecycleClock.completionVerdict(bounds, skewed, reversedSkew));
        assertEquals(0, PairingLifecycleClock.remaining(bounds, skewed, reversedSkew));
        assertEquals(PairingLifecycleClock.Verdict.UNCERTAIN,
                PairingLifecycleClock.completionVerdict(bounds, approval, sample(9_999, 9_999)));
    }

    @Test public void invalidOriginalAndApprovalBoundsFailClosed() {
        for (long lifetime : new long[] { -1, 0, 600_001 }) {
            assertThrows(IllegalArgumentException.class,
                    () -> PairingLifecycleClock.begin(START, WALL + lifetime));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new PairingLifecycleClock.Bounds(START, WALL + 100, ELAPSED + 101));
        PairingLifecycleClock.Bounds bounds = invitation();
        FormalRelationshipClock.Sample first = sample(100_000, 100_000);
        for (long[] duration : new long[][] { { 0, 0 }, { -1, -1 }, { 600_001, 600_001 }, { 100, 101 } }) {
            assertThrows(IllegalArgumentException.class, () -> new PairingLifecycleClock.Approval(
                    bounds, first, first.wall + duration[0], first.elapsed + duration[1]));
        }
        for (FormalRelationshipClock.Sample invalid : uncertainSamples()) {
            assertThrows(IllegalArgumentException.class, () -> new PairingLifecycleClock.Approval(
                    bounds, invalid, invalid.wall + 100, invalid.elapsed + 100));
        }
        // Equal-duration deadlines independently exceed the wall or elapsed outer bound.
        for (FormalRelationshipClock.Sample late : new FormalRelationshipClock.Sample[] {
                sample(700_000, 699_999), sample(699_999, 700_000) }) {
            assertThrows(IllegalArgumentException.class, () -> new PairingLifecycleClock.Approval(
                    bounds, late, late.wall + 500_001, late.elapsed + 500_001));
        }
    }

    @Test public void unavailableAnchorsAreRejectedBeforeCreatingBoundsOrOffers() {
        PairingLifecycleClock.Bounds bounds = invitation();
        for (FormalRelationshipClock.Sample invalid : new FormalRelationshipClock.Sample[] {
                new FormalRelationshipClock.Sample(0, ELAPSED, BOOT),
                new FormalRelationshipClock.Sample(WALL, -1, BOOT),
                new FormalRelationshipClock.Sample(WALL, ELAPSED, -1) }) {
            assertThrows(IllegalStateException.class, () -> PairingLifecycleClock.begin(invalid, WALL + 100));
            assertThrows(IllegalStateException.class, () -> PairingLifecycleClock.firstApproval(
                    bounds, PairingLifecycleClock.Direction.ACCEPT, invalid));
            assertThrows(IllegalStateException.class,
                    () -> new PairingCompletionProof.Offer(DOM, SUB, GENERATION, CLIENT_NONCE, invalid));
            assertFalse(PairingLifecycleClock.freshProof(invalid, START));
        }
    }

    @Test public void overflowingBoundsThrowInsteadOfWrappingIntoAUsableLifetime() {
        assertThrows(ArithmeticException.class, () -> PairingLifecycleClock.begin(START, Long.MIN_VALUE));
        assertThrows(ArithmeticException.class, () -> PairingLifecycleClock.begin(
                new FormalRelationshipClock.Sample(WALL, Long.MAX_VALUE - 10, BOOT), WALL + 20));
        assertThrows(ArithmeticException.class, () -> PairingLifecycleClock.begin(
                new FormalRelationshipClock.Sample(Long.MAX_VALUE - 100, 0, BOOT), Long.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> PairingLifecycleClock.begin(
                new FormalRelationshipClock.Sample(WALL, Long.MAX_VALUE - 100, BOOT), WALL + 1));
        PairingLifecycleClock.Bounds bounds = invitation();
        assertThrows(ArithmeticException.class,
                () -> new PairingLifecycleClock.Approval(bounds, START, Long.MIN_VALUE, ELAPSED + 1));
    }

    @Test public void representableDeadlinesNearLongMaximumRetainExactEqualityExpiry() {
        long anchor = Long.MAX_VALUE - 1_200_000;
        FormalRelationshipClock.Sample start = new FormalRelationshipClock.Sample(anchor, anchor, BOOT);
        PairingLifecycleClock.Bounds bounds = PairingLifecycleClock.begin(start, Long.MAX_VALUE - 600_000);
        FormalRelationshipClock.Sample first = new FormalRelationshipClock.Sample(
                Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, BOOT);
        PairingLifecycleClock.Approval approval = PairingLifecycleClock.firstApproval(
                bounds, PairingLifecycleClock.Direction.ACCEPT, first);
        assertEquals(Long.MAX_VALUE, approval.deadlineWall);
        assertEquals(Long.MAX_VALUE, approval.deadlineElapsed);
        assertEquals(1, PairingLifecycleClock.remaining(bounds, approval, first));
        assertEquals(PairingLifecycleClock.Verdict.EXPIRED, PairingLifecycleClock.completionVerdict(
                bounds, approval, new FormalRelationshipClock.Sample(Long.MAX_VALUE, Long.MAX_VALUE, BOOT)));
    }

    @Test public void proofIsFreshForThirtySecondsOnlyWhenBothClocksAgree() {
        assertTrue(PairingLifecycleClock.freshProof(START, START));
        assertTrue(PairingLifecycleClock.freshProof(START, sample(29_999, 29_999)));
        for (FormalRelationshipClock.Sample now : new FormalRelationshipClock.Sample[] {
                sample(30_000, 29_999), sample(29_999, 30_000), sample(30_001, 30_001) }) {
            assertFalse(PairingLifecycleClock.freshProof(START, now));
        }
        for (FormalRelationshipClock.Sample now : uncertainSamples()) {
            assertFalse(PairingLifecycleClock.freshProof(START, now));
        }
    }

    @Test public void matchingPhasesVerifyAndOnlyReadyProducesTheBoundVerifiedResult() throws Exception {
        PairingCompletionProof.Exchange exchange = exchange(DOM, SUB, GENERATION, CLIENT_NONCE, SERVER_NONCE);
        FormalRelationshipClock.Sample now = sample(1_000, 1_000);
        exchange.verifyActive(SECRET, exchange.active(SECRET), now);
        exchange.verifyPending(SECRET, exchange.pending(SECRET), now);
        PairingCompletionProof.Verified verified = exchange.verifyReady(SECRET, exchange.ready(SECRET), now);
        assertEquals(DOM, verified.dom);
        assertEquals(SUB, verified.sub);
        assertEquals(GENERATION, verified.generation);
        assertEquals(PairingCompletionProof.credentialDigest(SECRET), verified.credentialDigest);
        assertTrue(verified.validFor(SUB, DOM, GENERATION, verified.credentialDigest, now));
    }

    @Test public void activePendingAndReadyProofsCannotBeSubstitutedForEachOther() throws Exception {
        PairingCompletionProof.Exchange exchange = exchange(DOM, SUB, GENERATION, CLIENT_NONCE, SERVER_NONCE);
        String[] proofs = proofs(exchange, SECRET);
        assertNotEquals(proofs[0], proofs[1]);
        assertNotEquals(proofs[0], proofs[2]);
        assertNotEquals(proofs[1], proofs[2]);
        for (int phase = 0; phase < proofs.length; phase++) {
            String proof = proofs[phase];
            if (phase != 0) assertThrows(GeneralSecurityException.class,
                    () -> exchange.verifyActive(SECRET, proof, START));
            if (phase != 1) assertThrows(GeneralSecurityException.class,
                    () -> exchange.verifyPending(SECRET, proof, START));
            if (phase != 2) assertThrows(GeneralSecurityException.class,
                    () -> exchange.verifyReady(SECRET, proof, START));
        }
    }

    @Test public void eachTranscriptFieldAndTheExistingCredentialAreAuthenticated() throws Exception {
        PairingCompletionProof.Exchange original = exchange(DOM, SUB, GENERATION, CLIENT_NONCE, SERVER_NONCE);
        String[] originalProofs = proofs(original, SECRET);
        for (PairingCompletionProof.Exchange changed : new PairingCompletionProof.Exchange[] {
                exchange(OTHER_PEER, SUB, GENERATION, CLIENT_NONCE, SERVER_NONCE),
                exchange(DOM, OTHER_PEER, GENERATION, CLIENT_NONCE, SERVER_NONCE),
                exchange(SUB, DOM, GENERATION, CLIENT_NONCE, SERVER_NONCE),
                exchange(DOM, SUB, "pairing_2-A", CLIENT_NONCE, SERVER_NONCE),
                exchange(DOM, SUB, GENERATION, repeat("3", 64), SERVER_NONCE),
                exchange(DOM, SUB, GENERATION, CLIENT_NONCE, repeat("4", 64)) }) {
            assertProofsRejected(changed, SECRET, originalProofs, START);
        }
        assertProofsRejected(original, OTHER_SECRET, originalProofs, START);
        assertNotEquals(PairingCompletionProof.credentialDigest(SECRET),
                PairingCompletionProof.credentialDigest(OTHER_SECRET));
    }

    @Test public void everyPhaseRejectsExpiredAndUncertainProofClocks() throws Exception {
        PairingCompletionProof.Exchange exchange = exchange(DOM, SUB, GENERATION, CLIENT_NONCE, SERVER_NONCE);
        String[] proofs = proofs(exchange, SECRET);
        for (FormalRelationshipClock.Sample now : new FormalRelationshipClock.Sample[] {
                sample(30_000, 29_999), sample(29_999, 30_000), sample(30_001, 30_001) }) {
            assertProofsRejected(exchange, SECRET, proofs, now);
        }
        for (FormalRelationshipClock.Sample now : uncertainSamples()) {
            assertProofsRejected(exchange, SECRET, proofs, now);
        }
    }

    @Test public void verifiedRequiresExactLocalSubRemoteDomGenerationAndCredentialDigest() throws Exception {
        PairingCompletionProof.Exchange exchange = exchange(DOM, SUB, GENERATION, CLIENT_NONCE, SERVER_NONCE);
        FormalRelationshipClock.Sample now = sample(1_000, 1_000);
        PairingCompletionProof.Verified verified = exchange.verifyReady(SECRET, exchange.ready(SECRET), now);
        String digest = PairingCompletionProof.credentialDigest(SECRET);
        assertTrue(verified.validFor(SUB, DOM, GENERATION, digest, now));
        assertFalse(verified.validFor(OTHER_PEER, DOM, GENERATION, digest, now));
        assertFalse(verified.validFor(SUB, OTHER_PEER, GENERATION, digest, now));
        assertFalse(verified.validFor(DOM, SUB, GENERATION, digest, now));
        assertFalse(verified.validFor(SUB, DOM, "pairing_2-A", digest, now));
        assertFalse(verified.validFor(SUB, DOM, GENERATION,
                PairingCompletionProof.credentialDigest(OTHER_SECRET), now));
        assertFalse(verified.validFor(SUB.toUpperCase(java.util.Locale.ROOT), DOM, GENERATION, digest, now));
        assertFalse(verified.validFor(SUB, DOM, GENERATION, SECRET, now));
        assertFalse(verified.validFor(null, DOM, GENERATION, digest, now));
        assertFalse(verified.validFor(SUB, null, GENERATION, digest, now));
        assertFalse(verified.validFor(SUB, DOM, null, digest, now));
        assertFalse(verified.validFor(SUB, DOM, GENERATION, null, now));
    }

    @Test public void verifiedFreshnessRemainsAnchoredToOfferRatherThanVerificationTime() throws Exception {
        PairingCompletionProof.Exchange exchange = exchange(DOM, SUB, GENERATION, CLIENT_NONCE, SERVER_NONCE);
        FormalRelationshipClock.Sample lastFresh = sample(29_999, 29_999);
        PairingCompletionProof.Verified verified = exchange.verifyReady(SECRET, exchange.ready(SECRET), lastFresh);
        assertTrue(verified.validFor(SUB, DOM, GENERATION, verified.credentialDigest, lastFresh));
        for (FormalRelationshipClock.Sample now : new FormalRelationshipClock.Sample[] {
                sample(30_000, 29_999), sample(29_999, 30_000), sample(30_000, 30_000) }) {
            assertFalse(verified.validFor(SUB, DOM, GENERATION, verified.credentialDigest, now));
        }
        for (FormalRelationshipClock.Sample now : uncertainSamples()) {
            assertFalse(verified.validFor(SUB, DOM, GENERATION, verified.credentialDigest, now));
        }
    }

    @Test public void noncesMustBeExactlySixtyFourLowercaseHexCharacters() {
        assertEquals(CLIENT_NONCE, PairingCompletionProof.nonce(CLIENT_NONCE));
        String allHexDigits = repeat("0123456789abcdef", 4);
        assertEquals(allHexDigits, PairingCompletionProof.nonce(allHexDigits));
        for (String invalid : new String[] { null, "", repeat("a", 63), repeat("a", 65),
                repeat("A", 64), repeat("g", 64), " " + CLIENT_NONCE, CLIENT_NONCE + "\n" }) {
            assertThrows(IllegalArgumentException.class, () -> PairingCompletionProof.nonce(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> new PairingCompletionProof.Offer(DOM, SUB, GENERATION, invalid, START));
            assertThrows(IllegalArgumentException.class,
                    () -> new PairingCompletionProof.Offer(DOM, SUB, GENERATION, CLIENT_NONCE, START).challenge(invalid));
        }
    }

    @Test public void offerRejectsInvalidPeersEqualPeersAndUnsafeGenerations() {
        for (String invalid : new String[] { null, "", repeat("a", 63), repeat("A", 64),
                repeat("g", 64), " " + DOM }) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PairingCompletionProof.Offer(invalid, SUB, GENERATION, CLIENT_NONCE, START));
            assertThrows(IllegalArgumentException.class,
                    () -> new PairingCompletionProof.Offer(DOM, invalid, GENERATION, CLIENT_NONCE, START));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new PairingCompletionProof.Offer(DOM, DOM, GENERATION, CLIENT_NONCE, START));
        for (String invalid : new String[] { null, "", "unsafe generation", "../generation",
                "generation\n", repeat("g", 129) }) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PairingCompletionProof.Offer(DOM, SUB, invalid, CLIENT_NONCE, START));
        }
    }

    @Test public void malformedProofEncodingIsRejectedInEveryPhase() throws Exception {
        PairingCompletionProof.Exchange exchange = exchange(DOM, SUB, GENERATION, CLIENT_NONCE, SERVER_NONCE);
        for (String invalid : new String[] { null, "", repeat("a", 63), repeat("a", 65),
                repeat("A", 64), repeat("g", 64), " " + repeat("a", 64), repeat("a", 64) + "\n" }) {
            assertProofsRejected(exchange, SECRET, new String[] { invalid, invalid, invalid }, START);
        }
    }

    @Test public void allProofEntrypointsRequireAnExistingStrictBase32Credential() throws Exception {
        PairingCompletionProof.Exchange exchange = exchange(DOM, SUB, GENERATION, CLIENT_NONCE, SERVER_NONCE);
        String[] validProofs = proofs(exchange, SECRET);
        for (String invalid : new String[] { null, "", repeat("A", 31), repeat("A", 33),
                repeat("a", 32), repeat("0", 32), repeat("1", 32), repeat("8", 32),
                repeat("A", 31) + "=", " " + SECRET, SECRET + "\n" }) {
            assertThrows(GeneralSecurityException.class, () -> PairingCompletionProof.credentialDigest(invalid));
            assertThrows(GeneralSecurityException.class, () -> exchange.active(invalid));
            assertThrows(GeneralSecurityException.class, () -> exchange.pending(invalid));
            assertThrows(GeneralSecurityException.class, () -> exchange.ready(invalid));
            assertProofsRejected(exchange, invalid, validProofs, START);
        }
    }

    private static PairingLifecycleClock.Bounds invitation() {
        return PairingLifecycleClock.begin(START, WALL + 600_000);
    }

    private static FormalRelationshipClock.Sample sample(long wallOffset, long elapsedOffset) {
        return new FormalRelationshipClock.Sample(WALL + wallOffset, ELAPSED + elapsedOffset, BOOT);
    }

    private static FormalRelationshipClock.Sample[] uncertainSamples() {
        return new FormalRelationshipClock.Sample[] {
                new FormalRelationshipClock.Sample(WALL + 1_000, ELAPSED + 1_000, BOOT + 1),
                sample(-1, 0), sample(0, -1), sample(12_001, 10_000), sample(10_000, 12_001) };
    }

    private static PairingCompletionProof.Exchange exchange(String dom, String sub, String generation,
            String clientNonce, String serverNonce) {
        return new PairingCompletionProof.Offer(dom, sub, generation, clientNonce, START).challenge(serverNonce);
    }

    private static String[] proofs(PairingCompletionProof.Exchange exchange, String secret)
            throws GeneralSecurityException {
        return new String[] { exchange.active(secret), exchange.pending(secret), exchange.ready(secret) };
    }

    private static void assertProofsRejected(PairingCompletionProof.Exchange exchange, String secret,
            String[] proofs, FormalRelationshipClock.Sample now) {
        assertThrows(GeneralSecurityException.class, () -> exchange.verifyActive(secret, proofs[0], now));
        assertThrows(GeneralSecurityException.class, () -> exchange.verifyPending(secret, proofs[1], now));
        assertThrows(GeneralSecurityException.class, () -> exchange.verifyReady(secret, proofs[2], now));
    }

    private static String repeat(String value, int count) {
        return String.join("", Collections.nCopies(count, value));
    }
}
