package com.subhub.app.people;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.subhub.app.R;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.PreferencePage;

/** Profile edits remain a local draft until Save; the picker grants only the selected image. */
public final class ProfileEditorActivity extends PreferencePage {
    public static final String EXTRA_DOM = "profile_dom";
    private boolean dom, importing;
    private String avatar;
    private EditText name, about;
    private LinearLayout portrait;
    private TextView save, change, remove;
    private AsyncUiScope data;
    private ActivityResultLauncher<String> picker;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dom = getIntent().getBooleanExtra(EXTRA_DOM, false);
        if (!ProfileStore.mayEdit(this, dom)) { finish(); return; }
        ProfileData profile = ProfileStore.load(this, dom);
        avatar = state == null ? profile.avatar : state.getString("profile_avatar", profile.avatar);
        data = AsyncUiScope.forPage(this);
        picker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null || !ProfileStore.mayEdit(this, dom)) return;
            importing = true; buttons();
            android.content.Context application = getApplicationContext();
            data.load("profile-import", () -> ProfileImages.importImage(application, uri), imported -> {
                importing = false;
                if (!ProfileStore.mayEdit(this, dom)) { finish(); return; }
                avatar = imported; renderAvatar(); buttons();
            }, failure -> {
                importing = false; buttons(); notice(getString(R.string.profile_image_failed));
            });
        });
        page(R.string.profile_edit);
        // The badge identifies the edited profile even when a Dom-only device is locked.
        ((TextView) findViewById(R.id.header_role_badge)).setText(dom ? R.string.role_dom : R.string.role_sub);
        LinearLayout form = card(page);
        portrait = PersonUi.column(this);
        portrait.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        form.addView(portrait, new LinearLayout.LayoutParams(-1, -2));
        change = PersonUi.action(form, R.string.profile_change_avatar, () -> picker.launch("image/*"), false);
        change.setTag("profile_avatar_change");
        remove = PersonUi.action(form, R.string.profile_remove_avatar, () -> {
            avatar = ""; renderAvatar(); buttons();
        }, false);
        remove.setTag("profile_avatar_remove");
        fieldLabel(form, getString(dom ? R.string.profile_dom_name : R.string.profile_sub_name));
        name = input(form, dom ? R.string.profile_dom_name : R.string.profile_sub_name,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setTag(dom ? "profile_name:dom" : "profile_name:sub");
        name.setContentDescription(getString(dom ? R.string.profile_dom_name : R.string.profile_sub_name));
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(ProfileNames.MAX_LENGTH)});
        name.setText(state == null ? ProfileNames.chosen(this, dom) : state.getString("profile_name", ""));
        fieldLabel(form, getString(R.string.profile_about));
        about = input(form, R.string.profile_about, android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        about.setSingleLine(false); about.setMinLines(3); about.setGravity(android.view.Gravity.TOP);
        about.setTag("profile_about"); about.setContentDescription(getString(R.string.profile_about));
        about.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(ProfileData.MAX_ABOUT_LENGTH)});
        about.setText(state == null ? profile.about : state.getString("profile_about", profile.about));
        save = PersonUi.action(page, R.string.profile_save, this::save, true); save.setTag("profile_save");
        PersonUi.action(page, android.R.string.cancel, this::finish, false).setTag("profile_cancel");
        renderAvatar(); buttons();
        com.subhub.app.util.InputFocusPolicy.openActivityForReading(this);
    }

    private void renderAvatar() {
        portrait.removeAllViews();
        ProfileData value = new ProfileData(ProfileStore.load(this, dom).name, "", avatar);
        ProfileAvatarView view = new ProfileAvatarView(this, value); view.setTag("profile_avatar_preview");
        portrait.addView(view, new LinearLayout.LayoutParams(dp(96), dp(96)));
    }

    private void buttons() {
        if (save == null) return;
        save.setEnabled(!importing); change.setEnabled(!importing);
        remove.setVisibility(avatar.isEmpty() ? View.GONE : View.VISIBLE);
        remove.setEnabled(!importing);
    }

    private void save() {
        if (importing || !ProfileStore.mayEdit(this, dom)) return;
        if (!ProfileStore.save(this, dom, name.getText().toString(), about.getText().toString(), avatar)) {
            notice(getString(R.string.profile_save_failed)); return;
        }
        com.subhub.app.remote.RemoteFeature.get(this).changed();
        finish();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!ProfileStore.mayEdit(this, dom)) finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (name != null) {
            state.putString("profile_name", name.getText().toString());
            state.putString("profile_about", about.getText().toString());
            state.putString("profile_avatar", avatar);
        }
        super.onSaveInstanceState(state);
    }
}
