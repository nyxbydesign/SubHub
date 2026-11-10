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
        ProfilePreview preview = new ProfilePreview(context, profile, true, "contract_dom", expanded, onExpansion);
        PersonSnapshot.PeerContact contact = person.contractDomContact != null
                && person.contractDomContact.id.equals(person.contractDomId) ? person.contractDomContact : null;
        if (contact != null) ProfileViews.inlineLastOnline(preview, "contract_dom", person.contractDomId,
                contact.state, contact.lastOnlineAt);
        View avatar = preview.findViewWithTag("contract_dom_avatar");
        avatar.setLayoutParams(new LinearLayout.LayoutParams(PersonUi.dp(context, 56), PersonUi.dp(context, 56)));
        ((TextView) preview.findViewWithTag("contract_dom_name")).setTextSize(20);
        ((TextView) preview.findViewWithTag("contract_dom_details")).setTextSize(13);
        TextView about = preview.findViewWithTag("contract_dom_about");
        if (about != null) about.setTextSize(14);
        for (int i = 0; i < preview.getChildCount(); i++) {
            View child = preview.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString().equals(context.getString(R.string.profile_about)))
                ((TextView) child).setTextSize(14);
        }
        parent.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        if (person.contractDomId != null && !ProfileViews.remoteContactTarget(person.contractDomId)) {
            if (contact != null && contact.state == PersonSnapshot.Connection.PENDING)
                PersonUi.text(parent, context.getString(R.string.role_connection_pending), false);
            else if (contact != null && contact.state == PersonSnapshot.Connection.REVOKED)
                PersonUi.text(parent, context.getString(R.string.role_connection_revoked), false);
        }
        if (person.contractDomId != null && context instanceof android.app.Activity)
            com.subhub.app.journal.JournalPreview.addSharedLink(parent,
                    com.subhub.app.security.DeviceRolePreferences.isDomWorkspace(context), person.contractDomId,
                    () -> com.subhub.app.journal.JournalRoutes.profile((android.app.Activity) context, person.contractDomId));
    }
}
