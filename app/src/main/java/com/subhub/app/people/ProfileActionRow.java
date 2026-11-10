package com.subhub.app.people;

import android.content.Context;
import android.text.Layout;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;

/** A profile action pair fits equal columns only when both complete labels fit. */
final class ProfileActionRow extends LinearLayout {
    private View topAction, bottomAction;

    ProfileActionRow(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBaselineAligned(false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (getChildCount() != 2) { super.onMeasure(widthSpec, heightSpec); return; }
        if (topAction == null) { topAction = getChildAt(0); bottomAction = getChildAt(1); }
        int available = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int gap = getResources().getDimensionPixelSize(R.dimen.ui_gap_inline);
        int required = 0, visible = 0;
        for (int index = 0; index < getChildCount(); index++) {
            TextView button = (TextView) getChildAt(index);
            if (button.getVisibility() == GONE) continue;
            visible++;
            CharSequence label = button.getText();
            if (button.getTransformationMethod() != null)
                label = button.getTransformationMethod().getTransformation(label, button);
            required = Math.max(required, Math.max(button.getMinimumWidth(),
                    (int) Math.ceil(Layout.getDesiredWidth(label, button.getPaint()))
                            + button.getCompoundPaddingLeft() + button.getCompoundPaddingRight()));
        }
        boolean horizontal = visible == 2 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
                && available >= 2 * required + gap;
        setOrientation(horizontal ? HORIZONTAL : VERTICAL);
        // Preserve vertical reading order. In a row, the former bottom action precedes the top one.
        View last = horizontal ? topAction : bottomAction;
        if (getChildAt(1) != last) bringChildToFront(last);
        for (int index = 0; index < getChildCount(); index++) {
            View button = getChildAt(index);
            LayoutParams params = (LayoutParams) button.getLayoutParams();
            int width = horizontal ? 0 : LayoutParams.MATCH_PARENT;
            int start = horizontal && index > 0 ? gap : 0;
            float weight = horizontal ? 1f : 0f;
            if (params.width != width || params.weight != weight || params.getMarginStart() != start
                    || params.getMarginEnd() != 0) {
                params.width = width;
                params.weight = weight;
                params.setMarginStart(start);
                params.setMarginEnd(0);
                button.setLayoutParams(params);
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
