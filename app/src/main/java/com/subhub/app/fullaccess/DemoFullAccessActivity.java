package com.subhub.app.fullaccess;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.LinearLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.subhub.app.R;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.people.DummySubPreview;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.people.PersonUi;
import com.subhub.app.people.ProfileEditorActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.PreferencePage;

/** The Dom can inspect their own Android data through the Demo profile without a peer session. */
public final class DemoFullAccessActivity extends PreferencePage {
    private static final String SCOPE = "blackmail_scope";
    private static final String SCROLL = "blackmail_scroll";
    private static final String PENDING_SCREEN_GRANT = "blackmail_pending_screen_grant";
    private DemoFullAccess source;
    private LinearLayout controls;
    private FullAccessViewer viewer;
    private boolean resumed;
    private String restoredScope;
    private int restoredScroll;
    private Long restoredScreenGrant;
    private ScreenRequest screenRequest;
    private boolean screenResultReady;
    private Intent screenApproval;

    private record ScreenRequest(FullAccessViewer viewer, long selection, long grant) {
        boolean matches(FullAccessViewer selected, long currentGrant) {
            return viewer == selected && selection == viewer.screenSelection() && grant == currentGrant;
        }
    }

    private final ActivityResultLauncher<Intent> screen = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (screenResultReady || screenRequest == null && restoredScreenGrant == null) return;
                if (!allowed() || !pendingScreenMatches(source.grantRevision())) {
                    clearScreenRequest();
                    return;
                }
                screenApproval = result.getResultCode() == RESULT_OK ? result.getData() : null;
                screenResultReady = true;
                applyScreenResult();
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!DummySubPreview.isAllowed(this)) { finish(); return; }
        InternalSubData data = InternalSubData.peek(this);
        if (data == null) { finish(); return; }
        source = new DemoFullAccess(this, data.acknowledged().generation, ControllerPinManager.domWorkspaceRevision());
        if (!authorized()) { finish(); return; }
        page(R.string.full_access_title);
        controls = PersonUi.column(this);
        page.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        if (state != null) {
            restoredScope = state.getString(SCOPE);
            restoredScroll = state.getInt(SCROLL);
            if ("screen".equals(restoredScope) && state.containsKey(PENDING_SCREEN_GRANT)
                    && state.getLong(PENDING_SCREEN_GRANT) == source.grantRevision() && allowed()) {
                restoredScreenGrant = state.getLong(PENDING_SCREEN_GRANT);
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!authorized()) { finish(); return; }
        if (!source.current()) {
            if (viewer != null) viewer.close();
            viewer = null;
            clearScreenRequest();
            restoredScope = null;
            restoredScroll = 0;
            controls.removeAllViews();
            LinearLayout card = PersonUi.card(controls);
            PersonUi.text(card, getString(R.string.demo_full_access_enable_profile), false);
            PersonUi.action(card, R.string.profile_edit, () -> {
                if (authorized()) startActivity(DummySubPreview.childIntent(this, ProfileEditorActivity.class));
            }, true);
            return;
        }
        if (viewer == null) {
            controls.removeAllViews();
            viewer = new FullAccessViewer(this, controls, source);
            viewer.restoreNavigation(restoredScope, restoredScroll);
            if (restoredScreenGrant != null) {
                long selection = viewer.screenSelection();
                if (selection != 0 && restoredScreenGrant == source.grantRevision())
                    screenRequest = new ScreenRequest(viewer, selection, restoredScreenGrant);
                else clearScreenRequest();
                restoredScreenGrant = null;
            }
            restoredScope = null;
            restoredScroll = 0;
        }
        viewer.resume();
        applyScreenResult();
    }

    @Override protected void onPause() {
        resumed = false;
        if (viewer != null) viewer.pause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (allowed()) {
            state.putString(SCOPE, viewer == null ? restoredScope : viewer.selectedScope());
            state.putInt(SCROLL, viewer == null ? restoredScroll : viewer.navigationScroll());
            long grant = source.grantRevision();
            if (!screenResultReady && pendingScreenMatches(grant)) state.putLong(PENDING_SCREEN_GRANT, grant);
        }
    }

    @Override protected void onDestroy() {
        clearScreenRequest();
        if (viewer != null) viewer.close();
        viewer = null;
        super.onDestroy();
    }

    boolean viewerCurrent() { return resumed && allowed(); }

    private boolean authorized() {
        return source != null && !isFinishing() && !isDestroyed()
                && DummySubPreview.isAllowed(this) && source.available();
    }

    private boolean allowed() { return authorized() && source.current(); }

    void requestScreen() {
        FullAccessViewer requested = viewer;
        if (!viewerCurrent() || requested == null || screenRequest != null || restoredScreenGrant != null) return;
        long grant = source.grantRevision();
        long selection = requested.screenSelection();
        if (selection == 0) return;
        if (MediaProjectionLeaseRegistry.owner() != null) {
            requested.screenRequestEnded();
            notice(getString(R.string.full_access_screen_busy));
            return;
        }
        try {
            MediaProjectionManager projection = getSystemService(MediaProjectionManager.class);
            if (projection == null) {
                requested.screenRequestEnded();
                notice(getString(R.string.full_access_unavailable));
                return;
            }
            if (FullAccessScreen.activeNotifications(this) == null) {
                requested.screenRequestEnded();
                notice(getString(R.string.full_access_screen_alerts_required));
                startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                return;
            }
            if (!viewerCurrent() || source.grantRevision() != grant) {
                requested.screenRequestEnded();
                return;
            }
            screenRequest = new ScreenRequest(requested, selection, grant);
            screen.launch(projection.createScreenCaptureIntent());
        } catch (RuntimeException unavailable) {
            clearScreenRequest();
            requested.screenRequestEnded();
            notice(getString(R.string.full_access_unavailable));
        }
    }

    private void applyScreenResult() {
        if (screenRequest == null || !screenResultReady || !resumed) return;
        ScreenRequest completed = screenRequest;
        Intent approval = screenApproval;
        clearScreenRequest();
        if (!viewerCurrent() || !completed.matches(viewer, source.grantRevision())) return;
        if (approval == null) viewer.screenRequestEnded();
        else viewer.showLocalScreen(approval);
    }

    private void clearScreenRequest() {
        restoredScreenGrant = null;
        screenRequest = null;
        screenResultReady = false;
        screenApproval = null;
    }

    private boolean pendingScreenMatches(long grant) {
        return restoredScreenGrant != null ? restoredScreenGrant == grant && "screen".equals(restoredScope)
                : screenRequest != null && screenRequest.matches(viewer, grant);
    }
}
