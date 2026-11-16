package com.subhub.app.people;

import android.content.Context;
import android.os.Process;
import com.subhub.app.fullaccess.FullAccessGrant;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.HardcoreModeManager;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Warm the existing readers once on first entry into each authorized workspace. */
public final class ProfileWarmup {
    private final Context app;
    private final Executor worker = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "subhub-profile-warmup"));
    private boolean subStarted;
    private long domStarted = -1;
    private static volatile PreparedSub preparedSub;

    private record PreparedSub(Context owner, long workspace, java.util.Locale locale,
            ProfileStore.PreparedRead profile) {}

    public ProfileWarmup(Context app) { this.app = app.getApplicationContext(); }

    public void onWorkspaceOpen() {
        if (ProfileStore.mayEdit(app, false) && !subStarted) {
            subStarted = true;
            long revision = ControllerPinManager.domWorkspaceRevision();
            worker.execute(() -> {
                try {
                    prepareSelf(app, revision);
                    if (new HardcoreModeManager(app).isEnabled()) FullAccessGrant.isLocallyEnabled(app);
                } catch (RuntimeException unavailable) { /* Page-owned reads report failures. */ }
            });
        }
        long revision = ControllerPinManager.domWorkspaceRevision();
        if (domStarted != revision && DummySubPreview.authorized(app)) {
            domStarted = revision;
            worker.execute(() -> {
                if (!DummySubPreview.authorized(app) || ControllerPinManager.domWorkspaceRevision() != revision) return;
                try {
                    // Ordinary startup must never provision or migrate a Demo Sub.
                    if (InternalSubData.provisioned(app)) {
                        InternalSubData backend = InternalSubData.get(app);
                        if (DummySubPreview.current(app, backend.acknowledged().generation, revision))
                            backend.requestPresentation(app);
                    }
                } catch (RuntimeException unavailable) { /* Page-owned reads report failures. */ }
            });
        }
    }

    static ProfileData loadSelf(Context app, long workspace) {
        return prepareSelf(app, workspace).load();
    }

    private static ProfileStore.PreparedRead prepareSelf(Context app, long workspace) {
        if (!ProfileStore.mayEdit(app, false) || ControllerPinManager.domWorkspaceRevision() != workspace)
            throw new SecurityException("Sub profile unavailable");
        java.util.Locale locale = locale(app);
        PreparedSub previous = preparedSub;
        if (previous != null && previous.owner() == app && previous.workspace() == workspace
                && locale.equals(previous.locale())) return previous.profile();
        ProfileStore.PreparedRead profile = ProfileStore.prepareRead(app, false);
        if (ProfileStore.mayEdit(app, false) && ControllerPinManager.domWorkspaceRevision() == workspace
                && locale.equals(locale(app))) preparedSub = new PreparedSub(app, workspace, locale, profile);
        return profile;
    }

    private static java.util.Locale locale(Context app) {
        return app.getResources().getConfiguration().getLocales().get(0);
    }
}
