package com.subhub.app.stats;

import android.widget.Button;
import android.widget.LinearLayout;
import com.subhub.app.R;

/** Statistics and its role-selected catalog share one primary destination. */
final class StatsSections {
    private StatsSections() { }

    static void add(StatsActivity activity, LinearLayout parent, boolean achievements, boolean profile) {
        LinearLayout sections = new LinearLayout(activity);
        sections.setTag("stats_sections");
        sections.setBaselineAligned(false);
        sections.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        parent.addView(sections, Math.min(1, parent.getChildCount()), size);
        LinearLayout row = new LinearLayout(activity);
        row.setBaselineAligned(false);
        sections.addView(row, new LinearLayout.LayoutParams(-1, -2));
        section(activity, row, R.string.role_profile, R.id.stats_section_profile, profile, false);
        section(activity, row, R.string.role_stats, R.id.stats_section_statistics,
                !achievements && !profile, false);
        section(activity, row, R.string.achievements_title, R.id.stats_section_achievements,
                achievements, true);
        int required = 0;
        int gap = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_inline);
        for (int index = 0; index < row.getChildCount(); index++) {
            Button button = (Button) row.getChildAt(index);
            int natural = Math.max(button.getMinimumWidth(), (int) Math.ceil(android.text.Layout.getDesiredWidth(
                    button.getText(), button.getPaint())) + button.getPaddingLeft() + button.getPaddingRight());
            ((LinearLayout.LayoutParams) button.getLayoutParams()).weight = natural;
            required += natural + 2 * gap;
        }
        int available = Math.round(activity.getResources().getConfiguration().screenWidthDp
                * activity.getResources().getDisplayMetrics().density)
                - 2 * activity.getResources().getDimensionPixelSize(R.dimen.page_margin);
        if (required > available) {
            Button catalog = row.findViewById(R.id.stats_section_achievements);
            row.removeView(catalog);
            LinearLayout.LayoutParams catalogSize = new LinearLayout.LayoutParams(-1, -2);
            catalogSize.setMargins(gap, gap, gap, 0);
            sections.addView(catalog, catalogSize);
        }
    }

    static void select(LinearLayout parent, boolean catalog, boolean profile) {
        for (int id : new int[] { R.id.stats_section_profile, R.id.stats_section_statistics, R.id.stats_section_achievements }) {
            Button button = parent.findViewById(id);
            if (button == null) continue;
            boolean selected = profile ? id == R.id.stats_section_profile
                    : id == (catalog ? R.id.stats_section_achievements : R.id.stats_section_statistics);
            button.setSelected(selected);
            button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        }
    }

    private static void section(StatsActivity activity, LinearLayout parent, int title, int id,
            boolean selected, boolean catalog) {
        Button button = (Button) activity.getLayoutInflater().inflate(R.layout.view_ux_action, parent, false);
        button.setId(id);
        button.setText(title);
        com.subhub.app.util.UiIdentity.textSize(button, R.dimen.ui_text_row_title);
        int inset = com.subhub.app.people.PersonUi.dp(activity, 8);
        button.setPaddingRelative(inset, button.getPaddingTop(), inset, button.getPaddingBottom());
        button.setAllCaps(false);
        button.setSelected(selected);
        button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        button.setOnClickListener(view -> {
            if (id == R.id.stats_section_profile) activity.showProfile();
            else activity.showAchievements(catalog);
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        int gap = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_inline);
        size.setMargins(gap, 0, gap, 0);
        parent.addView(button, size);
    }
}
