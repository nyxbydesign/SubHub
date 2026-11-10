package com.subhub.app.update;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Isolates fixture-owned updater work after an externally established cold-start baseline. */
final class UpdaterTestQuiescence {
    private UpdaterTestQuiescence() {}

    static void requireColdStartBaseline(Context context, WorkManager manager) throws Exception {
        assertTrue("Harness must attest the auto-off/process-stop/install cold-start sequence",
                "true".equals(androidx.test.platform.app.InstrumentationRegistry.getArguments()
                        .getString("updater_fixture_cold_start")));
        assertTrue("Automatic checks must already be off before this process starts",
                !context.getSharedPreferences("subhub_updates", Context.MODE_PRIVATE)
                        .getBoolean("automatic_checks", true));
        UpdateStateStore state = new UpdateStateStore(context);
        assertTrue("Cold baseline must not contain a current updater download", state.downloadId() < 0);
        assertTrue("Cold baseline must not contain a verified updater file", state.verifiedPath().isEmpty());
        requireTerminalWork(manager);
    }

    static void stop(Context context, WorkManager manager) throws Exception {
        // Only fixture-owned downloads/jobs can exist after the required cold-start precondition.
        new UpdateStateStore(context).setAutomaticChecks(false);
        manager.cancelUniqueWork(UpdateScheduler.PERIODIC_NAME).getResult().get(5, TimeUnit.SECONDS);
        manager.cancelUniqueWork(UpdateScheduler.IMMEDIATE_NAME).getResult().get(5, TimeUnit.SECONDS);
        requireTerminalWork(manager);
        Field field = GitHubReleaseRepository.class.getDeclaredField("CHECK_LOCK");
        field.setAccessible(true);
        Object checkLock = field.get(null);
        CountDownLatch drained = new CountDownLatch(1);
        Thread barrier = new Thread(() -> {
            synchronized (checkLock) {
                // The controlled loopback-check threads are joined by their fixtures; this also
                // waits for checks already admitted to the repository monitor.
            }
            synchronized (UpdateDownloadFinalizer.class) {
                // Wait for any already-admitted fixture download finalization.
            }
            drained.countDown();
        }, "updater-fixture-quiescence");
        barrier.setDaemon(true);
        barrier.start();
        // These monitor barriers do not prove cancellation of an original Worker body delayed before
        // repository admission. The external process boundary and terminal baseline exclude those owners.
        // On failure the observer never restores preferences, hooks or scheduling in the background.
        assertTrue("Admitted fixture checks/finalization did not drain; leave checks disabled and hooks unchanged",
                drained.await(30, TimeUnit.SECONDS));
        barrier.join(1000L);
        assertTrue("Updater drain observer must terminate", !barrier.isAlive());
    }

    private static void requireTerminalWork(WorkManager manager) throws Exception {
        for (String name : new String[]{UpdateScheduler.PERIODIC_NAME, UpdateScheduler.IMMEDIATE_NAME}) {
            for (WorkInfo work : manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)) {
                assertTrue("Updater work must be terminal before fixture state changes", work.getState().isFinished());
            }
        }
    }
}
