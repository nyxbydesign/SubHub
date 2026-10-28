package com.subhub.app.people;

import android.view.View;
import android.widget.LinearLayout;
import com.subhub.app.R;

/** Read-only app rows share the contract's type, icon, spacing and separators. */
public final class ContractAppList {
    private ContractAppList() { }
    public static void add(LinearLayout parent, String apps) {
        int index = 0;
        for (String app : apps.split("\\n")) {
            if (app.trim().isEmpty()) continue;
            if (index++ > 0) {
                View divider = new View(parent.getContext());
                divider.setBackgroundColor(parent.getContext().getColor(R.color.outline_subtle));
                parent.addView(divider, new LinearLayout.LayoutParams(-1, PersonUi.dp(parent.getContext(), 1)));
            }
            LinearLayout row = PersonUi.row(parent, R.drawable.ic_settings_apps, app, "", null);
            row.setMinimumHeight(PersonUi.dp(parent.getContext(), 48));
            row.setTag("contract_app:" + app);
        }
    }
}
