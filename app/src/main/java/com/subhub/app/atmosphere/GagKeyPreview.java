package com.subhub.app.atmosphere;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

/** Shows the current character above the key without taking focus or intercepting touch. */
final class GagKeyPreview {
    private final Rect visible = new Rect();
    private final View.OnAttachStateChangeListener ownerAttachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { dismiss(view); }
    };
    private View owner;
    private PopupWindow popup;
    private TextView label;
    private int densityDpi;
    private float fontScale;

    void show(TextView key) {
        if (!key.isAttachedToWindow()) {
            dismiss();
            return;
        }
        key.getWindowVisibleDisplayFrame(visible);
        int height = dp(key, 42);
        if (visible.width() <= 0 || visible.height() < height) {
            dismiss();
            return;
        }
        int width = Math.min(visible.width(), Math.max(key.getWidth(), dp(key, 44)));
        var offset = GagKeyAlternativesPopup.above(key, visible, width, height, 0);

        boolean renewContent = label == null || label.getContext() != key.getContext()
                || densityDpi != key.getResources().getDisplayMetrics().densityDpi
                || fontScale != key.getResources().getConfiguration().fontScale;
        boolean move = !renewContent && owner != null && popup.isShowing()
                && owner.getRootView() == key.getRootView();
        if (!move) dismiss();
        if (renewContent || popup == null) createPopup(key);
        label.setText(key.getText());
        try {
            if (move) {
                popup.update(key, offset.x(), offset.y(), width, height);
            } else {
                popup.setWidth(width);
                popup.setHeight(height);
                popup.showAsDropDown(key, offset.x(), offset.y(), Gravity.LEFT);
            }
            setOwner(key);
        } catch (WindowManager.BadTokenException | IllegalArgumentException detached) {
            dismiss();
            popup = null;
            label = null;
        }
    }

    private void createPopup(View key) {
        densityDpi = key.getResources().getDisplayMetrics().densityDpi;
        fontScale = key.getResources().getConfiguration().fontScale;
        label = new TextView(key.getContext());
        label.setTextColor(Color.WHITE);
        label.setTextSize(30);
        label.setGravity(Gravity.CENTER);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff444446);
        background.setCornerRadius(dp(key, 8));
        label.setBackground(background);

        popup = new PopupWindow(label, 0, 0, false);
        popup.setTouchable(false);
        popup.setAnimationStyle(0);
        // Dismissal must release the label immediately so the next key can reuse it.
        popup.setEnterTransition(null);
        popup.setExitTransition(null);
        popup.setElevation(dp(key, 4));
    }

    void dismiss(View key) {
        if (key == owner) dismiss();
    }

    void dismiss() {
        setOwner(null);
        try {
            if (popup != null) popup.dismiss();
        } catch (IllegalArgumentException detached) {
            popup = null;
        } finally {
            if (label != null) label.setText(null);
            if (popup == null) label = null;
        }
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
