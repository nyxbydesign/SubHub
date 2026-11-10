package com.subhub.app.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ViewportMotionTest {
    @Test public void staleMeasuredFadeRemainsDistinctFromOrdinaryEventPrediction() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0, 0, 1000);
        assertFalse(motion.isMeasuredPresentationMode());
        assertTrue(motion.measurePresentation(20, -100, 1010, 1012, 1012, 1344, 2992, 16));
        assertTrue(motion.hasMeasuredPresentation(1060));
        for (long now : new long[]{1061, 1068, 1075, 1076}) {
            assertTrue(motion.isMeasuredPresentationMode());
            assertFalse(motion.hasMeasuredPresentation(now));
            if (now < 1076) assertTrue("stale pose still needs document-only fallback",
                    Math.abs(motion.position(now).y) > 0);
        }
        motion.addDelta(0, -120, 1080, 1344, 2992, true, 1079);
        assertFalse(motion.isMeasuredPresentationMode());
        assertFalse(motion.hasMeasuredPresentation(1096));
        assertTrue("ordinary event prediction remains available", motion.position(1096).y < -120);
        assertTrue(motion.measurePresentation(0, -140, 1100, 1102, 1102, 1344, 2992, 16));
        motion.clearMeasuredPresentation(1104);
        assertFalse(motion.isMeasuredPresentationMode());
        motion.reset(0, 0, 1200);
        assertFalse(motion.isMeasuredPresentationMode());
    }

    @Test public void hostTraceStaysContinuousAndCaughtUpWithoutUnboundedPrediction() {
        for (boolean horizontal : new boolean[]{false, true}) {
            for (int direction : new int[]{-1, 1}) {
                for (int frameStep : new int[]{8, 16}) {
                    ViewportMotion motion = new ViewportMotion();
                    motion.reset(0, 0, 0);
                    motion.addDelta(horizontal ? direction * 338 : 0,
                            horizontal ? 0 : direction * 338,
                            3_761_279L, 2_992, 2_992, true, 3_761_278L);
                    float before = axis(motion.position(3_761_396L), horizontal);
                    motion.addDelta(horizontal ? direction * 324 : 0,
                            horizontal ? 0 : direction * 324,
                            3_761_396L, 2_992, 2_992, true, 3_761_387L);
                    float initial = axis(motion.position(3_761_396L), horizontal);
                    assertEquals("no measurement teleport", before, initial, .001f);
                    float amplitude = axis(motion.predictionAmplitude(), horizontal);
                    assertTrue(Math.abs(amplitude) <= 2992f * .18f);
                    float previous = initial;
                    for (int elapsed = frameStep; elapsed <= 32; elapsed += frameStep) {
                        float displayed = axis(motion.position(3_761_396L + elapsed), horizontal);
                        assertTrue("no backward correction", direction * (displayed - previous) >= -.001f);
                        assertTrue("bounded prediction", direction * displayed <= 662f + Math.abs(amplitude) + .001f);
                        if (elapsed >= 16) {
                            assertTrue("known displacement caught up by " + elapsed,
                                    direction * displayed >= 662f - .001f);
                        }
                        previous = displayed;
                    }
                    assertEquals(direction * 662f, axis(motion.position(3_762_000L), horizontal), .001f);
                }
            }
        }
    }

    private static float axis(ViewportMotion.Position position, boolean horizontal) {
        return horizontal ? position.x : position.y;
    }

    private static ViewportMotion pendingMeasurementCorrection() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0, 0, 0);
        motion.addDelta(0, -200, 1000, 1344, 2992, true);
        motion.addDelta(0, -462, 1117, 1344, 2992, true);
        assertTrue(motion.position(1121).y > -662f);
        return motion;
    }

    @Test public void resetAndRebaseCannotRetainPendingMeasuredCorrection() {
        ViewportMotion reset = pendingMeasurementCorrection();
        reset.reset(12, 25, 1121);
        ViewportMotion rebased = pendingMeasurementCorrection();
        rebased.rebase(12, 25, 1121);
        for (long now : new long[]{1121, 1129, 1137, 1200}) {
            assertEquals(25f, reset.position(now).y, .001f);
            assertEquals(25f, rebased.position(now).y, .001f);
        }
    }

    @Test public void pollAndAbsoluteMeasurementReplacePendingCorrection() {
        ViewportMotion polled = pendingMeasurementCorrection();
        polled.addPresentationDelta(0, -20, 1121, 1344, 2992);
        assertEquals(-682f, polled.position(1121).y, .001f);
        polled.settlePresentation(1125);
        assertEquals(-682f, polled.position(1141).y, .001f);

        ViewportMotion measured = pendingMeasurementCorrection();
        assertTrue(measured.measurePresentation(0, -690, 1121, 1123, 1123, 1344, 2992, 16));
        assertEquals(-690f, measured.position(1123).y, .001f);
        measured.clearMeasuredPresentation(1125);
        assertEquals(-662f, measured.position(1141).y, .001f);
        assertEquals(-662f, measured.position(1200).y, .001f);
    }

    @Test public void fallbackAndReversalCorrectButContinuingSlowdownHoldsOvershoot() {
        ViewportMotion fallback = pendingMeasurementCorrection();
        float displayed = fallback.position(1121).y;
        fallback.addDelta(0, -20, 1121, 1344, 2992, false);
        assertEquals(displayed, fallback.position(1121).y, .001f);
        assertEquals(-682f, fallback.position(1137).y, .001f);
        for (int delta : new int[]{20, -20}) {
            ViewportMotion motion = pendingMeasurementCorrection();
            float before = motion.position(1234).y;
            motion.addDelta(0, delta, 1234, 1344, 2992, true);
            assertEquals(before, motion.position(1234).y, .001f);
            if (delta > 0) {
                assertEquals(0f, motion.predictionAmplitude().y, .001f);
                assertEquals(-662f + delta, motion.position(1266).y, .001f);
            } else {
                assertEquals(before, motion.position(1266).y, .001f);
                assertTrue(Math.abs(motion.predictionAmplitude().y) <= 2992 * .18f);
            }
            assertEquals(-662f + delta, motion.position(1500).y, .001f);
            assertFalse(motion.isAnimating(1500));
        }
    }

    @Test public void unchangedAlignedMeasurementDoesNotForceIdleAnimation() {
        ViewportMotion motion = new ViewportMotion(); motion.reset(0, 0, 1000);
        assertTrue(motion.measurePresentation(0, 0, 1010, 1012, 1012, 1000, 2000, 16));
        assertFalse(motion.isAnimating(1012));
    }
    @Test public void delayedCoveredEventDoesNotDoubleApplyAbsoluteMeasurement() {
        ViewportMotion motion = new ViewportMotion(); motion.reset(0, 0, 1000);
        assertTrue(motion.measurePresentation(0, -30, 1010, 1012, 1012, 1000, 2000, 16));
        motion.addDelta(0, -20, 1020, 1000, 2000, true, 1005);
        assertEquals(-30, motion.position(1020).y, .001f);
        assertEquals(-20, motion.position(1076).y, .001f);
    }

    @Test public void newerOrOverlappingEventSupersedesMeasuredPosition() {
        for (long source : new long[]{1011, 1015}) {
            ViewportMotion motion = new ViewportMotion(); motion.reset(0, 0, 1000);
            assertTrue(motion.measurePresentation(0, -30, 1010, 1013, 1013, 1000, 2000, 16));
            motion.addDelta(0, -40, 1020, 1000, 2000, true, source);
            assertEquals(-40, motion.position(1020).y, .001f);
        }
    }

    @Test public void delayedPreEventReadCannotSnapPresentationBackward() {
        ViewportMotion motion = new ViewportMotion(); motion.reset(0, 0, 1000);
        motion.addDelta(0, -40, 1020, 1000, 2000, true, 1015);
        assertFalse(motion.measurePresentation(0, -30, 1012, 1018, 1021, 1000, 2000, 16));
    }

    @Test public void measuredPresentationExpiresToAuthorityWithoutFurtherInput() {
        ViewportMotion motion = new ViewportMotion(); motion.reset(0, 0, 1000);
        motion.addDelta(0, -20, 1005, 1000, 2000, true, 1005);
        assertTrue(motion.measurePresentation(0, -30, 1010, 1012, 1012, 1000, 2000, 16));
        assertTrue(motion.isAnimating(1065));
        assertEquals(-20, motion.position(1076).y, .001f);
        assertFalse(motion.isAnimating(1076));
    }

    @Test public void staleDuplicateAndPreRebaseSamplesAreRejected() {
        ViewportMotion motion = new ViewportMotion(); motion.reset(0, 0, 1000);
        assertFalse(motion.measurePresentation(0, -30, 1001, 1005, 1040, 1000, 2000, 16));
        assertTrue(motion.measurePresentation(0, -30, 1041, 1045, 1045, 1000, 2000, 16));
        assertFalse(motion.measurePresentation(0, -30, 1041, 1045, 1046, 1000, 2000, 16));
        motion.rebase(0, 0, 1050);
        assertFalse(motion.measurePresentation(0, -30, 1049, 1052, 1053, 1000, 2000, 16));
    }

    @Test public void oldPollingCanResumeAfterAbsoluteModeExpires() {
        ViewportMotion motion = new ViewportMotion(); motion.reset(0, 0, 1000);
        assertTrue(motion.measurePresentation(0, -30, 1010, 1012, 1012, 1000, 2000, 16));
        motion.addPresentationDelta(0, -10, 1100, 1000, 2000);
        assertEquals(-10, motion.position(1100).y, .001f);
    }

    @Test public void absolutePredictionUsesOneBoundedFrameAndStopsOnZeroMovement() {
        for (int frame : new int[]{8, 16, 24}) {
            ViewportMotion motion = new ViewportMotion(); motion.reset(0, 0, 1000);
            assertTrue(motion.measurePresentation(0, -30, 1010, 1012, 1012, 1000, 2000, frame));
            assertTrue(motion.measurePresentation(0, -130, 1030, 1032, 1032, 1000, 2000, frame));
            assertTrue(Math.abs(motion.position(1056).y + 130) <= 32.01);
            assertTrue(motion.measurePresentation(0, -130, 1058, 1060, 1060, 1000, 2000, frame));
            assertEquals(-130, motion.position(1070).y, .001f);
        }
    }

    @Test
    public void steadySlowScrollCorrectsPhaseWithoutIncreasingJumpBudget() {
        for (int interval : new int[]{60, 100, 120}) {
            for (int frameStep : new int[]{8, 16}) {
                for (int direction : new int[]{-1, 1}) {
                    ViewportMotion motion = new ViewportMotion();
                    motion.reset(0f, 0f, 1_000L);
                    double errorSum = 0;
                    int samples = 0;
                    for (int elapsed = 1; elapsed <= 1_200; elapsed++) {
                        long now = 1_000L + elapsed;
                        if (elapsed % interval == 0) {
                            float before = motion.position(now).y;
                            motion.addDelta(0, direction * .5f * interval,
                                    now, 1_344, 2_992, true);
                            if (elapsed > interval) {
                                assertTrue("same-direction jump budget",
                                        Math.abs(motion.position(now).y - before) <= 56.001f);
                            }
                        }
                        if (elapsed > interval * 2 && elapsed % frameStep == 0) {
                            errorSum += Math.abs(motion.position(now).y
                                    - direction * .5f * elapsed);
                            samples++;
                        }
                    }
                    assertTrue("mean phase error at " + interval + "ms / " + frameStep + "ms",
                            errorSum / samples < 22.0);
                    assertEquals(direction * .5f * (1_200 / interval) * interval,
                            motion.position(2_700L).y, .001f);
                    assertFalse(motion.isAnimating(2_700L));
                }
            }
        }
    }

    @Test
    public void firstAuthoritativeSampleCoversMeasuredPositionImmediately() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);

        motion.addDelta(0f, -500f, 16L, 1_344, 2_992, true);

        assertEquals(-500f, motion.position(16L).y, 0.001f);
        assertTrue(motion.position(32L).y < -500f);
    }

    @Test
    public void steadySparseStreamRemainsContinuousAndPromptlyReconcilesMeasurements() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        long now = 16L;
        motion.addDelta(0f, -400f, now, 1_344, 2_992, true);

        float largestDisplayStep = 0f;
        float previous = motion.position(now).y;
        for (int event = 1; event <= 7; event++) {
            long nextEvent = now + 114L;
            for (long frame = now + 8L; frame < nextEvent; frame += 8L) {
                float current = motion.position(frame).y;
                assertTrue("Trajectory reversed at " + frame, current <= previous + 0.001f);
                largestDisplayStep = Math.max(largestDisplayStep, Math.abs(current - previous));
                previous = current;
            }
            float beforeEvent = motion.position(nextEvent).y;
            motion.addDelta(0f, -400f, nextEvent, 1_344, 2_992, true);
            float afterEvent = motion.position(nextEvent).y;
            float exact = -400f * (event + 1);
            assertTrue(Math.abs(motion.position(nextEvent + 16L).y - exact)
                    <= 2_992f * 0.08f + 0.001f);
            assertEquals(beforeEvent, afterEvent, .001f);
            largestDisplayStep = Math.max(largestDisplayStep,
                    Math.abs(afterEvent - previous));
            previous = afterEvent;
            now = nextEvent;
        }

        assertTrue("Display step was " + largestDisplayStep,
                largestDisplayStep <= 2_992f * 0.08f + 0.001f);
    }

    @Test
    public void deceleratingStreamDoesNotReverseWhileEventsContinue() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        long[] times = {16L, 130L, 244L, 358L, 472L};
        float[] deltas = {-420f, -350f, -260f, -160f, -80f};
        float previous = 0f;
        for (int index = 0; index < times.length; index++) {
            float before = motion.position(times[index]).y;
            motion.addDelta(0f, deltas[index], times[index], 1_344, 2_992, true);
            float after = motion.position(times[index]).y;
            float expected = 0f;
            for (int deltaIndex = 0; deltaIndex <= index; deltaIndex++) {
                expected += deltas[deltaIndex];
            }
            assertTrue(Math.abs(motion.position(times[index] + 16L).y - expected)
                    <= 2_992f * 0.08f + 0.001f);
            assertTrue(after <= previous + 0.001f);
            previous = after;
        }
    }

    @Test
    public void reversalCorrectionIsBoundedAndTurnsTowardNewDirection() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -300f, 16L, 1_344, 2_992, true);
        motion.addDelta(0f, -300f, 130L, 1_344, 2_992, true);
        float beforeReverse = motion.position(244L).y;

        motion.addDelta(0f, 240f, 244L, 1_344, 2_992, true);
        float atReverse = motion.position(244L).y;
        float afterReverse = motion.position(284L).y;

        assertEquals(beforeReverse, atReverse, .001f);
        assertEquals(-360f, motion.position(276L).y, .001f);
        assertTrue(afterReverse > atReverse);
    }

    @Test
    public void predictionSettlesBackToLastMeasurementWithoutDrift() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -300f, 16L, 1_344, 2_992, true);
        motion.addDelta(0f, -300f, 130L, 1_344, 2_992, true);

        assertTrue(motion.isAnimating(200L));
        assertEquals(-600f, motion.position(500L).y, 0.001f);
        assertFalse(motion.isAnimating(500L));
    }

    @Test
    public void sharplyDeceleratingTailHoldsWithoutAddingLeadThenExpiresToAuthority() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -240f, 16L, 1_344, 2_992, true);
        float before = motion.position(130L).y;
        motion.addDelta(0f, -30f, 130L, 1_344, 2_992, true);

        assertEquals(before, motion.position(130L).y, .001f);
        for (long now = 138; now <= 258; now += 8) {
            assertEquals("hold, not extra forward lead or reverse correction", before,
                    motion.position(now).y, .001f);
        }
        assertEquals(-30f, motion.predictionAmplitude().y, .001f);
        assertEquals(-270f, motion.position(400L).y, .001f);
        assertFalse(motion.isAnimating(400L));
    }

    @Test
    public void reversalHasNoForwardOvershootAndSettlesWithinTwoFrames() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -240f, 16L, 1_344, 2_992, true);
        motion.addDelta(0f, 30f, 130L, 1_344, 2_992, true);

        assertEquals(0f, motion.predictionAmplitude().y, 0.001f);
        float previous = motion.position(130L).y;
        for (long frame = 138L; frame <= 162L; frame += 8L) {
            float current = motion.position(frame).y;
            assertTrue(current >= previous - 0.001f);
            assertTrue(current <= -210f + 0.001f);
            previous = current;
        }
        assertEquals(-210f, motion.position(162L).y, 0.001f);
        assertFalse(motion.isAnimating(163L));
    }

    @Test
    public void zeroDistanceBrakeDoesNotAdvertiseLongAnimation() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -120f, 16L, 1_344, 2_992, true);
        motion.addDelta(0f, 0f, 130L, 1_344, 2_992, true);

        assertFalse(motion.isAnimating(147L));
    }

    @Test
    public void flingPredictionIsViewportBounded() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -5_000f, 16L, 1_344, 2_992, true);

        assertTrue(Math.abs(motion.predictionAmplitude().y) <= 2_992f * 0.18f + 0.001f);
    }

    @Test
    public void nonAuthoritativeFallbackUsesOnlyOneDisplayFrame() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -500f, 16L, 1_344, 2_992, false);

        assertEquals(0f, motion.position(16L).y, 0.001f);
        float partial = motion.position(24L).y;
        assertTrue(partial < 0f && partial > -500f);
        assertEquals(-500f, motion.position(32L).y, 0.001f);
    }

    @Test
    public void detectorCoordinateRebasePreservesLivePollPhaseAcrossEvent() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -300f, 16L, 1_344, 2_992, true);
        motion.addPresentationDelta(0f, -40f, 80L, 1_344, 2_992);
        motion.rebase(0f, 0f, 88L);
        motion.addPresentationDelta(0f, -30f, 96L, 1_344, 2_992);
        float beforeEvent = motion.position(112L).y;

        motion.addDelta(0f, -260f, 112L, 1_344, 2_992, true);

        assertEquals(beforeEvent, motion.position(112L).y, 0.001f);
        assertTrue(Math.abs(motion.predictionAmplitude().y)
                <= 2_992f * 0.45f + 0.001f);
    }

    @Test
    public void presentationSamplesApplyMeasuredPhaseAndContinueBetweenPolls() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);

        motion.addPresentationDelta(0f, -180f, 40L, 1_344, 2_992);
        assertEquals(-180f, motion.position(40L).y, 0.001f);
        assertTrue(motion.position(48L).y < -180f);
        motion.addPresentationDelta(0f, -180f, 80L, 1_344, 2_992);
        motion.addPresentationDelta(0f, -180f, 120L, 1_344, 2_992);

        assertTrue(Math.abs(motion.predictionAmplitude().y)
                <= 2_992f * 0.45f + 0.001f);
    }

    @Test
    public void phaseLockedEventDoesNotApplyPolledIntervalTwice() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -240f, 16L, 1_344, 2_992, true);
        motion.addPresentationDelta(0f, -60f, 48L, 1_344, 2_992);
        motion.addPresentationDelta(0f, -60f, 64L, 1_344, 2_992);
        float beforeEvent = motion.position(72L).y;

        motion.addDelta(0f, -120f, 72L, 1_344, 2_992, true);

        assertEquals(beforeEvent, motion.position(72L).y, 0.001f);
    }

    @Test
    public void measuredZeroBrakesPredictionImmediatelyAndCanBeReconciled() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -200f, 16L, 1_344, 2_992, true);
        motion.addPresentationDelta(0f, -80f, 48L, 1_344, 2_992);
        motion.addPresentationDelta(0f, -80f, 64L, 1_344, 2_992);
        assertTrue(motion.position(72L).y < -360f);

        motion.settlePresentation(80L);

        assertEquals(-360f, motion.position(96L).y, 0.001f);
        motion.addDelta(0f, -160f, 104L, 1_344, 2_992, true);
        assertEquals(-360f, motion.position(120L).y, 0.001f);
        assertEquals(-360f, motion.position(160L).y, 0.001f);
    }

    @Test
    public void sparseFlingCannotLeaveRenderedViewportFarBehindMeasurement() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        motion.addDelta(0f, -900f, 16L, 1_344, 2_992, true);
        motion.addDelta(0f, -2_600f, 130L, 1_344, 2_992, true);

        float phaseError = Math.abs(motion.position(130L).y - -3_500f);
        assertTrue(phaseError <= 2_992f * 0.08f + 0.001f);
    }

    @Test
    public void delayedCallbackReplaysTrajectoryFromEventSourceTime() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);

        // Called at t=130, but Android says the scroll observation became effective at t=100.
        motion.addDelta(0f, -240f, 100L, 1_344, 2_992, true);

        assertEquals(-240f, motion.position(100L).y, 0.001f);
        assertTrue("The next presentation must include elapsed motion, not restart at callback",
                motion.position(130L).y < -240f);
    }
}
