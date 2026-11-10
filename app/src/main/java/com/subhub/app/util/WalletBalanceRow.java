package com.subhub.app.util;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** Keeps secondary amounts readable without shrinking text on narrow or large-text screens. */
public final class WalletBalanceRow extends LinearLayout {
    private boolean stacked, measured;

    public WalletBalanceRow(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        boolean next =
                MeasureSpec.getSize(widthSpec) < dp(300)
                        || getResources().getConfiguration().fontScale > 1.4f;
        if (!measured || getOrientation() != (next ? VERTICAL : HORIZONTAL) || next != stacked) {
            measured = true;
            stacked = next;
            setOrientation(next ? VERTICAL : HORIZONTAL);
            for (int index = 0; index < getChildCount(); index++) {
                LayoutParams params = new LayoutParams(next ? -1 : 0, -2, next ? 0 : 1);
                if (next) params.bottomMargin = dp(8);
                else if (index > 0) params.setMarginStart(dp(8));
                getChildAt(index).setLayoutParams(params);
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
