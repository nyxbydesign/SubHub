package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** In-keyboard picker that remembers only successfully inserted catalog entries. */
final class GagEmojiPicker extends LinearLayout {
    private static final int FREQUENT = -1;
    private static final int LABEL_COLOR = 0xfff6f6f6;
    private static final int MUTED_LABEL = 0xffdbdbe4;
    private static final int TAB_SURFACE = 0xff18181c;
    private static final int SELECTED_TAB = 0xff37476b;
    // The pinned catalog has a fixed group order. Labels come from the catalog.
    private static final List<String> GROUP_ICONS = List.of(
            "🙂", "👋", "🏽", "🐾", "🍉", "🚙", "⚽", "💡", "♫", "🏁");

    private final GagEmojiCatalog catalog;
    private final GagEmojiUsage usage;
    private final BooleanSupplier learning;
    private final Predicate<String> insert;
    private final List<Button> categoryButtons = new ArrayList<>();
    private final TextView heading;
    private final GridView grid;
    private final TextView empty;
    private final Tiles tiles = new Tiles();
    private final int tileSize;

    GagEmojiPicker(Context context, SharedPreferences preferences, GagEmojiCatalog catalog,
            boolean compact, BooleanSupplier learning, Predicate<String> insert, Runnable close) {
        super(context);
        this.catalog = catalog;
        this.usage = new GagEmojiUsage(preferences, catalog);
        this.learning = learning;
        this.insert = insert;
        tileSize = dp(compact ? 44 : 48);
        setOrientation(VERTICAL);

        heading = new TextView(context);
        heading.setTextColor(MUTED_LABEL);
        heading.setTextSize(14);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPaddingRelative(dp(12), 0, dp(8), 0);
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        ViewCompat.setAccessibilityHeading(heading, true);
        addView(heading, new LayoutParams(-1, dp(32)));

        grid = new GridView(context);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(tileSize);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(dp(4), 0, dp(4), 0);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setAdapter(tiles);
        addView(grid, new LayoutParams(-1, 0, 1));
        empty = new TextView(context);
        empty.setTextColor(LABEL_COLOR);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(12), 0, dp(12), 0);
        addView(empty, new LayoutParams(-1, 0, 1));

        LinearLayout tabs = new LinearLayout(context);
        tabs.setBackgroundColor(TAB_SURFACE);
        addView(tabs, new LayoutParams(-1, dp(48)));
        Button back = button("ABC", 14);
        back.setContentDescription(context.getString(R.string.gag_emoji_back_to_keyboard));
        back.setOnClickListener(view -> close.run());
        tabs.addView(back, new LayoutParams(dp(52), -1));
        setSurface(back, Color.TRANSPARENT);

        HorizontalScrollView categories = new HorizontalScrollView(context);
        categories.setHorizontalScrollBarEnabled(false);
        tabs.addView(categories, new LayoutParams(0, -1, 1));
        LinearLayout categoryRow = new LinearLayout(context);
        categories.addView(categoryRow, new HorizontalScrollView.LayoutParams(-2, -1));
        addCategory(categoryRow, "★", context.getString(R.string.gag_emoji_frequently_used), FREQUENT);
        for (int index = 0; index < catalog.groups.size(); index++) {
            String label = catalog.groups.get(index).name();
            String icon = index < GROUP_ICONS.size() ? GROUP_ICONS.get(index) : "•";
            addCategory(categoryRow, icon, label, index);
        }

        List<GagEmojiCatalog.Entry> frequent = frequentlyUsed();
        if (frequent.isEmpty()) selectCategory(0);
        else showCategory(FREQUENT, frequent);
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

    private void addCategory(LinearLayout row, String icon, String label, int category) {
        Button tab = button(icon, 20);
        tab.setContentDescription(label);
        tab.setOnClickListener(view -> selectCategory(category));
        categoryButtons.add(tab);
        row.addView(tab, new LayoutParams(dp(44), -1));
    }

    private List<GagEmojiCatalog.Entry> frequentlyUsed() {
        return learning.getAsBoolean() ? usage.frequentlyUsed() : List.of();
    }

    private void selectCategory(int category) {
        showCategory(category, category == FREQUENT
                ? frequentlyUsed() : catalog.groups.get(category).entries());
    }

    private void showCategory(int category, List<GagEmojiCatalog.Entry> values) {
        Button selected = categoryButtons.get(category + 1);
        for (Button tab : categoryButtons) {
            tab.setSelected(tab == selected);
            setSurface(tab, tab == selected ? SELECTED_TAB : Color.TRANSPARENT);
        }
        heading.setText(selected.getContentDescription());
        empty.setText(learning.getAsBoolean() ? R.string.gag_emoji_frequently_used_empty
                : R.string.gag_emoji_usage_paused);
        tiles.values = values;
        tiles.notifyDataSetChanged();
        grid.setVisibility(values.isEmpty() ? GONE : VISIBLE);
        empty.setVisibility(values.isEmpty() ? VISIBLE : GONE);
        grid.setSelection(0);
    }

    private void choose(GagEmojiCatalog.Entry emoji) {
        // Keep the visible grid stable for consecutive taps. Rank again on category entry.
        if (insert.test(emoji.sequence()) && learning.getAsBoolean()) usage.record(emoji.sequence());
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
                tile.setLayoutParams(new GridView.LayoutParams(-1, tileSize));
                setSurface(tile, Color.TRANSPARENT);
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
        face.setCornerRadius(dp(9));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(9));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x38ffffff), face, mask));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
