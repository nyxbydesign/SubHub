package com.subhub.app.people;

import android.view.View;
import android.widget.LinearLayout;
import com.subhub.app.R;

/** Read-only app rows share the contract's type, icon, spacing and separators. */
public final class ContractAppList {
    private ContractAppList() { }
    public static void add(LinearLayout parent, String apps) {
        add(parent, new PersonSnapshot.Section("apps", "", "", apps));
    }

    public static void add(LinearLayout parent, PersonSnapshot.Section section) {
        java.util.List<PersonSnapshot.App> apps = section.apps;
        if (apps.isEmpty()) {
            apps = new java.util.ArrayList<>();
            for (String label : section.details.split("\\n")) if (!label.trim().isEmpty())
                apps.add(new PersonSnapshot.App("", label));
        }
        int index = 0;
        for (PersonSnapshot.App app : apps) {
            if (index++ > 0) {
                View divider = new View(parent.getContext());
                divider.setBackgroundColor(parent.getContext().getColor(R.color.outline_subtle));
                parent.addView(divider, new LinearLayout.LayoutParams(-1, PersonUi.dp(parent.getContext(), 1)));
            }
            LinearLayout row = PersonUi.row(parent, R.drawable.ic_settings_apps, app.label, "", null);
            row.setMinimumHeight(PersonUi.dp(parent.getContext(), 48));
            row.setTag("contract_app:" + app.label);
            android.widget.ImageView icon = (android.widget.ImageView) row.getChildAt(0);
            icon.getLayoutParams().width = PersonUi.dp(parent.getContext(), 32);
            icon.getLayoutParams().height = PersonUi.dp(parent.getContext(), 32);
            icon.setTag("contract_app_icon:" + app.packageName);
            com.subhub.app.util.AppIcons.bind(icon, app.packageName, R.drawable.ic_settings_apps);
            androidx.core.view.ViewCompat.setScreenReaderFocusable(row, true);
        }
    }
}
