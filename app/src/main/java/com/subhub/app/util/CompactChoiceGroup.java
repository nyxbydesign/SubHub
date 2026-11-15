package com.subhub.app.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RadioGroup;
import java.util.regex.Pattern;

/** Radio semantics with equal-width wrapping and equal-height cards within each row. */
public final class CompactChoiceGroup extends RadioGroup {
    private static final Pattern WORD_SPACE = Pattern.compile("\\s+");
    private final ChoiceGridLayout grid = new ChoiceGridLayout();
    private int preferredColumns, minimumCellWidthDp = 88;

    public CompactChoiceGroup(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    public void setPreferredColumns(int count, int minimumWidthDp) {
        if (count < 1 || minimumWidthDp < 48)
            throw new IllegalArgumentException("Invalid choice layout");
        preferredColumns = count;
        minimumCellWidthDp = minimumWidthDp;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = ChoiceGridLayout.width(this, widthSpec);
        int available = ChoiceGridLayout.availableWidth(this, width);
        int count = ChoiceGridLayout.visibleCount(this);
        int minimum = ChoiceGridLayout.minimumWidth(this, minimumCellWidthDp);
        // Preserve whole words when labels need more width than the base cell size.
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof android.widget.TextView) || child.getVisibility() == GONE) continue;
            android.widget.TextView label = (android.widget.TextView) child;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int insets = label.getCompoundPaddingLeft() + label.getCompoundPaddingRight()
                    + params.leftMargin + params.rightMargin;
            for (String word : WORD_SPACE.split(label.getText().toString()))
                minimum = Math.max(minimum, (int) Math.ceil(label.getPaint().measureText(word)) + insets);
        }
        int desired =
                preferredColumns > 0
                        ? Math.min(preferredColumns, count)
                        : count == 4 ? 2 : Math.min(3, count);
        int columns = Math.max(1, Math.min(desired, available / Math.max(1, minimum)));
        int height = grid.measure(this, available, count, columns);
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        grid.layout(this, right - left);
    }
}
