package com.subhub.app.atmosphere;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

import com.subhub.app.R;

import java.util.ArrayList;
import java.util.Comparator;
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
    private final List<GagEmojiCatalog.Group> groups;
    private final GagEmojiUsage usage;
    private final BooleanSupplier learning;
    private final Predicate<String> insert;
    private final List<Button> categoryButtons = new ArrayList<>();
    private final HorizontalScrollView categories;
    private final ListView grid;
    private final Tiles tiles;
    private final int tileSize;
    private List<GagEmojiCatalog.Entry> frequent;
    private int activeCategory = Integer.MIN_VALUE;
    private boolean updateFrequentWhenIdle;

    GagEmojiPicker(Context context, SharedPreferences preferences, GagEmojiCatalog catalog,
            boolean compact, BooleanSupplier learning, Predicate<String> insert, Runnable close) {
        super(context);
        groups = new ArrayList<>(catalog.groups);
        // Keep standalone modifiers available after the main browsing categories.
        groups.sort(Comparator.comparing(group -> group.name().equals("Component")));
        this.usage = new GagEmojiUsage(preferences, catalog);
        this.learning = learning;
        this.insert = insert;
        tiles = new Tiles();
        tileSize = dp(compact ? 44 : 48);
        setOrientation(VERTICAL);

        categories = new HorizontalScrollView(context);
        categories.setBackgroundColor(TAB_SURFACE);
        categories.setHorizontalScrollBarEnabled(false);
        addView(categories, new LayoutParams(-1, dp(48)));
        LinearLayout categoryRow = new LinearLayout(context);
        categories.addView(categoryRow, new HorizontalScrollView.LayoutParams(-2, -1));
        addCategory(categoryRow, "🕘", context.getString(R.string.gag_emoji_frequently_used), FREQUENT);
        for (int index = 0; index < groups.size(); index++) {
            String label = groups.get(index).name();
            addCategory(categoryRow, categoryIcon(label), label, index);
        }

        frequent = frequentlyUsed();
        grid = new ListView(context);
        grid.setDivider(null);
        grid.setItemsCanFocus(true);
        grid.setPadding(dp(4), 0, dp(4), 0);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        tiles.rebuild(columnsFor(getResources().getDisplayMetrics().widthPixels));
        grid.setAdapter(tiles);
        addView(grid, new LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(context);
        footer.setBackgroundColor(TAB_SURFACE);
        Button back = button("ABC", 14);
        back.setContentDescription(context.getString(R.string.gag_emoji_back_to_keyboard));
        back.setOnClickListener(view -> close.run());
        setSurface(back, Color.TRANSPARENT);
        footer.addView(back, new LayoutParams(dp(52), -1));
        addView(footer, new LayoutParams(-1, dp(48)));

        grid.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int columns = columnsFor(right - left);
            if (columns != tiles.columns) {
                int category = activeCategory;
                tiles.rebuild(columns);
                jumpToCategory(category);
            }
        });
        jumpToCategory(frequent.isEmpty() ? 0 : FREQUENT);
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (state != SCROLL_STATE_IDLE || !updateFrequentWhenIdle || grid.getChildCount() == 0) return;
                updateFrequentWhenIdle = false;
                int position = grid.getFirstVisiblePosition();
                int top = grid.getChildAt(0).getTop();
                if (refreshFrequent()) grid.setSelectionFromTop(position, top);
            }

            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (visible == 0 || first >= tiles.getCount()) return;
                int category = tiles.getItem(first).category();
                if (category == activeCategory) return;
                updateFrequentWhenIdle = category == FREQUENT;
                highlightCategory(category);
            }
        });
    }

    private static String categoryIcon(String name) {
        return switch (name) {
            case "Smileys & Emotion" -> "☺️";
            case "People & Body" -> "🧍";
            case "Animals & Nature" -> "🐝";
            case "Food & Drink" -> "☕";
            case "Travel & Places" -> "🚗";
            case "Activities" -> "🏆";
            case "Objects" -> "💡";
            case "Symbols" -> "🔣";
            case "Flags" -> "🏁";
            case "Component" -> "🏽";
            default -> "•";
        };
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
        tab.setOnClickListener(view -> {
            if (category == FREQUENT) refreshFrequent();
            jumpToCategory(category);
        });
        categoryButtons.add(tab);
        row.addView(tab, new LayoutParams(dp(44), -1));
    }

    private List<GagEmojiCatalog.Entry> frequentlyUsed() {
        return learning.getAsBoolean() ? usage.frequentlyUsed() : List.of();
    }

    private int columnsFor(int width) {
        return Math.max(1, (width - grid.getPaddingLeft() - grid.getPaddingRight()) / tileSize);
    }

    private List<GagEmojiCatalog.Entry> entries(int category) {
        return category == FREQUENT ? frequent : groups.get(category).entries();
    }

    private boolean refreshFrequent() {
        List<GagEmojiCatalog.Entry> updated = frequentlyUsed();
        if (updated.equals(frequent)) return false;
        frequent = updated;
        tiles.rebuild(tiles.columns);
        return true;
    }

    private void jumpToCategory(int category) {
        updateFrequentWhenIdle = false;
        highlightCategory(category);
        grid.setSelectionFromTop(tiles.firstRow[category + 1], 0);
    }

    private void highlightCategory(int category) {
        if (activeCategory == category) return;
        activeCategory = category;
        Button selected = categoryButtons.get(category + 1);
        for (Button tab : categoryButtons) {
            tab.setSelected(tab == selected);
            setSurface(tab, tab == selected ? SELECTED_TAB : Color.TRANSPARENT);
        }
        categories.post(() -> {
            if (activeCategory != category) return;
            View row = (View) selected.getParent();
            Rect bounds = new Rect(selected.getLeft(), 0, selected.getRight(), selected.getHeight());
            categories.requestChildRectangleOnScreen(row, bounds, false);
        });
    }

    private void choose(GagEmojiCatalog.Entry emoji) {
        // Keep the visible grid stable for consecutive taps. Rank again on category entry.
        if (insert.test(emoji.sequence()) && learning.getAsBoolean()) usage.record(emoji.sequence());
    }

    private record Row(int category, int kind, int firstEntry) {}

    private final class Tiles extends BaseAdapter {
        private static final int HEADING = 0;
        private static final int EMPTY = 1;
        private static final int EMOJI = 2;
        private final int[] firstRow = new int[groups.size() + 1];
        private final List<Row> rows = new ArrayList<>();
        private int columns;

        private void rebuild(int columns) {
            this.columns = columns;
            rows.clear();
            for (int category = FREQUENT; category < groups.size(); category++) {
                firstRow[category + 1] = rows.size();
                rows.add(new Row(category, HEADING, 0));
                List<GagEmojiCatalog.Entry> values = entries(category);
                if (values.isEmpty()) rows.add(new Row(category, EMPTY, 0));
                for (int first = 0; first < values.size(); first += columns)
                    rows.add(new Row(category, EMOJI, first));
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int position) { return getItem(position).kind(); }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Row row = getItem(position);
            if (row.kind() == EMOJI) return emojiRow(row, recycled);
            TextView label = recycled instanceof TextView previous ? previous : new TextView(getContext());
            label.setTextSize(14);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setPaddingRelative(dp(8), 0, dp(4), 0);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            if (row.kind() == HEADING) {
                label.setText(categoryButtons.get(row.category() + 1).getContentDescription());
                label.setTextColor(MUTED_LABEL);
                label.setLayoutParams(new AbsListView.LayoutParams(-1, dp(32)));
                ViewCompat.setAccessibilityHeading(label, true);
            } else {
                label.setText(learning.getAsBoolean() ? R.string.gag_emoji_frequently_used_empty
                        : R.string.gag_emoji_usage_paused);
                label.setTextColor(LABEL_COLOR);
                label.setLayoutParams(new AbsListView.LayoutParams(-1, tileSize));
            }
            return label;
        }

        private View emojiRow(Row row, View recycled) {
            LinearLayout cells = recycled instanceof LinearLayout previous ? previous : new LinearLayout(getContext());
            cells.setLayoutParams(new AbsListView.LayoutParams(-1, tileSize));
            while (cells.getChildCount() > columns) cells.removeViewAt(cells.getChildCount() - 1);
            while (cells.getChildCount() < columns) {
                Button tile = button("", 23);
                setSurface(tile, Color.TRANSPARENT);
                tile.setOnClickListener(view -> choose((GagEmojiCatalog.Entry) view.getTag()));
                cells.addView(tile, new LayoutParams(0, -1, 1));
            }
            List<GagEmojiCatalog.Entry> values = entries(row.category());
            for (int column = 0; column < columns; column++) {
                Button tile = (Button) cells.getChildAt(column);
                int entryIndex = row.firstEntry() + column;
                tile.setVisibility(entryIndex < values.size() ? VISIBLE : INVISIBLE);
                if (entryIndex >= values.size()) continue;
                GagEmojiCatalog.Entry entry = values.get(entryIndex);
                boolean supported = tile.getPaint().hasGlyph(entry.sequence());
                tile.setTag(entry);
                tile.setContentDescription(entry.name());
                tile.setText(supported ? entry.sequence() : entry.name());
                tile.setTextSize(supported ? 23 : 9);
                tile.setMaxLines(supported ? 1 : 4);
            }
            return cells;
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
