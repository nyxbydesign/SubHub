package com.subhub.app.atmosphere;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.view.AccessibilityDelegateCompat;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.subhub.app.R;

/** Shared focus and accessibility behavior for Atmosphere disclosure controls. */
public final class AtmosphereDisclosure {
    private static final AccessibilityDelegateCompat BUTTON_ROLE = new AccessibilityDelegateCompat() {
        @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(Button.class.getName());
        }
    };

    private AtmosphereDisclosure() {}

    public static void bind(TextView header, View content, boolean expanded) {
        ViewCompat.setAccessibilityDelegate(header, BUTTON_ROLE);
        header.setOnClickListener(view -> show(header, content, content.getVisibility() != View.VISIBLE));
        show(header, content, expanded);
    }

    public static void show(TextView header, View content, boolean expanded) {
        if (!expanded && content.hasFocus()) {
            InputMethodManager keyboard = content.getContext().getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(content.getWindowToken(), 0);
            content.clearFocus();
            header.requestFocus();
        }
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        header.setSelected(expanded);
        Drawable icon = header.getCompoundDrawablesRelative()[0];
        Drawable arrow = AppCompatResources.getDrawable(header.getContext(),
                expanded ? R.drawable.ic_atmosphere_chevron_down : R.drawable.ic_overview_chevron);
        if (arrow != null) arrow.setBounds(0, 0, arrow.getIntrinsicWidth(), arrow.getIntrinsicHeight());
        header.setCompoundDrawablesRelative(icon, null, arrow, null);
        ViewCompat.setStateDescription(header, header.getContext().getString(
                expanded ? R.string.section_expanded : R.string.section_collapsed));
    }
}
