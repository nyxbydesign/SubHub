package com.subhub.app.fullaccess;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

import com.subhub.app.R;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;

import java.util.concurrent.ThreadLocalRandom;

/** Launches the Android prompt from an unlocked, focused Activity for one live request. */
public final class FullAccessScreenActivity extends Activity {
    private static final int SCREEN_CONSENT = 1953;
    private static final String REQUESTED = "projection_requested";
    private FullAccessScreen.Session session;
    private boolean requested;
    private boolean handedOff;
    private final Runnable cancelPrompt = () -> {
        if (requested && !handedOff) finishActivity(SCREEN_CONSENT);
        finish();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        session = FullAccessScreen.find(getIntent().getStringExtra(FullAccessScreen.EXTRA_SESSION));
        requested = state != null && state.getBoolean(REQUESTED);
        if (session == null || !session.revalidate() || (requested && !session.reattachPrompt(cancelPrompt))) {
            session = null;
            finish();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || requested || isFinishing() || session == null) return;
        if (!session.revalidate() || !FullAccessScreen.displayAvailable(this)) {
            end(FullAccessScreen.UNAVAILABLE);
            return;
        }
        if (MediaProjectionLeaseRegistry.owner() != null) {
            end(FullAccessScreen.IN_USE);
            return;
        }
        try {
            MediaProjectionManager projection = getSystemService(MediaProjectionManager.class);
            if (projection == null || FullAccessScreen.activeNotifications(this) == null) {
                end(FullAccessScreen.UNAVAILABLE);
                return;
            }
            Intent consent = projection.createScreenCaptureIntent();
            if (!session.claimPrompt(cancelPrompt)) {
                finish();
                return;
            }
            requested = true;
            String[] messages = getResources().getStringArray(R.array.full_access_screen_toasts);
            Toast.makeText(this, messages[ThreadLocalRandom.current().nextInt(messages.length)],
                    Toast.LENGTH_SHORT).show();
            startActivityForResult(consent, SCREEN_CONSENT);
        } catch (RuntimeException unavailable) {
            end(FullAccessScreen.UNAVAILABLE);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode != SCREEN_CONSENT || session == null || handedOff) return;
        if (resultCode != RESULT_OK || result == null) {
            end(FullAccessScreen.DECLINED);
            return;
        }
        if (!session.revalidate() || !FullAccessScreen.displayAvailable(this)
                || !session.acceptApproval(result)) {
            end(FullAccessScreen.UNAVAILABLE);
            return;
        }
        try {
            startForegroundService(new Intent(this, FullAccessScreenService.class)
                    .putExtra(FullAccessScreen.EXTRA_SESSION, session.id));
            handedOff = true;
            finish();
        } catch (RuntimeException unavailable) {
            end(FullAccessScreen.UNAVAILABLE);
        }
    }

    private void end(String status) {
        if (session != null) session.end(status);
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(REQUESTED, requested);
    }

    @Override protected void onDestroy() {
        if (session != null) {
            session.clearOnEnd(cancelPrompt);
            if (!handedOff && !isChangingConfigurations() && isFinishing() && requested) session.close();
        }
        super.onDestroy();
    }
}
