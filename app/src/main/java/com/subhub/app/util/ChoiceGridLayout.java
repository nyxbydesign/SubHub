package com.subhub.app.util;

import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import java.util.Arrays;

/** Equal-width cells and equal-height siblings for compact choice layouts. */
final class ChoiceGridLayout {
    private int columns = 1;
    private int[] rowHeights = new int[0];

    static int width(ViewGroup parent, int widthSpec) {
        return MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? Math.round(320 * parent.getResources().getDisplayMetrics().density)
                : MeasureSpec.getSize(widthSpec);
    }

    static int availableWidth(ViewGroup parent, int width) {
        return Math.max(0, width - parent.getPaddingLeft() - parent.getPaddingRight());
    }

    static int visibleCount(ViewGroup parent) {
        int count = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i).getVisibility() != View.GONE) count++;
        }
        return count;
    }

    static int minimumWidth(ViewGroup parent, int dp) {
        return Math.round(dp * parent.getResources().getDisplayMetrics().density
                * Math.max(1f, parent.getResources().getConfiguration().fontScale));
    }

    int measure(ViewGroup parent, int available, int count, int columns) {
        this.columns = columns;
        int rows = (count + columns - 1) / columns;
        if (rowHeights.length == rows) Arrays.fill(rowHeights, 0);
        else rowHeights = new int[rows];
        int cell = available / columns;
        int visible = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(0,
                    cell - params.leftMargin - params.rightMargin), MeasureSpec.EXACTLY),
                    ViewGroup.getChildMeasureSpec(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                            0, params.height));
            int row = visible++ / columns;
            rowHeights[row] = Math.max(rowHeights[row],
                    child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
        }
        // Stretch siblings to the tallest naturally measured child in their row.
        visible = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            int row = visible++ / columns;
            child.measure(MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, rowHeights[row]
                            - params.topMargin - params.bottomMargin), MeasureSpec.EXACTLY));
        }
        int height = parent.getPaddingTop() + parent.getPaddingBottom();
        for (int row : rowHeights) height += row;
        return height;
    }

    void layout(ViewGroup parent, int width) {
        int cell = availableWidth(parent, width) / columns;
        int y = parent.getPaddingTop();
        int visible = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            int row = visible / columns;
            int column = visible % columns;
            if (column == 0 && row > 0) y += rowHeights[row - 1];
            if (parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) column = columns - 1 - column;
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            int x = parent.getPaddingLeft() + column * cell + params.leftMargin;
            int childY = y + params.topMargin;
            child.layout(x, childY, x + child.getMeasuredWidth(), childY + child.getMeasuredHeight());
            visible++;
        }
    }
}
