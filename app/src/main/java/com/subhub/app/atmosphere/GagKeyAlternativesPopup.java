package com.subhub.app.atmosphere;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A held key can slide into an option or release and tap it explicitly. */
final class GagKeyAlternativesPopup {
    private static final int PANEL_COLOR = 0xff363638;
    private static final int PANEL_BORDER = 0xff515156;
    private static final int HIGHLIGHT = 0xff5e97f6;
    private final View keyboard;
    private final ArrayList<Button> buttons = new ArrayList<>();
    private PopupWindow popup;
    private View heldKey;
    private Button highlighted;
    private boolean leftHeldKey;

    GagKeyAlternativesPopup(View keyboard) {
        this.keyboard = keyboard;
    }

    boolean show(View key, List<String> choices, Consumer<String> select) {
        dismiss();
        Rect window = new Rect();
        key.getWindowVisibleDisplayFrame(window);
        if (choices.isEmpty() || window.width() <= 0 || window.height() <= 0
                || !key.isAttachedToWindow() || !keyboard.isAttachedToWindow()) return false;
        int inset = Math.min(dp(4), (Math.min(window.width(), window.height()) - 1) / 2);
        int availableWidth = window.width() - 2 * inset;
        int availableHeight = window.height() - 2 * inset;
        int cell = Math.min(dp(48), availableWidth);
        int minimumColumns = Math.min(choices.size(), Math.max(1, availableWidth / cell));
        int rows = (choices.size() + minimumColumns - 1) / minimumColumns;
        if (availableHeight < rows) return false;
        int columns = (choices.size() + rows - 1) / rows;
        int heightPerRow = Math.min(dp(48), availableHeight / rows);
        int width = columns * cell + 2 * inset;
        int height = rows * heightPerRow + 2 * inset;
        LinearLayout content = new LinearLayout(key.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        content.setPadding(inset, inset, inset, inset);
        GradientDrawable background = surface(PANEL_COLOR, dp(14));
        background.setStroke(dp(1), PANEL_BORDER);
        for (int index = 0; index < choices.size(); ) {
            LinearLayout row = new LinearLayout(key.getContext());
            content.addView(row, new LinearLayout.LayoutParams(-1, heightPerRow));
            for (int column = 0; column < columns && index < choices.size(); column++, index++) {
                String choice = choices.get(index);
                Button button = new Button(key.getContext());
                button.setText(choice);
                button.setAllCaps(false);
                button.setTextSize(20);
                button.setGravity(Gravity.CENTER);
                button.setPadding(0, 0, 0, 0);
                button.setMinimumWidth(0);
                button.setMinWidth(0);
                styleOption(button);
                button.setOnClickListener(view -> {
                    if (!buttons.contains(view)) return;
                    dismiss();
                    select.accept(choice);
                });
                row.addView(button, new LinearLayout.LayoutParams(cell, -1));
                buttons.add(button);
            }
        }
        PopupWindow panel = new PopupWindow(content, width, height, false);
        panel.setBackgroundDrawable(background);
        panel.setOutsideTouchable(true);
        panel.setAnimationStyle(0);
        panel.setElevation(dp(6));
        int[] keyOnScreen = new int[2];
        key.getLocationOnScreen(keyOnScreen);
        int left = Math.max(window.left, Math.min(keyOnScreen[0] + (key.getWidth() - width) / 2,
                window.right - width));
        int top = Math.max(window.top, Math.min(keyOnScreen[1] - height - dp(4), window.bottom - height));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            panel.setIsLaidOutInScreen(true);
        } else {
            int[] keyInWindow = new int[2];
            key.getLocationInWindow(keyInWindow);
            left += keyInWindow[0] - keyOnScreen[0];
            top += keyInWindow[1] - keyOnScreen[1];
        }
        heldKey = key;
        popup = panel;
        panel.setOnDismissListener(() -> {
            if (popup == panel) clear();
        });
        try {
            panel.showAtLocation(keyboard, Gravity.TOP | Gravity.LEFT, left, top);
        } catch (WindowManager.BadTokenException detached) {
            dismiss();
            return false;
        }
        return true;
    }

    private void styleOption(Button button) {
        int[] selected = {android.R.attr.state_selected};
        int[] pressed = {android.R.attr.state_pressed};
        int[] ordinary = {};
        StateListDrawable backgrounds = new StateListDrawable();
        backgrounds.addState(selected, surface(HIGHLIGHT, dp(10)));
        backgrounds.addState(pressed, surface(HIGHLIGHT, dp(10)));
        backgrounds.addState(ordinary, surface(Color.TRANSPARENT, dp(10)));
        button.setBackground(backgrounds);
        button.setBackgroundTintList(null);
        button.setTextColor(new ColorStateList(new int[][]{selected, pressed, ordinary},
                new int[]{0xff101923, 0xff101923, 0xfff6f6f6}));
    }

    private static GradientDrawable surface(int color, int radius) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(color);
        surface.setCornerRadius(radius);
        return surface;
    }

    boolean isShowingFor(View key) { return key == heldKey && popup != null && popup.isShowing(); }

    void dismiss() {
        PopupWindow previous = popup;
        clear();
        if (previous != null) previous.dismiss();
    }

    private void clear() {
        popup = null;
        heldKey = null;
        highlighted = null;
        leftHeldKey = false;
        buttons.clear();
    }

    void highlight(View key, MotionEvent event, int pointer) {
        leftHeldKey |= !overKey(key, event, pointer);
        Button active = leftHeldKey ? optionAt(key, event, pointer) : null;
        if (active == highlighted) return;
        if (highlighted != null) highlighted.setSelected(false);
        highlighted = active;
        if (active != null) active.setSelected(true);
    }

    void release(View key, MotionEvent event, int pointer) {
        // Releasing a stationary hold leaves the panel available for explicit taps or accessibility.
        if (!leftHeldKey && overKey(key, event, pointer)) return;
        Button active = optionAt(key, event, pointer);
        if (active != null) active.performClick();
        else dismiss();
    }

    private static boolean overKey(View key, MotionEvent event, int pointer) {
        float x = event.getX(pointer), y = event.getY(pointer);
        return 0 <= x && x < key.getWidth() && 0 <= y && y < key.getHeight();
    }

    private Button optionAt(View key, MotionEvent event, int pointer) {
        int[] offset = new int[2];
        key.getLocationOnScreen(offset);
        float x = offset[0] + event.getX(pointer);
        float y = offset[1] + event.getY(pointer);
        for (Button button : buttons) {
            if (!button.isLaidOut()) continue;
            button.getLocationOnScreen(offset);
            if (offset[0] <= x && x < offset[0] + button.getWidth()
                    && offset[1] <= y && y < offset[1] + button.getHeight()) return button;
        }
        return null;
    }

    private int dp(int value) {
        return Math.round(value * keyboard.getResources().getDisplayMetrics().density);
    }
}
