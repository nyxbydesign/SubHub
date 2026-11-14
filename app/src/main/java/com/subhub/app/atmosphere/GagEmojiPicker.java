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
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

import com.subhub.app.R;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.function.Predicate;

/** In-keyboard picker that remembers only successfully inserted catalog entries. */
final class GagEmojiPicker extends LinearLayout {
    static final String PREFS = "subhub_gag_emoji";
    private static final String RECENT = "recent";
    private static final int MAX_RECENT = 32;
    private static final int LABEL_COLOR = 0xfff6f6f6;
    private static final int TAB_SURFACE = 0x88181818;
    private static final int SELECTED_TAB = 0xb3435f8e;
    private static final int EMOJI_SURFACE = 0x88565656;

    private final SharedPreferences preferences;
    private final GagEmojiCatalog catalog;
    private final Predicate<String> insert;
    private final List<GagEmojiCatalog.Entry> recent = new ArrayList<>(MAX_RECENT);
    private final List<Button> categoryButtons = new ArrayList<>();
    private final HorizontalScrollView categories;
    private final GridView grid;
    private final TextView empty;
    private final Tiles tiles = new Tiles();
    private final Button allTab;
    private final Button recentTab;
    private boolean showingRecent;
    private int category;

    GagEmojiPicker(Context context, SharedPreferences preferences, GagEmojiCatalog catalog,
            boolean compact, Predicate<String> insert, Runnable close) {
        super(context);
        this.preferences = preferences;
        this.catalog = catalog;
        this.insert = insert;
        StringTokenizer saved = new StringTokenizer(preferences.getString(RECENT, ""));
        while (saved.hasMoreTokens() && recent.size() < MAX_RECENT) {
            GagEmojiCatalog.Entry emoji = catalog.entry(saved.nextToken());
            if (emoji != null && !recent.contains(emoji)) recent.add(emoji);
        }
        setOrientation(VERTICAL);
        setPadding(dp(4), 0, dp(4), 0);

        LinearLayout tabs = new LinearLayout(context);
        addView(tabs, new LayoutParams(-1, dp(compact ? 38 : 48)));
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

        categories = new HorizontalScrollView(context);
        categories.setHorizontalScrollBarEnabled(false);
        addView(categories, new LayoutParams(-1, dp(38)));
        LinearLayout categoryRow = new LinearLayout(context);
        categories.addView(categoryRow, new HorizontalScrollView.LayoutParams(-2, -1));
        for (int index = 0; index < catalog.groups.size(); index++) {
            int selected = index;
            Button group = button(catalog.groups.get(index).name(), 13);
            group.setPadding(dp(9), 0, dp(9), 0);
            group.setOnClickListener(view -> {
                category = selected;
                showCategory();
            });
            categoryButtons.add(group);
            tabSlot(categoryRow, group, -2, 0);
        }

        grid = new GridView(context);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(48));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(3));
        grid.setVerticalSpacing(dp(3));
        grid.setAdapter(tiles);
        addView(grid, new LayoutParams(-1, 0, 1));
        empty = new TextView(context);
        empty.setText(R.string.gag_emoji_recent_empty);
        empty.setTextColor(LABEL_COLOR);
        empty.setPadding(dp(8), dp(12), dp(8), dp(12));
        addView(empty, new LayoutParams(-1, 0, 1));
        selectTab(true);
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

    private void tabSlot(LinearLayout row, Button button, int width, float weight) {
        LayoutParams slot = new LayoutParams(width, -1, weight);
        slot.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(button, slot);
        setSurface(button, TAB_SURFACE);
    }

    private void selectTab(boolean recentOnly) {
        showingRecent = recentOnly;
        select(allTab, !recentOnly);
        select(recentTab, recentOnly);
        categories.setVisibility(recentOnly ? GONE : VISIBLE);
        if (recentOnly) showEntries(recent);
        else showCategory();
    }

    private void showCategory() {
        for (int index = 0; index < categoryButtons.size(); index++)
            select(categoryButtons.get(index), index == category);
        showEntries(catalog.groups.get(category).entries());
    }

    private void showEntries(List<GagEmojiCatalog.Entry> values) {
        tiles.values = values;
        tiles.notifyDataSetChanged();
        grid.setVisibility(values.isEmpty() ? GONE : VISIBLE);
        empty.setVisibility(values.isEmpty() ? VISIBLE : GONE);
        grid.setSelection(0);
    }

    private void select(Button button, boolean selected) {
        button.setSelected(selected);
        setSurface(button, selected ? SELECTED_TAB : TAB_SURFACE);
    }

    private void choose(GagEmojiCatalog.Entry emoji) {
        if (!insert.test(emoji.sequence()) || (!recent.isEmpty() && recent.get(0).equals(emoji))) return;
        recent.remove(emoji);
        recent.add(0, emoji);
        if (recent.size() > MAX_RECENT) recent.remove(MAX_RECENT);
        StringBuilder saved = new StringBuilder();
        for (GagEmojiCatalog.Entry entry : recent) {
            if (saved.length() != 0) saved.append(' ');
            saved.append(entry.sequence());
        }
        preferences.edit().putString(RECENT, saved.toString()).apply();
        if (showingRecent) showEntries(recent);
    }

    private final class Tiles extends BaseAdapter {
        private List<GagEmojiCatalog.Entry> values = List.of();

        @Override public int getCount() { return values.size(); }
        @Override public GagEmojiCatalog.Entry getItem(int position) { return values.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Button tile;
            if (recycled instanceof Button previous) tile = previous;
            else {
                tile = button("", 23);
                tile.setLayoutParams(new GridView.LayoutParams(-1, dp(50)));
                setSurface(tile, EMOJI_SURFACE);
                tile.setOnClickListener(view -> choose((GagEmojiCatalog.Entry) view.getTag()));
            }
            GagEmojiCatalog.Entry entry = getItem(position);
            boolean supported = tile.getPaint().hasGlyph(entry.sequence());
            tile.setTag(entry);
            tile.setContentDescription(entry.name());
            tile.setText(supported ? entry.sequence() : entry.name());
            tile.setTextSize(supported ? 23 : 9);
            tile.setMaxLines(supported ? 1 : 4);
            return tile;
        }
    }

    private Button button(String label, int textSize) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextColor(LABEL_COLOR);
        button.setTextSize(textSize);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT);
        button.setGravity(Gravity.CENTER);
        button.setMaxLines(1);
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
