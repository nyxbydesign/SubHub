package com.subhub.app.atmosphere;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Shows the current character above the key without taking focus or intercepting touch. */
final class GagKeyPreview {
    private final Rect visible = new Rect();
    private final Rect hostBounds = new Rect();
    private final int[] keyPosition = new int[2];
    private final int[] hostPosition = new int[2];
    private final View.OnAttachStateChangeListener ownerAttachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { dismiss(view); }
    };
    private View owner;
    private ViewGroup host;
    private TextView label;
    private int densityDpi;
    private float fontScale;

    void show(TextView key) {
        if (!key.isAttachedToWindow() || !(key.getRootView() instanceof ViewGroup root)) {
            dismiss();
            return;
        }
        key.getWindowVisibleDisplayFrame(visible);
        root.getLocationOnScreen(hostPosition);
        key.getLocationOnScreen(keyPosition);
        hostBounds.set(hostPosition[0], hostPosition[1],
                hostPosition[0] + root.getWidth(), hostPosition[1] + root.getHeight());
        if (!visible.intersect(hostBounds)) {
            dismiss();
            return;
        }
        int bottom = Math.min(keyPosition[1], visible.bottom);
        int height = Math.min(dp(key, 42), bottom - visible.top);
        if (height < dp(key, 20)) {
            dismiss();
            return;
        }
        int width = Math.min(visible.width(), Math.max(key.getWidth(), dp(key, 44)));
        int center = keyPosition[0] + (key.getWidth() - width) / 2;
        int left = Math.max(visible.left, Math.min(center, visible.right - width));

        boolean renewContent = label == null || label.getContext() != key.getContext()
                || densityDpi != key.getResources().getDisplayMetrics().densityDpi
                || fontScale != key.getResources().getConfiguration().fontScale;
        if (renewContent || host != root) dismiss();
        if (renewContent) createLabel(key);

        label.setText(key.getText());
        label.setTextSize(30f * height / dp(key, 42));
        label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        int localLeft = left - hostPosition[0];
        int localBottom = bottom - hostPosition[1];
        label.layout(localLeft, localBottom - height, localLeft + width, localBottom);
        // Drawing in the keyboard window keeps rapid key changes out of window-move animations.
        if (host == null) {
            host = root;
            host.getOverlay().add(label);
        }
        setOwner(key);
    }

    private void createLabel(View key) {
        densityDpi = key.getResources().getDisplayMetrics().densityDpi;
        fontScale = key.getResources().getConfiguration().fontScale;
        label = new TextView(key.getContext());
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff444446);
        background.setCornerRadius(dp(key, 8));
        label.setBackground(background);
        label.setElevation(dp(key, 4));
    }

    void dismiss(View key) {
        if (key == owner) dismiss();
    }

    void dismiss() {
        setOwner(null);
        if (host != null) {
            host.getOverlay().remove(label);
            host = null;
        }
        if (label != null) label.setText(null);
    }

    private void setOwner(View next) {
        if (next == owner) return;
        if (owner != null) owner.removeOnAttachStateChangeListener(ownerAttachment);
        owner = next;
        if (owner != null) owner.addOnAttachStateChangeListener(ownerAttachment);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
