package com.subhub.app.atmosphere;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Hosts Android's photo-permission dialog from a deliberate Clipboard open. */
public final class GagClipboardPermissionActivity extends Activity {
    private static final int PHOTOS = 1;

    static void open(Context keyboard) {
        try {
            keyboard.startActivity(new Intent(keyboard, GagClipboardPermissionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            // The keyboard's text and copied-image history can continue without MediaStore.
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (GagScreenshotClipboard.access(this) != GagScreenshotClipboard.Access.NONE) {
            finishRequest();
        } else if (state == null) {
            requestPermissions(GagScreenshotClipboard.requestedPermissions(), PHOTOS);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PHOTOS) finishRequest();
    }

    private void finishRequest() {
        GagScreenshotClipboard.updateAccess(this);
        finish();
    }
}
