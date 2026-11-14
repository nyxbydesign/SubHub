package com.subhub.app.atmosphere;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A held key can slide into an option or release and tap it explicitly. */
final class GagKeyAlternativesPopup {
    private final View keyboard;
    private final ArrayList<Button> buttons = new ArrayList<>();
    private PopupWindow popup;
    private View heldKey;
    private Button highlighted;

    GagKeyAlternativesPopup(View keyboard) {
        this.keyboard = keyboard;
    }

    boolean show(View key, List<String> choices, Consumer<String> select) {
        dismiss();
        Rect window = new Rect();
        key.getWindowVisibleDisplayFrame(window);
        if (window.width() <= 0 || window.height() <= 0 || !keyboard.isAttachedToWindow()) return false;
        int cell = Math.min(dp(48), window.width());
        int minimumColumns = Math.min(choices.size(), Math.max(1, window.width() / cell));
        int rows = (choices.size() + minimumColumns - 1) / minimumColumns;
        int columns = (choices.size() + rows - 1) / rows;
        int heightPerRow = Math.min(dp(48), Math.max(1, window.height() / rows));
        int width = columns * cell;
        int height = rows * heightPerRow;
        LinearLayout content = new LinearLayout(key.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff323232);
        background.setCornerRadius(dp(8));
        for (int index = 0; index < choices.size(); ) {
            LinearLayout row = new LinearLayout(key.getContext());
            content.addView(row, new LinearLayout.LayoutParams(-1, heightPerRow));
            for (int column = 0; column < columns && index < choices.size(); column++, index++) {
                String choice = choices.get(index);
                Button button = new Button(key.getContext());
                button.setText(choice);
                button.setAllCaps(false);
                button.setTextColor(Color.WHITE);
                button.setTextSize(20);
                button.setPadding(0, 0, 0, 0);
                button.setMinimumWidth(0);
                button.setMinWidth(0);
                button.setBackgroundColor(Color.TRANSPARENT);
                button.setOnClickListener(view -> {
                    dismiss();
                    select.accept(choice);
                });
                row.addView(button, new LinearLayout.LayoutParams(cell, -1));
                buttons.add(button);
            }
        }
        heldKey = key;
        popup = new PopupWindow(content, width, height, false);
        popup.setBackgroundDrawable(background);
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> {
            popup = null;
            heldKey = null;
            highlighted = null;
            buttons.clear();
        });

        int[] keyOnScreen = new int[2];
        key.getLocationOnScreen(keyOnScreen);
        int left = Math.max(window.left, Math.min(keyOnScreen[0] + (key.getWidth() - width) / 2,
                window.right - width));
        int top = Math.max(window.top, Math.min(keyOnScreen[1] - height, window.bottom - height));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            popup.setIsLaidOutInScreen(true);
            popup.showAtLocation(keyboard, Gravity.TOP | Gravity.LEFT, left, top);
        } else {
            int[] keyInWindow = new int[2];
            key.getLocationInWindow(keyInWindow);
            popup.showAtLocation(keyboard, Gravity.TOP | Gravity.LEFT,
                    left + keyInWindow[0] - keyOnScreen[0], top + keyInWindow[1] - keyOnScreen[1]);
        }
        return true;
    }

    boolean isShowingFor(View key) { return key == heldKey && popup != null && popup.isShowing(); }

    void dismiss() {
        if (popup != null) popup.dismiss();
        popup = null;
        heldKey = null;
        highlighted = null;
        buttons.clear();
    }

    void highlight(View key, MotionEvent event, int pointer) {
        Button active = optionAt(key, event, pointer);
        if (active == highlighted) return;
        if (highlighted != null) highlighted.setBackgroundColor(Color.TRANSPARENT);
        highlighted = active;
        if (active != null) active.setBackgroundColor(0xff606060);
    }

    void release(View key, MotionEvent event, int pointer) {
        Button active = optionAt(key, event, pointer);
        if (active != null) active.performClick();
        else highlight(key, event, pointer);
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
