package com.subhub.app.people;

import android.app.Activity;
import android.content.Intent;
import android.widget.Button;
import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.activity.ActivitySources;
import com.subhub.app.activity.DomActivityActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;

/** The roster and its activity feed share the Subs destination. */
public final class PeopleSections {
    private PeopleSections() { }

    public static void add(Activity activity, LinearLayout parent, boolean feed) {
        if (!ActivitySources.repository().supports(null)) return;
        LinearLayout sections = new LinearLayout(activity);
        sections.setTag("people_sections");
        sections.setBaselineAligned(false);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        parent.addView(sections, 0, size);
        section(activity, sections, R.string.role_your_subs, R.id.people_section_people,
                !feed, PeopleActivity.class);
        section(activity, sections, R.string.dom_activity_title, R.id.people_section_activity,
                feed, DomActivityActivity.class);
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
            if (selected || !ControllerPinManager.isDomModeActive()
                    || !DeviceRolePreferences.supportsDom(activity)) return;
            int flags = target == PeopleActivity.class
                    ? Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    : Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
            activity.startActivity(new Intent(activity, target).addFlags(flags));
            if (target == PeopleActivity.class) activity.finish();
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        int gap = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_inline);
        size.setMargins(gap, 0, gap, 0);
        parent.addView(button, size);
    }
}
