package com.subhub.app.atmosphere;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

/** Shows the current character above the key without taking focus or intercepting touch. */
final class GagKeyPreview {
    private View owner;
    private PopupWindow popup;

    void show(TextView key) {
        dismiss();
        if (!key.isAttachedToWindow()) return;
        Rect visible = new Rect();
        key.getWindowVisibleDisplayFrame(visible);
        int height = dp(key, 42);
        int[] origin = new int[2];
        key.getLocationOnScreen(origin);
        if (visible.width() <= 0 || origin[1] - height < visible.top) return;
        int width = Math.min(visible.width(), Math.max(key.getWidth(), dp(key, 44)));
        int left = Math.max(visible.left, Math.min(origin[0] + (key.getWidth() - width) / 2,
                visible.right - width));
        int top = origin[1] - height;

        TextView label = new TextView(key.getContext());
        label.setText(key.getText());
        label.setTextColor(Color.WHITE);
        label.setTextSize(30);
        label.setGravity(Gravity.CENTER);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff444446);
        background.setCornerRadius(dp(key, 8));
        label.setBackground(background);

        PopupWindow preview = new PopupWindow(label, width, height, false);
        preview.setTouchable(false);
        preview.setAnimationStyle(0);
        preview.setElevation(dp(key, 4));
        if (Build.VERSION.SDK_INT >= 29) {
            preview.setIsLaidOutInScreen(true);
        } else {
            int[] inWindow = new int[2];
            key.getLocationInWindow(inWindow);
            left += inWindow[0] - origin[0];
            top += inWindow[1] - origin[1];
        }
        try {
            preview.showAtLocation(key, Gravity.TOP | Gravity.LEFT, left, top);
            popup = preview;
            owner = key;
        } catch (WindowManager.BadTokenException detached) {
            preview.dismiss();
        }
    }

    void dismiss(View key) {
        if (key == owner) dismiss();
    }

    void dismiss() {
        if (popup != null) popup.dismiss();
        popup = null;
        owner = null;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
