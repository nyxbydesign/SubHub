package com.subhub.app.util;

import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.snackbar.Snackbar;
import com.subhub.app.R;

/** Shows a screen-owned notice above visible navigation while its host is started. */
public final class ForegroundSnackbar {
    private ForegroundSnackbar() {}

    public static Snackbar show(AppCompatActivity host, View root, Snackbar current,
            CharSequence message, int duration) {
        if (root == null || host.isFinishing() || !root.isAttachedToWindow()
                || !host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            Toast.makeText(host, message,
                    duration == Snackbar.LENGTH_LONG ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return current;
        }
        Snackbar notice = Snackbar.make(root, message, duration);
        View navigation = root.findViewById(R.id.bottom_navigation);
        if (navigation != null && navigation.isShown()) notice.setAnchorView(navigation);
        notice.show();
        return notice;
    }

    public static void dismiss(Snackbar notice) {
        if (notice != null) notice.dismiss();
    }
}
