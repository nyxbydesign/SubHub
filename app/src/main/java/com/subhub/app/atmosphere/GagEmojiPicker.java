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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** In-keyboard emoji surface. Only explicitly chosen favorites are stored. */
final class GagEmojiPicker extends LinearLayout {
    static final String PREFS = "subhub_gag_emoji";
    private static final String FAVORITES = "favorites";
    private static final String[] EMOJI = (
            "😀 😃 😄 😁 😆 😅 😂 🤣 😊 🙂 🙃 😉 😌 😍 🥰 😘 😋 😛 😜 🤪 🤔 🤭 🫢 🤫 "
            + "😏 😒 🙄 😬 😴 😎 🤓 🥳 😐 😑 😕 🙁 😔 😢 😭 🥺 😤 😠 😳 🫠 🤯 😱 😮 "
            + "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤝 👏 🙌 🙏 👋 💪 👀 💅 🤷 🤦 🫶 "
            + "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 🩷 💔 💕 💖 💗 💞 💓 💌 "
            + "✨ ⭐ 🌟 💫 🔥 🎉 🎊 🎁 🎈 🏆 ✅ ❌ 💯 💡 🎶 🎵 📷 📚 💬 "
            + "🐶 🐱 🐻 🦊 🐰 🐼 🐨 🐸 🦋 🐝 🌸 🌹 🌻 🌷 🍀 🌈 ☀️ 🌙 ☁️ ☔ ❄️ "
            + "☕ 🍵 🍕 🍔 🍟 🌮 🍣 🍰 🎂 🍪 🍫 🍓 🍉 🍎 🍇 🥑 🍿 🥂 🎮 ⚽ 🏀 "
            + "🚀 ✈️ 🚗 🚲 🏠 🌍 ⏰ 📅 🌊 ⛰️ 🏖️ 🏕️").split(" ");
    private static final int LABEL_COLOR = 0xfff6f6f6;
    private static final int TAB_SURFACE = 0x88181818;
    private static final int SELECTED_TAB = 0xb3435f8e;
    private static final int EMOJI_SURFACE = 0x88565656;

    private final SharedPreferences preferences;
    private final Consumer<String> insert;
    private final Set<String> favorites;
    private final ScrollView scroll;
    private final LinearLayout grid;
    private final Button allTab;
    private final Button favoritesTab;
    private boolean showingFavorites;
    private int columns;

    GagEmojiPicker(Context context, SharedPreferences preferences, boolean compact,
            Consumer<String> insert, Runnable close) {
        super(context);
        this.preferences = preferences;
        this.insert = insert;
        favorites = new HashSet<>(preferences.getStringSet(FAVORITES, Set.of()));
        favorites.retainAll(List.of(EMOJI));
        setOrientation(VERTICAL);
        setPadding(dp(4), 0, dp(4), 0);

        LinearLayout tabs = new LinearLayout(context);
        int tabHeight = dp(compact ? 38 : 48);
        addView(tabs, new LayoutParams(-1, tabHeight));
        Button back = button("‹", 24);
        back.setContentDescription(context.getString(R.string.gag_emoji_back_to_keyboard));
        back.setOnClickListener(view -> close.run());
        tabSlot(tabs, back, dp(48), 0);
        allTab = button(context.getString(R.string.gag_emoji_tab), 14);
        allTab.setOnClickListener(view -> selectTab(false));
        tabSlot(tabs, allTab, 0, 1);
        favoritesTab = button(context.getString(R.string.gag_emoji_favorites), 14);
        favoritesTab.setOnClickListener(view -> selectTab(true));
        tabSlot(tabs, favoritesTab, 0, 1);
        scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        addView(scroll, new LayoutParams(-1, 0, 1));
        grid = new LinearLayout(context);
        grid.setOrientation(VERTICAL);
        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        selectTab(false);
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

    private void selectTab(boolean favoritesOnly) {
        showingFavorites = favoritesOnly;
        allTab.setSelected(!favoritesOnly);
        setSurface(allTab, favoritesOnly ? TAB_SURFACE : SELECTED_TAB);
        favoritesTab.setSelected(favoritesOnly);
        setSurface(favoritesTab, favoritesOnly ? SELECTED_TAB : TAB_SURFACE);
        scroll.scrollTo(0, 0);
        renderGrid();
    }

    private void renderGrid() {
        if (columns == 0) return;
        grid.removeAllViews();
        List<String> values = new ArrayList<>();
        for (String emoji : EMOJI) {
            if (!showingFavorites || favorites.contains(emoji)) values.add(emoji);
        }
        if (values.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(R.string.gag_emoji_favorites_empty);
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
            tile.setOnClickListener(view -> insert.accept(emoji));
            tile.setOnLongClickListener(view -> {
                toggleFavorite(tile, emoji);
                return true;
            });
            describeFavorite(tile, emoji);
            LayoutParams slot = new LayoutParams(0, -1, 1);
            slot.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(tile, slot);
        }
    }

    private void toggleFavorite(Button tile, String emoji) {
        boolean added = favorites.add(emoji);
        if (!added) favorites.remove(emoji);
        preferences.edit().putStringSet(FAVORITES, new HashSet<>(favorites)).apply();
        String notice = getContext().getString(added ? R.string.gag_emoji_added : R.string.gag_emoji_removed);
        announceForAccessibility(notice);
        if (showingFavorites) renderGrid();
        else describeFavorite(tile, emoji);
    }

    private void describeFavorite(Button tile, String emoji) {
        boolean favorite = favorites.contains(emoji);
        // The glyph text stays unmodified so Android can announce its natural emoji name.
        ViewCompat.setStateDescription(tile, favorite ? getContext().getString(R.string.gag_emoji_favorite) : null);
        ViewCompat.replaceAccessibilityAction(tile, AccessibilityActionCompat.ACTION_LONG_CLICK,
                getContext().getString(favorite ? R.string.gag_emoji_remove_favorite : R.string.gag_emoji_add_favorite),
                (view, arguments) -> {
                    toggleFavorite(tile, emoji);
                    return true;
                });
        setSurface(tile, favorite ? SELECTED_TAB : EMOJI_SURFACE);
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
