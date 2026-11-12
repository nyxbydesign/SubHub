package com.subhub.app.fullaccess;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.WindowManager;
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
    private DemoFullAccess source;
    private LinearLayout controls;
    private FullAccessViewer viewer;
    private boolean resumed;
    private boolean screenRequested;
    private Intent screenApproval;

    private final ActivityResultLauncher<Intent> screen = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (!screenRequested) return;
                screenRequested = false;
                if (allowed() && result.getResultCode() == RESULT_OK) {
                    screenApproval = result.getData();
                    applyScreenApproval();
                }
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!DummySubPreview.isAllowed(this)) { finish(); return; }
        InternalSubData data = InternalSubData.peek(this);
        if (data == null) { finish(); return; }
        source = new DemoFullAccess(this, data.acknowledged().generation, ControllerPinManager.domWorkspaceRevision());
        if (!authorized()) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        page(R.string.full_access_title);
        controls = PersonUi.column(this);
        page.addView(controls, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!authorized()) { finish(); return; }
        controls.removeAllViews();
        PersonUi.text(controls, getString(R.string.demo_full_access_local), false);
        if (!source.current()) {
            viewer = null;
            LinearLayout card = PersonUi.card(controls);
            PersonUi.text(card, getString(R.string.demo_full_access_enable_profile), false);
            PersonUi.action(card, R.string.profile_edit, () -> {
                if (authorized()) startActivity(DummySubPreview.childIntent(this, ProfileEditorActivity.class));
            }, true);
            return;
        }
        viewer = new FullAccessViewer(this, controls, source);
        viewer.resume();
        applyScreenApproval();
    }

    @Override protected void onPause() {
        resumed = false;
        if (viewer != null) viewer.close();
        viewer = null;
        super.onPause();
    }

    @Override protected void onDestroy() {
        screenApproval = null;
        super.onDestroy();
    }

    boolean viewerCurrent() { return resumed && allowed(); }

    private boolean authorized() {
        return source != null && !isFinishing() && !isDestroyed()
                && DummySubPreview.isAllowed(this) && source.available();
    }

    private boolean allowed() { return authorized() && source.current(); }

    void requestScreen() {
        if (!viewerCurrent() || screenRequested) return;
        if (MediaProjectionLeaseRegistry.owner() != null) {
            notice(getString(R.string.full_access_screen_busy));
            return;
        }
        try {
            MediaProjectionManager projection = getSystemService(MediaProjectionManager.class);
            if (projection == null) { notice(getString(R.string.full_access_unavailable)); return; }
            if (FullAccessScreen.activeNotifications(this) == null) {
                notice(getString(R.string.full_access_screen_alerts_required));
                startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                return;
            }
            screenRequested = true;
            screen.launch(projection.createScreenCaptureIntent());
        } catch (RuntimeException unavailable) {
            screenRequested = false;
            notice(getString(R.string.full_access_unavailable));
        }
    }

    private void applyScreenApproval() {
        if (screenApproval == null || !resumed) return;
        Intent approval = screenApproval;
        screenApproval = null;
        if (viewerCurrent() && viewer != null) viewer.showLocalScreen(approval);
    }
}
