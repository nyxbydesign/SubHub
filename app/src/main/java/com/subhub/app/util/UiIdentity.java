package com.subhub.app.util;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DimenRes;
import androidx.annotation.DrawableRes;
import androidx.core.view.ViewCompat;

import com.subhub.app.R;

/** Shared typography and spacing tokens for XML and dynamically created UI. */
public final class UiIdentity {
    private UiIdentity() {}

    public static void textSize(TextView view, @DimenRes int size) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.getResources().getDimensionPixelSize(size));
    }

    public static void sectionIcon(TextView view, int icon) {
        android.graphics.drawable.Drawable drawable = view.getContext().getDrawable(icon).mutate();
        int size = Math.round(24 * view.getResources().getDisplayMetrics().density);
        drawable.setBounds(0, 0, size, size);
        drawable.setTint(view.getContext().getColor(com.subhub.app.R.color.text_primary));
        view.setCompoundDrawablesRelative(drawable, null, null, null);
        view.setCompoundDrawablePadding(view.getResources().getDimensionPixelSize(com.subhub.app.R.dimen.ui_gap_control));
    }

    public static void inputType(android.widget.EditText view, int type) {
        android.graphics.Typeface typeface = view.getTypeface();
        view.setInputType(type);
        view.setTypeface(typeface);
    }

    public static void cardPadding(android.view.View view) {
        int side = view.getResources().getDimensionPixelSize(com.subhub.app.R.dimen.ui_card_padding);
        int vertical = view.getResources().getDimensionPixelSize(com.subhub.app.R.dimen.ui_card_padding_vertical);
        view.setPaddingRelative(side, vertical, side, vertical);
    }

    public static void addActionChevron(LinearLayout row) {
        Context context = row.getContext();
        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.ic_overview_chevron);
        arrow.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.text_secondary)));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(20 * context.getResources().getDisplayMetrics().density);
        row.addView(arrow, new LinearLayout.LayoutParams(size, size));
    }

    public static void bindClick(View view, Runnable action) {
        Context context = view.getContext();
        view.setFocusable(true);
        view.setClickable(true);
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        view.setForeground(context.getDrawable(ripple.resourceId));
        view.setOnClickListener(ignored -> action.run());
        ViewCompat.setScreenReaderFocusable(view, true);
    }

    public static LinearLayout addCard(LinearLayout parent, Context context, @DrawableRes int background) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(background);
        cardPadding(card);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        size.bottomMargin = context.getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        parent.addView(card, size);
        return card;
    }
}
