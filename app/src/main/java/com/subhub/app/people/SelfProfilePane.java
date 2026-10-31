package com.subhub.app.people;

import android.content.Intent;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.security.DeviceRolePreferences;

/** One active workspace owns one editable identity; the other role's profile stays separate. */
public final class SelfProfilePane {
    private final AppCompatActivity activity;
    private final LinearLayout content;

    public SelfProfilePane(AppCompatActivity activity) {
        this.activity = activity;
        content = PersonUi.column(activity);
        content.setTag("self_profile");
    }
    public LinearLayout view() { return content; }

    public void refresh() {
        content.removeAllViews();
        boolean dom = DeviceRolePreferences.isDomWorkspace(activity);
        if (!ProfileStore.mayEdit(activity, dom)) return;
        ProfileData profile = ProfileStore.load(activity, dom);
        LinearLayout hero = PersonUi.card(content);
        hero.setTag(dom ? "self_profile:dom" : "self_profile:sub");
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new ProfileAvatarView(activity, profile), new LinearLayout.LayoutParams(
                PersonUi.dp(activity, 80), PersonUi.dp(activity, 80)));
        LinearLayout copy = PersonUi.column(activity);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMarginStart(PersonUi.dp(activity, 16)); row.addView(copy, size);
        TextView name = PersonUi.text(copy, profile.name, true);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24);
        name.setTag("self_profile_name");
        PersonUi.text(copy, activity.getString(dom ? R.string.role_dom : R.string.role_sub), false);
        hero.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (!profile.about.isEmpty()) {
            PersonUi.text(hero, activity.getString(R.string.profile_about), true);
            PersonUi.text(hero, profile.about, false).setTag("self_profile_about");
        }
        PersonUi.action(hero, R.string.journal_title,
                () -> com.subhub.app.journal.JournalRoutes.own(activity, dom), false).setTag("profile_journal");
        PersonUi.action(hero, R.string.profile_edit, () -> {
            if (ProfileStore.mayEdit(activity, dom)) activity.startActivity(new Intent(activity, ProfileEditorActivity.class)
                    .putExtra(ProfileEditorActivity.EXTRA_DOM, dom));
        }, false).setTag("profile_edit");
        if (!dom) com.subhub.app.security.SubFeatureControls.bind(activity, PersonUi.card(content));
    }
}
