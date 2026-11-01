package com.subhub.app.people;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import com.subhub.app.settings.GlobalSettingsActivity;
import java.util.ArrayList;
import java.util.List;

/** Compact native composition used by the two overviews and the Sub's Contract. */
public final class OverviewUi {
    private OverviewUi() { }

    public static void header(AppCompatActivity activity, boolean enabled) {
        ViewGroup header = activity.findViewById(R.id.primary_header);
        ViewGroup content = activity.findViewById(android.R.id.content);
        View page = content.getChildAt(0);
        if (!Boolean.valueOf(enabled).equals(page.getTag(R.id.overview_page_style))) {
            int background = enabled ? R.drawable.bg_overview_page : R.drawable.bg_main;
            page.setBackgroundResource(background); activity.getWindow().setBackgroundDrawableResource(background);
            page.setTag(R.id.overview_page_style, enabled);
        }
        View role = header.findViewById(R.id.button_edit_lock);
        if (enabled && !(role.getBackground() instanceof android.graphics.drawable.InsetDrawable)) {
            int start = role.getPaddingStart(), top = role.getPaddingTop(), end = role.getPaddingEnd(), bottom = role.getPaddingBottom();
            role.setBackground(new android.graphics.drawable.InsetDrawable(activity.getDrawable(R.drawable.bg_shell_role), 0, dp(activity, 8), 0, dp(activity, 8)));
            role.setPaddingRelative(start, top, end, bottom);
        } else if (!enabled && role.getBackground() instanceof android.graphics.drawable.InsetDrawable) role.setBackgroundResource(R.drawable.bg_shell_role);
        ImageView settings = header.findViewById(R.id.overview_settings);
        if (settings == null && enabled) {
            settings = new ImageView(activity);
            settings.setId(R.id.overview_settings);
            settings.setImageResource(R.drawable.ic_overview_settings);
            settings.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.text_primary)));
            settings.setContentDescription(activity.getString(R.string.global_settings_title));
            settings.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
            header.addView(settings, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
            click(settings, () -> activity.startActivity(new Intent(activity, GlobalSettingsActivity.class)));
        }
        if (settings != null) settings.setVisibility(enabled ? View.VISIBLE : View.GONE);
        TextView brand = header.findViewById(R.id.primary_header_brand);
        TextView title = header.findViewById(R.id.primary_header_title);
        if (enabled) { brand.setTextSize(24); title.setTextSize(18); }
        else {
            brand.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, activity.getResources().getDimension(R.dimen.ui_text_label));
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, activity.getResources().getDimension(R.dimen.ui_text_title));
        }
        if (enabled) {
            header.setBackground(null);
            header.findViewById(R.id.primary_header_icon).setVisibility(View.GONE);
            View copy = header.findViewById(R.id.primary_header_copy);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) copy.getLayoutParams();
            if (params.getMarginStart() != 0) { params.setMarginStart(0); copy.setLayoutParams(params); }
        } else header.setBackgroundResource(R.drawable.bg_primary_header_rule);
    }

    public static LinearLayout card(LinearLayout parent) {
        LinearLayout card = PersonUi.column(parent.getContext());
        card.setBackgroundResource(R.drawable.bg_overview_card);
        int padding = dp(parent.getContext(), 14);
        card.setPadding(padding, dp(parent.getContext(), 10), padding, dp(parent.getContext(), 10));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(parent.getContext(), 10);
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
            TextView link = text(context, context.getString(actionTitle) + "  ›", 12, false);
            link.setTextColor(context.getColor(R.color.accent_text));
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
        values.add(context.getString(dom ? R.string.role_dom : R.string.role_sub));
        if (profile.age != 0) values.add(context.getResources().getQuantityString(R.plurals.profile_age_value, profile.age, profile.age));
        if (!profile.gender.isEmpty()) values.add(profile.gender);
        TextView details = text(context, String.join(" · ", values), 13, false); details.setTag(prefix + "_details");
        details.setPadding(0, dp(context, 4), 0, 0); copy.addView(details, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, -2);
        rowSize.topMargin = dp(context, 4); rowSize.bottomMargin = dp(context, 4);
        parent.addView(row, rowSize);
        return new Identity(row, copy, name, details);
    }

    public static LinearLayout row(LinearLayout parent, int icon, CharSequence title, CharSequence value, Runnable action) {
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
        boolean stacked = context.getResources().getConfiguration().fontScale >= 1.3f;
        LinearLayout copy = new LinearLayout(context);
        copy.setGravity(Gravity.CENTER_VERTICAL); copy.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView label = text(context, title, 14, true);
        copy.addView(label, stacked ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        TextView detail = text(context, value, 13, false); detail.setTag("overview_row_value");
        if (stacked) detail.setPadding(0, dp(context, 4), 0, 0);
        else { detail.setMaxWidth(dp(context, 150)); detail.setGravity(Gravity.END); }
        detail.setVisibility(value == null || value.length() == 0 ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams detailSize = new LinearLayout.LayoutParams(stacked ? -1 : -2, -2);
        if (!stacked) detailSize.setMarginStart(dp(context, 10));
        copy.addView(detail, detailSize);
        if (action != null) {
            TextView arrow = text(context, "›", 22, false); arrow.setGravity(Gravity.CENTER);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(context, 20), -2)); click(row, action);
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
