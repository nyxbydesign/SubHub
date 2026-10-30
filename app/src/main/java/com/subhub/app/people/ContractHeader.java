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
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        View avatar = ProfileViews.avatar(context, profile); avatar.setTag("contract_dom_avatar");
        row.addView(avatar, new LinearLayout.LayoutParams(PersonUi.dp(context, 48), PersonUi.dp(context, 48)));
        TextView name = new TextView(context);
        com.subhub.app.util.UiIdentity.textSize(name, R.dimen.ui_text_section);
        name.setTextColor(context.getColor(R.color.text_primary)); name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setText(profile.name); name.setTag("contract_dom_name");
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMarginStart(PersonUi.dp(context, 12)); row.addView(name, size);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (!profile.about.isEmpty()) {
            TextView about = PersonUi.text(parent, profile.about, false); about.setTag("contract_dom_about");
            about.setMaxLines(expanded ? Integer.MAX_VALUE : 2);
            about.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
            TextView more = PersonUi.action(parent, expanded ? R.string.profile_show_less : R.string.profile_show_more,
                    () -> { }, false);
            more.setTag("contract_dom_about_expand"); more.setMinHeight(PersonUi.dp(context, 48));
            more.setVisibility(View.GONE);
            more.setOnClickListener(view -> {
                boolean open = about.getMaxLines() == 2;
                about.setMaxLines(open ? Integer.MAX_VALUE : 2); about.setEllipsize(open ? null : TextUtils.TruncateAt.END);
                more.setText(open ? R.string.profile_show_less : R.string.profile_show_more); onExpansion.accept(open);
            });
            about.addOnLayoutChangeListener((view, a, b, c, d, e, f, g, h) -> {
                android.text.Layout layout = about.getLayout();
                if (layout != null) {
                    boolean overflowing = layout.getLineCount() > 2 || layout.getLineCount() > 0
                            && layout.getEllipsisCount(layout.getLineCount() - 1) > 0;
                    more.setVisibility(overflowing ? View.VISIBLE : View.GONE);
                }
            });
        }
        TextView contract = PersonUi.text(parent, person.contractName, false); contract.setTag("contract_name");
    }
}
