package com.subhub.app.util;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** Independent choices in two equal columns, reflowing for narrow or enlarged text. */
public final class SelectionGrid extends LinearLayout {
    private final ChoiceGridLayout grid = new ChoiceGridLayout();

    public SelectionGrid(Context context, AttributeSet attributes) { super(context, attributes); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = ChoiceGridLayout.width(this, widthSpec);
        int available = ChoiceGridLayout.availableWidth(this, width);
        int columns = available >= ChoiceGridLayout.minimumWidth(this, 132) * 2 ? 2 : 1;
        int height = grid.measure(this, available, ChoiceGridLayout.visibleCount(this), columns);
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        grid.layout(this, right - left);
    }
}
