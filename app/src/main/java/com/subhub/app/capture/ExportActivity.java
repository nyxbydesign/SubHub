package com.subhub.app.capture;

import android.graphics.Bitmap;
import android.net.Uri;
import java.io.IOException;

/** Stable navigation entry point for the gallery workspace. */
public final class ExportActivity extends com.subhub.app.capture.export.ExportWorkspaceActivity {
    Uri saveToGallery(Bitmap bitmap, int sequence) throws IOException {
        return saveBitmapCopy(bitmap);
    }
}
