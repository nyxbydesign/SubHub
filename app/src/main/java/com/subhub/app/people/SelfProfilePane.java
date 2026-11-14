package com.subhub.app.people;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.subhub.app.R;
import com.subhub.app.journal.JournalPreview;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.AsyncUiScope;

/** One active workspace owns one editable identity; the other role's profile stays separate. */
public final class SelfProfilePane {
    private static final String OWN_PROFILE_READ = "self-profile";
    private final AppCompatActivity activity;
    private final LinearLayout content;
    private final AsyncUiScope data;
    private final SharedPreferences.OnSharedPreferenceChangeListener hardcoreChange = (settings, key) -> {
        if ((key == null || HardcoreModeManager.KEY_REQUESTED.equals(key)) && view().isShown()) refresh();
    };

    public SelfProfilePane(AppCompatActivity activity) {
        this.activity = activity;
        data = AsyncUiScope.forPage(activity);
        content = PersonUi.column(activity);
        content.setTag("self_profile");
        SharedPreferences settings = activity.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        activity.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onResume(LifecycleOwner owner) {
                settings.registerOnSharedPreferenceChangeListener(hardcoreChange);
            }
            @Override public void onPause(LifecycleOwner owner) {
                settings.unregisterOnSharedPreferenceChangeListener(hardcoreChange);
            }
        });
    }
    public LinearLayout view() { return content; }

    public void refresh() {
        boolean dom = DeviceRolePreferences.isDomWorkspace(activity);
        if (!ProfileStore.mayEdit(activity, dom)) {
            data.cancel(OWN_PROFILE_READ);
            content.removeAllViews();
            return;
        }
        if (dom) {
            data.cancel(OWN_PROFILE_READ);
            render(true, ProfileStore.load(activity, true), null);
            return;
        }
        Context app = activity.getApplicationContext();
        long revision = ControllerPinManager.domWorkspaceRevision();
        data.load(OWN_PROFILE_READ, () -> {
            if (!ProfileStore.mayEdit(app, false)) throw new SecurityException("Sub profile unavailable");
            return new SubProfile(ProfileStore.load(app, false), JournalPreview.loadInitial(app, false, PersonId.LOCAL));
        }, loaded -> {
            if (canPublishSub(revision)) render(false, loaded.profile(), loaded.journal());
        }, failure -> {
            if (canPublishSub(revision)) content.removeAllViews();
        });
    }

    private boolean canPublishSub(long revision) {
        return content.isShown() && !activity.isFinishing()
                && revision == ControllerPinManager.domWorkspaceRevision() && ProfileStore.mayEdit(activity, false);
    }

    private record SubProfile(ProfileData profile, JournalPreview.Initial journal) {}

    private void render(boolean dom, ProfileData profile, JournalPreview.Initial journal) {
        content.removeAllViews();
        LinearLayout hero = PersonUi.card(content);
        hero.setBackgroundResource(R.drawable.bg_home_composition_person);
        hero.setTag(dom ? "self_profile:dom" : "self_profile:sub");
        hero.addView(ProfilePreview.own(activity, profile, dom), new LinearLayout.LayoutParams(-1, -2));
        if (!dom) {
            LinearLayout features = PersonUi.column(activity); features.setTag("self_profile_features");
            hero.addView(features, new LinearLayout.LayoutParams(-1, -2));
            com.subhub.app.security.SubFeatureControls.bindReadOnly(activity, features);
        }
        JournalPreview.add(hero, dom, PersonId.LOCAL,
                () -> com.subhub.app.journal.JournalRoutes.own(activity, dom), journal);
        TextView edit = PersonUi.action(hero, R.string.profile_edit, () -> {
            if (ProfileStore.mayEdit(activity, dom)) activity.startActivity(new Intent(activity, ProfileEditorActivity.class)
                    .putExtra(ProfileEditorActivity.EXTRA_DOM, dom));
        }, false); edit.setTag("profile_edit"); edit.setBackground(null); edit.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        edit.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_journal_edit, 0, 0, 0);
        edit.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent_text)));
        edit.setCompoundDrawablePadding(PersonUi.dp(activity, 8)); edit.setPadding(0, 0, 0, 0);
    }
}
