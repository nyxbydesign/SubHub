package com.subhub.app.people;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;

/** Person identity stays prominent without repeating device and contract details. */
public final class ProfileViews {
    private ProfileViews() {}

    public static void identity(LinearLayout parent, PersonSnapshot person) {
        Context context = parent.getContext();
        LinearLayout hero = PersonUi.column(context);
        hero.setPadding(0, PersonUi.dp(context, 4), 0, PersonUi.dp(context, 20));
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(avatar(context, person.name), new LinearLayout.LayoutParams(
                PersonUi.dp(context, 64), PersonUi.dp(context, 64)));
        LinearLayout copy = PersonUi.column(context);
        LinearLayout.LayoutParams copySize = new LinearLayout.LayoutParams(0, -2, 1);
        copySize.setMarginStart(PersonUi.dp(context, 16));
        row.addView(copy, copySize);
        TextView name = PersonUi.text(copy, person.name, true);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
        ViewCompat.setAccessibilityHeading(name, true);
        PersonUi.text(copy, PersonUi.connection(context, person), false);
        hero.addView(row, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(hero, new LinearLayout.LayoutParams(-1, -2));
    }

    public static LinearLayout person(LinearLayout parent, PersonSnapshot person, Runnable open) {
        Context context = parent.getContext();
        LinearLayout card = PersonUi.card(parent);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(avatar(context, person.name), new LinearLayout.LayoutParams(
                PersonUi.dp(context, 44), PersonUi.dp(context, 44)));
        LinearLayout content = PersonUi.column(context);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMarginStart(PersonUi.dp(context, 14));
        row.addView(content, size);
        PersonUi.row(content, 0, person.name, PersonUi.connection(context, person), null);
        TextView arrow = new TextView(context);
        arrow.setText("›");
        arrow.setTextSize(24);
        arrow.setTextColor(context.getColor(R.color.text_secondary));
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(PersonUi.dp(context, 24), -2));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        PersonUi.text(card, person.contractName, false);
        card.setOnClickListener(view -> open.run());
        card.setFocusable(true);
        card.setClickable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        card.setForeground(context.getDrawable(ripple.resourceId));
        card.setTag("person:" + person.id.value());
        ViewCompat.setScreenReaderFocusable(card, true);
        return card;
    }

    private static TextView avatar(Context context, String name) {
        TextView value = new TextView(context);
        String[] words = name.trim().split("\\s+");
        String initials = words.length == 0 || words[0].isEmpty() ? "?"
                : initial(words[0]) + (words.length > 1 ? initial(words[words.length - 1]) : "");
        value.setText(initials.toUpperCase(java.util.Locale.ROOT));
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                value, 10, 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        value.setTextColor(context.getColor(R.color.accent_text));
        value.setTypeface(null, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        value.setBackgroundResource(R.drawable.bg_profile_avatar);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return value;
    }

    private static String initial(String word) {
        return word.substring(0, word.offsetByCodePoints(0, 1));
    }
}
