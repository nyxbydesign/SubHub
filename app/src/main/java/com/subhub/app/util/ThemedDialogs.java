package com.subhub.app.util;

import android.content.Context;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.subhub.app.R;

/** One app-owned dialog shell, including confirmation, picker and secure PIN surfaces. */
public final class ThemedDialogs {
    private ThemedDialogs() { }

    public static MaterialAlertDialogBuilder builder(Context context) {
        return new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_SubHub_AlertDialog)
                .setBackground(ContextCompat.getDrawable(context, R.drawable.bg_native_dialog));
    }
}
