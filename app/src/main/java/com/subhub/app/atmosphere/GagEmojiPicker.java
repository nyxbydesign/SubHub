package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

import com.subhub.app.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.function.Predicate;

/** In-keyboard picker that remembers only successfully inserted catalog entries. */
final class GagEmojiPicker extends LinearLayout {
    static final String PREFS = "subhub_gag_emoji";
    private static final String RECENT = "recent";
    private static final int MAX_RECENT = 32;
    private static final List<String> EMOJI = List.of((
            "😀 😃 😄 😁 😆 😅 😂 🤣 😊 🙂 🙃 😉 😌 😍 🥰 😘 😋 😛 😜 🤪 🤔 🤭 🫢 🤫 "
            + "😏 😒 🙄 😬 😴 😎 🤓 🥳 😐 😑 😕 🙁 😔 😢 😭 🥺 😤 😠 😳 🫠 🤯 😱 😮 "
            + "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤝 👏 🙌 🙏 👋 💪 👀 💅 🤷 🤦 🫶 "
            + "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 🩷 💔 💕 💖 💗 💞 💓 💌 "
            + "✨ ⭐ 🌟 💫 🔥 🎉 🎊 🎁 🎈 🏆 ✅ ❌ 💯 💡 🎶 🎵 📷 📚 💬 "
            + "🐶 🐱 🐻 🦊 🐰 🐼 🐨 🐸 🦋 🐝 🌸 🌹 🌻 🌷 🍀 🌈 ☀️ 🌙 ☁️ ☔ ❄️ "
            + "☕ 🍵 🍕 🍔 🍟 🌮 🍣 🍰 🎂 🍪 🍫 🍓 🍉 🍎 🍇 🥑 🍿 🥂 🎮 ⚽ 🏀 "
            + "🚀 ✈️ 🚗 🚲 🏠 🌍 ⏰ 📅 🌊 ⛰️ 🏖️ 🏕️").split(" "));
    private static final Set<String> CATALOG = Set.copyOf(EMOJI);
    private static final int LABEL_COLOR = 0xfff6f6f6;
    private static final int TAB_SURFACE = 0x88181818;
    private static final int SELECTED_TAB = 0xb3435f8e;
    private static final int EMOJI_SURFACE = 0x88565656;

    private final SharedPreferences preferences;
    private final Predicate<String> insert;
    private final List<String> recent = new ArrayList<>(MAX_RECENT);
    private final ScrollView scroll;
    private final LinearLayout grid;
    private final Button allTab;
    private final Button recentTab;
    private boolean showingRecent;
    private int columns;

    GagEmojiPicker(Context context, SharedPreferences preferences, boolean compact,
            Predicate<String> insert, Runnable close) {
        super(context);
        this.preferences = preferences;
        this.insert = insert;
        StringTokenizer saved = new StringTokenizer(preferences.getString(RECENT, ""));
        while (saved.hasMoreTokens() && recent.size() < MAX_RECENT) {
            String emoji = saved.nextToken();
            if (CATALOG.contains(emoji) && !recent.contains(emoji)) recent.add(emoji);
        }
        setOrientation(VERTICAL);
        setPadding(dp(4), 0, dp(4), 0);

        LinearLayout tabs = new LinearLayout(context);
        int tabHeight = dp(compact ? 38 : 48);
        addView(tabs, new LayoutParams(-1, tabHeight));
        Button back = button("‹", 24);
        back.setContentDescription(context.getString(R.string.gag_emoji_back_to_keyboard));
        back.setOnClickListener(view -> close.run());
        tabSlot(tabs, back, dp(48), 0);
        recentTab = button(context.getString(R.string.gag_emoji_recent), 14);
        recentTab.setOnClickListener(view -> selectTab(true));
        tabSlot(tabs, recentTab, 0, 1);
        allTab = button(context.getString(R.string.gag_emoji_tab), 14);
        allTab.setOnClickListener(view -> selectTab(false));
        tabSlot(tabs, allTab, 0, 1);
        scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        addView(scroll, new LayoutParams(-1, 0, 1));
        grid = new LinearLayout(context);
        grid.setOrientation(VERTICAL);
        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        selectTab(true);
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int count = Math.max(1, Math.min(10, (right - left) / dp(48)));
            if (columns != count) {
                columns = count;
                // Keep child creation out of the current layout traversal.
                post(this::renderGrid);
            }
        });
    }

    static void bindComma(Button comma, Runnable open) {
        Context context = comma.getContext();
        comma.setContentDescription(context.getText(R.string.gag_keyboard_comma_hint));
        comma.setOnLongClickListener(view -> {
            open.run();
            return true;
        });
        ViewCompat.replaceAccessibilityAction(comma, AccessibilityActionCompat.ACTION_LONG_CLICK,
                context.getText(R.string.gag_emoji_open), (view, arguments) -> {
                    open.run();
                    return true;
                });
    }

    private void tabSlot(LinearLayout tabs, Button button, int width, float weight) {
        LayoutParams slot = new LayoutParams(width, -1, weight);
        slot.setMargins(dp(2), dp(2), dp(2), dp(2));
        tabs.addView(button, slot);
        setSurface(button, TAB_SURFACE);
    }

    private void selectTab(boolean recentOnly) {
        showingRecent = recentOnly;
        allTab.setSelected(!recentOnly);
        setSurface(allTab, recentOnly ? TAB_SURFACE : SELECTED_TAB);
        recentTab.setSelected(recentOnly);
        setSurface(recentTab, recentOnly ? SELECTED_TAB : TAB_SURFACE);
        scroll.scrollTo(0, 0);
        renderGrid();
    }

    private void renderGrid() {
        if (columns == 0) return;
        grid.removeAllViews();
        List<String> values = showingRecent ? recent : EMOJI;
        if (values.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(R.string.gag_emoji_recent_empty);
            empty.setTextColor(LABEL_COLOR);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            grid.addView(empty, new LayoutParams(-1, -2));
            return;
        }
        LinearLayout row = null;
        for (int index = 0; index < values.size(); index++) {
            if (index % columns == 0) {
                row = new LinearLayout(getContext());
                row.setWeightSum(columns);
                grid.addView(row, new LayoutParams(-1, dp(49)));
            }
            String emoji = values.get(index);
            Button tile = button(emoji, 23);
            tile.setOnClickListener(view -> choose(emoji));
            setSurface(tile, EMOJI_SURFACE);
            LayoutParams slot = new LayoutParams(0, -1, 1);
            slot.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(tile, slot);
        }
    }

    private void choose(String emoji) {
        if (!insert.test(emoji) || (!recent.isEmpty() && recent.get(0).equals(emoji))) return;
        recent.remove(emoji);
        recent.add(0, emoji);
        if (recent.size() > MAX_RECENT) recent.remove(MAX_RECENT);
        preferences.edit().putString(RECENT, String.join(" ", recent)).apply();
        if (showingRecent) renderGrid();
    }

    private Button button(String label, int textSize) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextColor(LABEL_COLOR);
        button.setTextSize(textSize);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinimumHeight(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        return button;
    }

    private void setSurface(View view, int color) {
        GradientDrawable face = new GradientDrawable();
        face.setColor(color);
        face.setCornerRadius(dp(7));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x38ffffff), face, null));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
