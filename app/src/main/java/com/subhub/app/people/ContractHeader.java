package com.subhub.app.people;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;

/** The contract's Dom preview carries only its already-authorized profile projection. */
public final class ContractHeader {
    private ContractHeader() { }
    public static void add(LinearLayout parent, PersonSnapshot person, boolean expanded,
            java.util.function.Consumer<Boolean> onExpansion) {
        android.content.Context context = parent.getContext();
        ProfileData profile = person.contractDom == null
                ? ProfileData.empty(context.getString(R.string.header_subtitle_dom)) : person.contractDom;
        parent.addView(new ProfilePreview(context, profile, true, "contract_dom", expanded, onExpansion),
                new LinearLayout.LayoutParams(-1, -2));
        if (person.contractDomId != null && context instanceof android.app.Activity)
            com.subhub.app.journal.JournalPreview.add(parent,
                    com.subhub.app.security.DeviceRolePreferences.isDomWorkspace(context), person.contractDomId,
                    () -> com.subhub.app.journal.JournalRoutes.profile((android.app.Activity) context, person.contractDomId));
    }
}
