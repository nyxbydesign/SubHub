package com.subhub.app.people;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import java.util.ArrayList;
import java.util.List;

/** Compact native composition used by the two overviews and the Sub's Contract. */
public final class OverviewUi {
    private OverviewUi() { }

    public static LinearLayout card(LinearLayout parent) {
        LinearLayout card = PersonUi.column(parent.getContext());
        card.setBackgroundResource(R.drawable.bg_overview_card);
        com.subhub.app.util.UiIdentity.cardPadding(card);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = parent.getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        parent.addView(card, size);
        return card;
    }

    public static LinearLayout compactCard(LinearLayout parent) {
        LinearLayout card = card(parent);
        card.setPadding(dp(parent.getContext(), 14), dp(parent.getContext(), 4),
                dp(parent.getContext(), 14), dp(parent.getContext(), 4));
        return card;
    }

    public static TextView text(Context context, CharSequence text, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text); view.setTextSize(size); view.setIncludeFontPadding(false);
        view.setTextColor(context.getColor(bold ? R.color.text_primary : R.color.text_secondary));
        view.setLineSpacing(dp(context, 2), 1);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    public static void heading(LinearLayout parent, int title, int actionTitle, Runnable action) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(context, context.getString(title), 16, true);
        ViewCompat.setAccessibilityHeading(label, true);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView link = text(context, context.getString(actionTitle), 12, false);
            link.setTextColor(context.getColor(R.color.accent_text));
            android.graphics.drawable.Drawable arrow = context.getDrawable(R.drawable.ic_overview_chevron).mutate();
            arrow.setBounds(0, 0, dp(context, 20), dp(context, 20));
            arrow.setTint(context.getColor(R.color.accent_text));
            link.setCompoundDrawablesRelative(null, null, arrow, null);
            link.setCompoundDrawablePadding(dp(context, 4));
            link.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); link.setMinHeight(dp(context, 48));
            link.setMinWidth(dp(context, 48));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
            size.setMarginStart(dp(context, 10)); row.addView(link, size); click(link, action);
        } else row.setMinimumHeight(dp(context, 28));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    public static Identity identity(LinearLayout parent, ProfileData profile, boolean dom, String prefix) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        View avatar = ProfileViews.avatar(context, profile); avatar.setTag(prefix + "_avatar");
        row.addView(avatar, new LinearLayout.LayoutParams(dp(context, 56), dp(context, 56)));
        LinearLayout copy = PersonUi.column(context);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMarginStart(dp(context, 14)); row.addView(copy, size);
        TextView name = text(context, profile.name, 20, true); name.setTag(prefix + "_name");
        copy.addView(name, new LinearLayout.LayoutParams(-1, -2));
        List<String> values = new ArrayList<>();
        if (profile.isSwitch) values.add(context.getString(R.string.profile_role_switch));
        if (profile.age != 0) values.add(context.getResources().getQuantityString(R.plurals.profile_age_value, profile.age, profile.age));
        if (!profile.gender.isEmpty()) values.add(profile.gender);
        TextView details = text(context, String.join(" · ", values), 13, false); details.setTag(prefix + "_details");
        details.setVisibility(values.isEmpty() ? View.GONE : View.VISIBLE);
        details.setPadding(0, dp(context, 4), 0, 0); copy.addView(details, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, -2);
        rowSize.topMargin = dp(context, 4); rowSize.bottomMargin = dp(context, 4);
        parent.addView(row, rowSize);
        return new Identity(row, copy, name, details);
    }

    public static LinearLayout row(LinearLayout parent, int icon, CharSequence title, CharSequence value, Runnable action) {
        return row(parent, icon, title, value, action, false);
    }

    public static LinearLayout row(LinearLayout parent, int icon, CharSequence title, CharSequence value,
            Runnable action, boolean inlineValue) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 48)); row.setPadding(0, dp(context, 6), 0, dp(context, 6));
        if (icon != 0) {
            ImageView image = new ImageView(context); image.setImageResource(icon);
            image.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.text_primary)));
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
            size.setMarginEnd(dp(context, 12)); row.addView(image, size);
        }
        boolean stacked = !inlineValue && context.getResources().getConfiguration().fontScale >= 1.3f;
        LinearLayout copy = new LinearLayout(context);
        copy.setGravity(Gravity.CENTER_VERTICAL); copy.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView label = text(context, title, 14, true);
        copy.addView(label, stacked ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        TextView detail = text(context, value, 13, false); detail.setTag("overview_row_value");
        if (stacked) detail.setPadding(0, dp(context, 4), 0, 0);
        else { if (!inlineValue) detail.setMaxWidth(dp(context, 150)); detail.setGravity(Gravity.END); }
        detail.setVisibility(value == null || value.length() == 0 ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams detailSize = inlineValue
                ? new LinearLayout.LayoutParams(0, -2, 1.7f)
                : new LinearLayout.LayoutParams(stacked ? -1 : -2, -2);
        if (!stacked) detailSize.setMarginStart(dp(context, 10));
        copy.addView(detail, detailSize);
        if (action != null) {
            ImageView arrow = new ImageView(context);
            arrow.setImageResource(R.drawable.ic_overview_chevron);
            arrow.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.text_secondary)));
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20))); click(row, action);
        }
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }

    public static void divider(LinearLayout parent) {
        View line = new View(parent.getContext()); line.setBackgroundColor(parent.getContext().getColor(R.color.outline_subtle));
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(parent.getContext(), 1)));
    }

    public static void click(View view, Runnable action) {
        view.setFocusable(true); view.setClickable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        view.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        view.setForeground(view.getContext().getDrawable(ripple.resourceId));
        view.setOnClickListener(ignored -> action.run()); ViewCompat.setScreenReaderFocusable(view, true);
    }

    private static int dp(Context context, int value) { return PersonUi.dp(context, value); }

    public static final class Identity {
        public final LinearLayout view, copy;
        public final TextView name, details;
        Identity(LinearLayout view, LinearLayout copy, TextView name, TextView details) {
            this.view = view; this.copy = copy; this.name = name; this.details = details;
        }
    }
}
