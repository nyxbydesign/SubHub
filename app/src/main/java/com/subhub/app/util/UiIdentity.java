package com.subhub.app.util;

import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.DimenRes;

/** Shared typography and spacing tokens for XML and dynamically created UI. */
public final class UiIdentity {
    private UiIdentity() {}

    public static void textSize(TextView view, @DimenRes int size) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.getResources().getDimensionPixelSize(size));
    }

    public static void sectionIcon(TextView view, int icon) {
        android.graphics.drawable.Drawable drawable = view.getContext().getDrawable(icon).mutate();
        int size = Math.round(24 * view.getResources().getDisplayMetrics().density);
        drawable.setBounds(0, 0, size, size);
        drawable.setTint(view.getContext().getColor(com.subhub.app.R.color.text_primary));
        view.setCompoundDrawablesRelative(drawable, null, null, null);
        view.setCompoundDrawablePadding(view.getResources().getDimensionPixelSize(com.subhub.app.R.dimen.ui_gap_control));
    }

    public static void inputType(android.widget.EditText view, int type) {
        android.graphics.Typeface typeface = view.getTypeface();
        view.setInputType(type);
        view.setTypeface(typeface);
    }

    public static void cardPadding(android.view.View view) {
        int side = view.getResources().getDimensionPixelSize(com.subhub.app.R.dimen.ui_card_padding);
        int vertical = view.getResources().getDimensionPixelSize(com.subhub.app.R.dimen.ui_card_padding_vertical);
        view.setPaddingRelative(side, vertical, side, vertical);
    }
}
