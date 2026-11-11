package com.subhub.app.service;

import android.os.SystemClock;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.After;
import static org.junit.Assert.*;

/** Exercise the service-to-coordinator wiring, without connecting or starting capture workers. */
public final class SceneMotionIntegrationAndroidTest {
    private final java.util.List<AccessibilityLegacyPipeline> fixtures = new java.util.ArrayList<>();
    private AccessibilityLegacyPipeline freshPipeline() throws Exception {
        AccessibilityLegacyPipeline pipeline = new AccessibilityLegacyPipeline(new ScreenshotAccessibilityService());
        Field active = AccessibilityLegacyPipeline.class.getDeclaredField("recognitionActive");
        active.setAccessible(true); active.setBoolean(pipeline, true); fixtures.add(pipeline);
        return pipeline;
    }
    @After public void restoreRecognitionScope() throws Exception {
        for (AccessibilityLegacyPipeline pipeline : fixtures) {
            AccessibilityPipelineFixture.set(pipeline, "recognitionActive", false);
            AccessibilityPipelineFixture.set(pipeline, "running", false);
        }
        fixtures.clear();
    }

    @Test public void continuousServiceScenesSurviveMotionButNotNavigationOrSupersession() throws Exception {
        for (boolean settled : new boolean[] {false, true}) {
            AccessibilityLegacyPipeline service = freshPipeline();
            long now = SystemClock.uptimeMillis();
            Object scene = begin(service, 1, now, settled, true);
            SceneTransactionCoordinator<Detection> coordinator = coordinator(service);
            SceneTransactionCoordinator.SceneKey key = key(scene);
            for (int reversal = 0; reversal < 8; reversal++) motion(service);
            assertFalse(cancelled(scene));
            SceneTransactionCoordinator.Commit<Detection> commit = complete(service, scene).commit();
            assertNotNull(commit);
            motion(service);
            assertTrue(coordinator.isPresentationCurrent(commit));
            Object replacement = begin(service, 2, now + 2, settled, true);
            // Staging/capture does not supersede available presentation or an executing scene.
            assertFalse(cancelled(scene));
            assertTrue(coordinator.isPresentationCurrent(commit));
            assertNotNull(complete(service, replacement).commit());
            assertTrue(cancelled(scene));
            assertFalse(coordinator.isPresentationCurrent(commit));
            motion(service);
            assertFalse(cancelled(replacement));
            Method invalidate = AccessibilityLegacyPipeline.class.getDeclaredMethod("invalidateCurrentScene", String.class);
            invalidate.setAccessible(true);
            invalidate.invoke(service, "test-document-change");
            assertTrue(cancelled(replacement));
            assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                    coordinator.fastReady(key(replacement), Collections.emptyList(), now + 3).status());
        }
    }

    @Test public void nonContinuousServiceSceneStillClosesForMotion() throws Exception {
        AccessibilityLegacyPipeline service = freshPipeline();
        long now = SystemClock.uptimeMillis();
        Object scene = begin(service, 1, now, false, false);
        assertNotNull(complete(service, scene).commit());
        motion(service);
        assertTrue(cancelled(scene));
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                coordinator(service).fastReady(key(scene), Collections.emptyList(), now + 1).status());
    }

    @Test public void completedResultStillRejectsStructuralAndMotionScopeChanges() throws Exception {
        for (String scope : new String[] {"epoch", "document", "window", "motion", "stopped"}) {
            AccessibilityLegacyPipeline service = freshPipeline();
            Object scene = begin(service, 1, SystemClock.uptimeMillis(), false, false);
            if (scope.equals("epoch")) ((CaptureEpoch) field(service, "captureEpoch")).invalidate();
            if (scope.equals("document")) ((AtomicLong) field(service, "visualDocumentEpoch")).incrementAndGet();
            if (scope.equals("motion")) ((AtomicLong) field(service, "motionGeneration")).incrementAndGet();
            if (scope.equals("window")) ((java.util.concurrent.atomic.AtomicInteger)
                    field(service, "activeApplicationWindowId")).set(2);
            if (scope.equals("stopped")) {
                Field running = AccessibilityLegacyPipeline.class.getDeclaredField("running");
                running.setAccessible(true);
                running.setBoolean(service, false);
            }
            assertNull(scope, complete(service, scene));
            assertTrue(scope, cancelled(scene));
            assertNull(scope, coordinator(service).currentKey());
        }
    }

    @Test public void obsoleteLookupRecoveryCannotResetNewerDocument() throws Exception {
        AccessibilityLegacyPipeline service = freshPipeline();
        long now = SystemClock.uptimeMillis();
        begin(service, 1, now, false, true);
        Field packageField = AccessibilityLegacyPipeline.class.getDeclaredField("foregroundPackage");
        packageField.setAccessible(true);
        packageField.set(service, "fixture");
        Class<?> pendingType = Class.forName(AccessibilityLegacyPipeline.class.getName() + "$PendingScrollEvent");
        Constructor<?> constructor = pendingType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        try {
            Object pending = constructor.newInstance(event, 1L, now, 1L, 1L, 1, "fixture", 0L, false);
            // Synthetic scope explicitly matches everything except the now-obsolete document.
            Field scope = pendingType.getDeclaredField("scope");
            scope.setAccessible(true);
            scope.set(pending, new ScrollLookupScope(1, 0, 1, 1, 1, "fixture", false));
            ((AtomicLong) field(service, "visualDocumentEpoch")).set(2);
            Method recovery = AccessibilityLegacyPipeline.class.getDeclaredMethod(
                    "recoverScrollLookupGap", pendingType, String.class);
            recovery.setAccessible(true);
            recovery.invoke(service, pending, "queue-age");
            assertEquals(2, ((AtomicLong) field(service, "visualDocumentEpoch")).get());
            assertEquals(1, ((AtomicLong) field(service, "motionGeneration")).get());
            assertEquals(0, ((AtomicLong) field(service, "cumulativeScrollY")).get());
        } finally { event.recycle(); }
    }

    @Test public void completedPresentationIsCoalescedBeforeBlockedMainAndReleasedOnce() throws Exception {
        AccessibilityLegacyPipeline service = freshPipeline();
        java.util.ArrayDeque<Runnable> ticks = new java.util.ArrayDeque<>();
        java.util.ArrayList<Integer> presented = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger released = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> mainFailure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch unblock = new java.util.concurrent.CountDownLatch(1);
        LatestFrameBroker<Object> broker = new LatestFrameBroker<>(ticks::addLast,
                packet -> invokePresentationPacket(packet, "present"),
                packet -> invokePresentationPacket(packet, "dispose"), 1);
        Field presenter = AccessibilityLegacyPipeline.class.getDeclaredField("scenePresenter");
        presenter.setAccessible(true);
        presenter.set(service, broker);
        Method submit = AccessibilityLegacyPipeline.class.getDeclaredMethod(
                "submitScenePresentation", String.class, Runnable.class, Runnable.class);
        submit.setAccessible(true);
        assertTrue(new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            blocked.countDown();
            try {
                if (!unblock.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    mainFailure.set(new AssertionError("Synthetic main block was not released"));
                }
            } catch (InterruptedException error) { mainFailure.set(error); }
        }));
        try {
            assertTrue(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            for (int id = 1; id <= 24; id++) {
                final int sceneId = id;
                submit.invoke(service, "synthetic-" + id, (Runnable) () -> {
                    assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
                    presented.add(sceneId);
                    released.incrementAndGet(); // Consumer adopts and releases this synthetic frame.
                }, (Runnable) released::incrementAndGet);
            }
            assertEquals("Only one callback can wait behind main", 1, ticks.size());
            assertEquals("Replaced frames close before main resumes", 23, released.get());
            assertTrue(presented.isEmpty());
            unblock.countDown();
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(() -> ticks.removeFirst().run());
            assertNull(mainFailure.get());
            assertEquals(List.of(24), presented);
            assertEquals(24, released.get());
            submit.invoke(service, "synthetic-close", (Runnable) () -> fail("Closed presenter must not publish"),
                    (Runnable) released::incrementAndGet);
            broker.close();
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(() -> ticks.removeFirst().run());
            assertEquals(25, released.get());
        } finally {
            unblock.countDown();
            broker.close();
        }
    }

    private static void invokePresentationPacket(Object packet, String action) {
        try {
            Method method = packet.getClass().getDeclaredMethod(action);
            method.setAccessible(true);
            method.invoke(packet);
        } catch (Exception error) { throw new AssertionError("Synthetic presentation callback failed", error); }
    }

    @Test public void oldGenerationCanQueryButCannotInsertOrMoveEventCacheEvidence() throws Exception {
        AccessibilityLegacyPipeline service = freshPipeline();
        Field surface = AccessibilityLegacyPipeline.class.getDeclaredField("activeScrollSurfaceKey");
        surface.setAccessible(true);
        surface.set(service, "test");
        ((AtomicLong) field(service, "motionGeneration")).set(10);
        ContentSpaceRegionCache cache = (ContentSpaceRegionCache) field(service, "contentSpaceRegionCache");
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().motionPrediction(false).build());
        tracker.update(List.of(face(20)));
        List<TrackedObject> tracks = tracker.update(List.of(face(20)));
        updateCache(service, tracks, 9);
        assertEquals(0, cache.size());
        updateCache(service, tracks, 10);
        assertEquals(1, cache.size());
        List<Detection> before = cache.queryNearAsScreenDetections(1, "test", SystemClock.uptimeMillis(),
                0, 0, 100, 200, 100, 200);
        assertEquals(1, before.size());
        tracks = tracker.update(List.of(face(40)));
        updateCache(service, tracks, 9);
        List<Detection> after = cache.queryNearAsScreenDetections(1, "test", SystemClock.uptimeMillis(),
                0, 0, 100, 200, 100, 200);
        assertEquals(before.get(0).getBox(), after.get(0).getBox());
    }

    @Test public void sourceIdentitySnapshotDoesNotConsumeMotionOrSwapFastScrollNeighbors() throws Exception {
        for (int scroll : new int[] {400, -400}) {
            AccessibilityLegacyPipeline service = freshPipeline();
            Method attach = android.content.ContextWrapper.class.getDeclaredMethod(
                    "attachBaseContext", android.content.Context.class);
            attach.setAccessible(true);
            attach.invoke(field(service, "host"), androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .getTargetContext());
            Method bounds = AccessibilityLegacyPipeline.class.getDeclaredMethod("screenBounds");
            bounds.setAccessible(true);
            android.graphics.Rect viewport = (android.graphics.Rect) bounds.invoke(service);
            assertTrue(viewport.height() > 1600);
            ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().motionPrediction(false).build());
            tracker.update(List.of(phaseFace(500), phaseFace(900)), 1_000_000_000L);
            tracker.update(List.of(phaseFace(500), phaseFace(900)), 1_100_000_000L);
            Field trackerField = AccessibilityLegacyPipeline.class.getDeclaredField("tracker");
            trackerField.setAccessible(true);
            trackerField.set(service, tracker);
            ((AtomicLong) field(service, "cumulativeScrollY")).set(scroll);
            ((AtomicLong) field(service, "pendingTrackerOffsetY")).set(-scroll);
            Method snapshot = AccessibilityLegacyPipeline.class.getDeclaredMethod(
                    "identityTracksForSource", int.class, int.class, long.class, long.class);
            snapshot.setAccessible(true);
            @SuppressWarnings("unchecked") List<TrackedObject> sourceTracks = (List<TrackedObject>)
                    snapshot.invoke(service, viewport.width(), viewport.height(), 0L, (long) scroll);
            List<Detection> observations = List.of(phaseFace(500 - scroll), phaseFace(900 - scroll));
            com.subhub.app.detection.VisualIdentityReconciler.Result identity =
                    com.subhub.app.detection.VisualIdentityReconciler.reconcile(observations,
                            Collections.emptyList(), sourceTracks);
            assertEquals(1, identity.detections().get(0).getTrackId());
            assertEquals(2, identity.detections().get(1).getTrackId());
            assertEquals(-scroll, ((AtomicLong) field(service, "pendingTrackerOffsetY")).get());
            assertEquals(scroll, ((AtomicLong) field(service, "cumulativeScrollY")).get());
            assertEquals(500, tracker.activeTracks().get(0).getRawBox().getY());
            tracker.offsetActiveTracks(0, -scroll, viewport.width(), viewport.height());
            List<TrackedObject> updated = tracker.update(identity.detections(), 1_430_000_000L);
            assertEquals(2, updated.size());
            assertEquals(0, updated.get(0).getFramesMissing());
            assertEquals(0, updated.get(1).getFramesMissing());
        }
    }

    private static Detection phaseFace(int y) {
        return new Detection("FACE_FEMALE", "face", .99f, new BBox(160, y, 277, 255), true, false);
    }

    private static Detection face(int x) {
        return new Detection("FACE_FEMALE", "face", .99f, new BBox(x, 80, 20, 20), true, false);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void updateCache(AccessibilityLegacyPipeline service,
            List<TrackedObject> tracks, long generation) throws Exception {
        Method update = null;
        for (Method candidate : AccessibilityLegacyPipeline.class.getDeclaredMethods()) {
            if (candidate.getName().equals("updateWorldCache")) update = candidate;
        }
        assertNotNull(update);
        int count = update.getParameterTypes().length;
        assertEquals(14, count);
        assertEquals(ContentSpaceRegionCache.Evidence.class, update.getParameterTypes()[7]);
        assertEquals(long.class, update.getParameterTypes()[11]);
        update.setAccessible(true);
        update.invoke(service, tracks, 0L, 0L, 100, 200, 100, 200,
                ContentSpaceRegionCache.Evidence.full(ContentSpaceRegionCache.Evidence.FAST,
                        SystemClock.uptimeMillis()), "test", 1L,
                "test", generation, Collections.emptyList(), null);
    }

    private static Object begin(AccessibilityLegacyPipeline service, long sequence, long now,
            boolean settled, boolean continuous) throws Exception {
        Field running = AccessibilityLegacyPipeline.class.getDeclaredField("running");
        running.setAccessible(true);
        running.setBoolean(service, true);
        CaptureEpoch epoch = (CaptureEpoch) field(service, "captureEpoch");
        if (epoch.token() == 0) epoch.invalidate();
        ((AtomicLong) field(service, "motionGeneration")).set(1);
        ((java.util.concurrent.atomic.AtomicInteger) field(service, "activeApplicationWindowId")).set(1);
        Method method = AccessibilityLegacyPipeline.class.getDeclaredMethod("stageScene", long.class,
                long.class, long.class, long.class, long.class, boolean.class, boolean.class,
                boolean.class, SpatialRegionCache.Frame.class);
        method.setAccessible(true);
        return method.invoke(service, 1L, sequence, 1L, now, now, settled, false, continuous, null);
    }

    @SuppressWarnings("unchecked")
    private static SceneTransactionCoordinator.Transition<Detection> complete(
            AccessibilityLegacyPipeline service, Object scene) throws Exception {
        Class<?> frameType = Class.forName(AccessibilityLegacyPipeline.class.getName() + "$InferenceFrame");
        Constructor<?> constructor = frameType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        SceneTransactionCoordinator.SceneKey stamp = key(scene);
        long now = stamp.screenshotUptimeMillis();
        Object frame = constructor.newInstance(null, stamp.captureEpoch(), 0L, 0L,
                stamp.motionGeneration(), 100, 200, false, field(scene, "continuousMotionInference"),
                false, false, now, 0L, 0L, stamp.motionGeneration(),
                CaptureTimeReference.accessibility(false, now, now, now), false, 0L, 1L, "",
                stamp.fastSequence(), 1, scene);
        Method method = AccessibilityLegacyPipeline.class.getDeclaredMethod(
                "completeFastScene", frameType, long.class, List.class);
        method.setAccessible(true);
        return (SceneTransactionCoordinator.Transition<Detection>) method.invoke(
                service, frame, stamp.motionGeneration(), Collections.emptyList());
    }
    private static void motion(AccessibilityLegacyPipeline service) throws Exception {
        Method method = AccessibilityLegacyPipeline.class.getDeclaredMethod("invalidateNonReprojectableSceneForMotion");
        method.setAccessible(true);
        method.invoke(service);
    }
    private static SceneTransactionCoordinator.SceneKey key(Object scene) throws Exception {
        Field field = scene.getClass().getDeclaredField("key");
        field.setAccessible(true);
        return (SceneTransactionCoordinator.SceneKey) field.get(scene);
    }
    private static boolean cancelled(Object scene) throws Exception {
        Field field = scene.getClass().getDeclaredField("cancelled");
        field.setAccessible(true);
        return ((AtomicBoolean) field.get(scene)).get();
    }
    @SuppressWarnings("unchecked")
    private static SceneTransactionCoordinator<Detection> coordinator(AccessibilityLegacyPipeline service) throws Exception {
        Field field = AccessibilityLegacyPipeline.class.getDeclaredField("sceneCoordinator");
        field.setAccessible(true);
        return (SceneTransactionCoordinator<Detection>) field.get(service);
    }
}
