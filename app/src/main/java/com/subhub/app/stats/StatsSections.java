package com.subhub.app.stats;

import android.app.Activity;
import android.content.Intent;
import android.widget.Button;
import android.widget.LinearLayout;
import com.subhub.app.R;

/** Statistics and its role-selected catalog share one primary destination. */
final class StatsSections {
    private StatsSections() { }

    static void add(Activity activity, LinearLayout parent, boolean achievements) {
        LinearLayout sections = new LinearLayout(activity);
        sections.setTag("stats_sections");
        sections.setBaselineAligned(false);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        parent.addView(sections, Math.min(1, parent.getChildCount()), size);
        section(activity, sections, R.string.role_stats, R.id.stats_section_statistics,
                !achievements, StatsActivity.class);
        section(activity, sections, R.string.achievements_title, R.id.stats_section_achievements,
                achievements, AchievementsActivity.class);
    }

    private static void section(Activity activity, LinearLayout parent, int title, int id,
            boolean selected, Class<? extends Activity> target) {
        Button button = (Button) activity.getLayoutInflater().inflate(R.layout.view_ux_action, parent, false);
        button.setId(id);
        button.setText(title);
        button.setAllCaps(false);
        button.setSelected(selected);
        button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        button.setOnClickListener(view -> {
            if (!selected) activity.startActivity(new Intent(activity, target)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        int gap = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_inline);
        size.setMargins(gap, 0, gap, 0);
        parent.addView(button, size);
    }
}
