package com.subhub.app.fullaccess;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.PreferencePage;

/** The authenticated paired Dom's read-only Full Access view. */
public final class FullAccessActivity extends PreferencePage {
    private static final String PEER = "full_access_peer";
    private static final String SCOPE = "blackmail_scope";
    private static final String SCROLL = "blackmail_scroll";
    private FullAccessViewer viewer;
    private boolean resumed;
    private long revision;

    public static void openPeer(Activity activity, PersonId peer) {
        if (peer != null && validPeer(peer.value()) && viewerAllowed(activity))
            activity.startActivity(new Intent(activity, FullAccessActivity.class).putExtra(PEER, peer.value()));
    }

    private static boolean validPeer(String peer) {
        return peer != null && peer.matches("remote_[0-9a-f]{64}_[A-Za-z0-9_-]{1,128}");
    }

    private static boolean viewerAllowed(Context context) {
        return DeviceRolePreferences.supportsDom(context) && ControllerPinManager.isDomWorkspaceUnlocked();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String peer = getIntent().getStringExtra(PEER);
        if (!validPeer(peer) || !viewerAllowed(this)) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        page(R.string.full_access_title);
        LinearLayout controls = PersonUi.column(this);
        page.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        revision = ControllerPinManager.domWorkspaceRevision();
        viewer = new FullAccessViewer(this, controls, new PersonId(peer), this::viewerCurrent);
        if (state != null) viewer.restoreNavigation(state.getString(SCOPE), state.getInt(SCROLL));
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!viewerCurrent()) { finish(); return; }
        if (viewer != null) viewer.resume();
    }

    private boolean viewerCurrent() {
        return resumed && !isFinishing() && !isDestroyed() && viewerAllowed(this)
                && ControllerPinManager.domWorkspaceRevision() == revision;
    }

    @Override protected void onPause() {
        resumed = false;
        if (viewer != null) viewer.pause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (viewer != null && viewerAllowed(this) && ControllerPinManager.domWorkspaceRevision() == revision) {
            state.putString(SCOPE, viewer.selectedScope());
            state.putInt(SCROLL, viewer.navigationScroll());
        }
    }

    @Override protected void onDestroy() {
        if (viewer != null) viewer.close();
        super.onDestroy();
    }
}
