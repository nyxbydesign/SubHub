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
        hero.setBackgroundResource(R.drawable.bg_home_composition_person);
        hero.setTag(dom ? "self_profile:dom" : "self_profile:sub");
        hero.addView(ProfilePreview.own(activity, profile, dom), new LinearLayout.LayoutParams(-1, -2));
        if (!dom) {
            LinearLayout features = PersonUi.column(activity); features.setTag("self_profile_features");
            hero.addView(features, new LinearLayout.LayoutParams(-1, -2));
            com.subhub.app.security.SubFeatureControls.bindReadOnly(activity, features);
        }
        com.subhub.app.journal.JournalPreview.add(hero, dom, PersonId.LOCAL,
                () -> com.subhub.app.journal.JournalRoutes.own(activity, dom));
        TextView edit = PersonUi.action(hero, R.string.profile_edit, () -> {
            if (ProfileStore.mayEdit(activity, dom)) activity.startActivity(new Intent(activity, ProfileEditorActivity.class)
                    .putExtra(ProfileEditorActivity.EXTRA_DOM, dom));
        }, false); edit.setTag("profile_edit"); edit.setBackground(null); edit.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        edit.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_journal_edit, 0, 0, 0);
        edit.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent_text)));
        edit.setCompoundDrawablePadding(PersonUi.dp(activity, 8)); edit.setPadding(0, 0, 0, 0);
    }
}
