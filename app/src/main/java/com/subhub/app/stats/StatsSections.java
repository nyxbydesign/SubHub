package com.subhub.app.stats;

import android.widget.Button;
import android.widget.LinearLayout;
import com.subhub.app.R;

/** Statistics and its role-selected catalog share one primary destination. */
final class StatsSections {
    private StatsSections() { }

    static void add(StatsActivity activity, LinearLayout parent, boolean achievements) {
        LinearLayout sections = new LinearLayout(activity);
        sections.setTag("stats_sections");
        sections.setBaselineAligned(false);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        parent.addView(sections, Math.min(1, parent.getChildCount()), size);
        section(activity, sections, R.string.role_stats, R.id.stats_section_statistics,
                !achievements, false);
        section(activity, sections, R.string.achievements_title, R.id.stats_section_achievements,
                achievements, true);
    }

    static void select(LinearLayout parent, boolean catalog) {
        for (int id : new int[] { R.id.stats_section_statistics, R.id.stats_section_achievements }) {
            Button button = parent.findViewById(id);
            if (button == null) continue;
            boolean selected = (id == R.id.stats_section_achievements) == catalog;
            button.setSelected(selected);
            button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        }
    }

    private static void section(StatsActivity activity, LinearLayout parent, int title, int id,
            boolean selected, boolean catalog) {
        Button button = (Button) activity.getLayoutInflater().inflate(R.layout.view_ux_action, parent, false);
        button.setId(id);
        button.setText(title);
        button.setAllCaps(false);
        button.setSelected(selected);
        button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        button.setOnClickListener(view -> {
            activity.showAchievements(catalog);
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        int gap = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_inline);
        size.setMargins(gap, 0, gap, 0);
        parent.addView(button, size);
    }
}
