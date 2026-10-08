package com.subhub.app.service;

import android.os.SystemClock;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercise the service-to-coordinator wiring, without connecting or starting capture workers. */
public final class SceneMotionIntegrationAndroidTest {
    @Test public void continuousServiceScenesSurviveMotionButNotNavigationOrSupersession() throws Exception {
        for (boolean settled : new boolean[] {false, true}) {
            ScreenshotAccessibilityService service = new ScreenshotAccessibilityService();
            long now = SystemClock.uptimeMillis();
            Object scene = begin(service, 1, now, settled, true);
            SceneTransactionCoordinator<Detection> coordinator = coordinator(service);
            SceneTransactionCoordinator.SceneKey key = key(scene);
            for (int reversal = 0; reversal < 8; reversal++) motion(service);
            assertFalse(cancelled(scene));
            SceneTransactionCoordinator.Commit<Detection> commit =
                    coordinator.fastReady(key, Collections.emptyList(), now + 1).commit();
            assertNotNull(commit);
            motion(service);
            assertTrue(coordinator.isPresentationCurrent(commit));
            Object replacement = begin(service, 2, now + 2, settled, true);
            assertTrue(cancelled(scene));
            assertFalse(coordinator.isPresentationCurrent(commit));
            motion(service);
            assertFalse(cancelled(replacement));
            Method invalidate = ScreenshotAccessibilityService.class.getDeclaredMethod("invalidateCurrentScene", String.class);
            invalidate.setAccessible(true);
            invalidate.invoke(service, "test-document-change");
            assertTrue(cancelled(replacement));
            assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                    coordinator.fastReady(key(replacement), Collections.emptyList(), now + 3).status());
        }
    }

    @Test public void nonContinuousServiceSceneStillClosesForMotion() throws Exception {
        ScreenshotAccessibilityService service = new ScreenshotAccessibilityService();
        long now = SystemClock.uptimeMillis();
        Object scene = begin(service, 1, now, false, false);
        motion(service);
        assertTrue(cancelled(scene));
        assertEquals(SceneTransactionCoordinator.Status.DROPPED_CLOSED,
                coordinator(service).fastReady(key(scene), Collections.emptyList(), now + 1).status());
    }

    @Test public void oldGenerationCanQueryButCannotInsertOrMoveEventCacheEvidence() throws Exception {
        ScreenshotAccessibilityService service = new ScreenshotAccessibilityService();
        Field surface = ScreenshotAccessibilityService.class.getDeclaredField("activeScrollSurfaceKey");
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

    private static Detection face(int x) {
        return new Detection("FACE_FEMALE", "face", .99f, new BBox(x, 80, 20, 20), true, false);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void updateCache(ScreenshotAccessibilityService service,
            List<TrackedObject> tracks, long generation) throws Exception {
        Method update = null;
        for (Method candidate : ScreenshotAccessibilityService.class.getDeclaredMethods()) {
            if (candidate.getName().equals("updateWorldCache")) update = candidate;
        }
        assertNotNull(update);
        // The prototype has an additional late-quality list; both must carry the generation fence.
        int count = update.getParameterTypes().length;
        assertTrue(count == 13 || count == 14);
        assertEquals(long.class, update.getParameterTypes()[11]);
        update.setAccessible(true);
        if (count == 14) {
            update.invoke(service, tracks, 0L, 0L, 100, 200, 100, 200, false, "test", 1L,
                    "test", generation, Collections.emptyList(), null);
        } else {
            update.invoke(service, tracks, 0L, 0L, 100, 200, 100, 200, false, "test", 1L,
                    "test", generation, null);
        }
    }

    private static Object begin(ScreenshotAccessibilityService service, long sequence, long now,
            boolean settled, boolean continuous) throws Exception {
        Method method = ScreenshotAccessibilityService.class.getDeclaredMethod("beginScene", long.class,
                long.class, long.class, long.class, long.class, boolean.class, boolean.class,
                boolean.class, SpatialRegionCache.Frame.class);
        method.setAccessible(true);
        return method.invoke(service, 1L, sequence, 1L, now, now, settled, false, continuous, null);
    }
    private static void motion(ScreenshotAccessibilityService service) throws Exception {
        Method method = ScreenshotAccessibilityService.class.getDeclaredMethod("invalidateNonReprojectableSceneForMotion");
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
    private static SceneTransactionCoordinator<Detection> coordinator(ScreenshotAccessibilityService service) throws Exception {
        Field field = ScreenshotAccessibilityService.class.getDeclaredField("sceneCoordinator");
        field.setAccessible(true);
        return (SceneTransactionCoordinator<Detection>) field.get(service);
    }
}
