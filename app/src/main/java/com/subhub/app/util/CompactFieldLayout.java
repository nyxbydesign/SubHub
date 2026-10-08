package com.subhub.app.util;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/** Two-column form flow: long fields span both columns and hidden fields leave no holes. */
public final class CompactFieldLayout extends ViewGroup {
    private final List<View> visible = new ArrayList<>();
    private final List<Integer> spans = new ArrayList<>();
    private int columns = 1;
    private int gap;

    public CompactFieldLayout(Context context) { super(context); }

    public void addField(View field, boolean fullWidth) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.fullWidth = fullWidth;
        addView(field, params);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        float density = getResources().getDisplayMetrics().density;
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) width = Math.round(320 * density);
        gap = Math.round(8 * density);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int minimum = Math.round(120 * density * Math.max(1f, getResources().getConfiguration().fontScale));
        columns = available >= minimum * 2 + gap ? 2 : 1;
        int cell = (available - gap * (columns - 1)) / columns;
        visible.clear(); spans.clear();
        int used = 0, rowHeight = 0, height = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int span = params.fullWidth ? columns : 1;
            if (used + span > columns) { height += rowHeight + gap; used = 0; rowHeight = 0; }
            child.measure(MeasureSpec.makeMeasureSpec(span == columns ? available : cell, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            visible.add(child); spans.add(span);
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            used += span;
            if (used == columns) { height += rowHeight + gap; used = 0; rowHeight = 0; }
        }
        height += rowHeight;
        if (used == 0 && !visible.isEmpty()) height -= gap;
        height += getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = Math.max(0, right - left - getPaddingLeft() - getPaddingRight());
        int cell = (available - gap * (columns - 1)) / columns;
        int used = 0, y = getPaddingTop(), rowHeight = 0;
        for (int i = 0; i < visible.size(); i++) {
            View child = visible.get(i);
            int span = spans.get(i);
            if (used + span > columns) { y += rowHeight + gap; used = 0; rowHeight = 0; }
            int x = getPaddingLeft() + used * (cell + gap);
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) x = getPaddingLeft() + available - (x - getPaddingLeft()) - child.getMeasuredWidth();
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            used += span;
            if (used == columns) { y += rowHeight + gap; used = 0; rowHeight = 0; }
        }
    }

    public static final class LayoutParams extends ViewGroup.LayoutParams {
        boolean fullWidth;
        public LayoutParams(int width, int height) { super(width, height); }
    }
}
