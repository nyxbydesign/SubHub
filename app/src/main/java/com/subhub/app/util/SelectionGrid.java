package com.subhub.app.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/** Independent choices in two equal columns, reflowing for narrow or enlarged text. */
public final class SelectionGrid extends LinearLayout {
    private int columns = 1;
    private int[] rowHeights = new int[0];

    public SelectionGrid(Context context, AttributeSet attributes) { super(context, attributes); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) {
            width = Math.round(320 * getResources().getDisplayMetrics().density);
        }
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int count = 0;
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) count++;
        int minimum = Math.round(132 * getResources().getDisplayMetrics().density
                * Math.max(1f, getResources().getConfiguration().fontScale));
        columns = available >= minimum * 2 ? 2 : 1;
        rowHeights = ChoiceGridLayout.measureRows(this, available, count, columns);
        int height = getPaddingTop() + getPaddingBottom();
        for (int row : rowHeights) height += row;
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        ChoiceGridLayout.layout(this, right - left, columns, rowHeights);
    }
}
