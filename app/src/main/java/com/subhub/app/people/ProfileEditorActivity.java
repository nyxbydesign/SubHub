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
    private boolean dom, importing, internal, saving;
    private InternalSubData internalData;
    private com.subhub.app.internal.InternalSubRecord internalBase;
    private String internalGeneration;
    private long internalControllerRevision;
    private String avatar;
    private com.subhub.app.security.SubFeatureControls.Draft features;
    private com.subhub.app.fullaccess.FullAccessSetup fullAccess;
    private EditText name, about, gender, age;
    private LinearLayout portrait;
    private TextView save, change, remove;
    private AsyncUiScope data;
    private ActivityResultLauncher<String> picker;
    private final android.os.Handler authorityClock = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable checkInternalAuthority = new Runnable() {
        @Override public void run() {
            if (!internal || isFinishing() || isDestroyed()) return;
            if (!mayEdit()) { findViewById(android.R.id.content).setVisibility(View.INVISIBLE); finish(); return; }
            authorityClock.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        internal = DummySubPreview.isRequested(getIntent());
        dom = !internal && getIntent().getBooleanExtra(EXTRA_DOM, false);
        if (internal) {
            if (!DummySubPreview.isAllowed(this)) { finish(); return; }
            internalData = InternalSubData.get(this); internalBase = internalData.acknowledged();
            internalGeneration = internalBase.generation;
            internalControllerRevision = com.subhub.app.security.ControllerPinManager.domWorkspaceRevision();
            if (state != null && state.getLong("internal_profile_base", -1) != internalBase.revision) {
                finish(); return;
            }
        }
        if (!mayEdit()) { finish(); return; }
        ProfileData profile = loadProfile();
        avatar = state == null ? profile.avatar : state.getString("profile_avatar", profile.avatar);
        data = AsyncUiScope.forPage(this);
        picker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null || !mayEdit()) return;
            importing = true; buttons();
            android.content.Context application = getApplicationContext();
            data.load("profile-import", () -> ProfileImages.importImage(application, uri), imported -> {
                importing = false;
                if (!mayEdit()) { finish(); return; }
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
        ProfileActionRow avatarActions = new ProfileActionRow(this);
        avatarActions.setTag("profile_avatar_actions");
        form.addView(avatarActions, new LinearLayout.LayoutParams(-1, -2));
        change = PersonUi.action(avatarActions, R.string.profile_change_avatar, () -> picker.launch("image/*"), false);
        change.setTag("profile_avatar_change");
        remove = PersonUi.action(avatarActions, R.string.profile_remove_avatar, () -> {
            avatar = ""; renderAvatar(); buttons();
        }, false);
        remove.setTag("profile_avatar_remove");
        fieldLabel(form, getString(dom ? R.string.profile_dom_name : R.string.profile_sub_name));
        name = input(form, dom ? R.string.profile_dom_name : R.string.profile_sub_name,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setTag(dom ? "profile_name:dom" : "profile_name:sub");
        name.setContentDescription(getString(dom ? R.string.profile_dom_name : R.string.profile_sub_name));
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(ProfileNames.MAX_LENGTH)});
        name.setText(state == null ? internal ? profile.name : ProfileNames.chosen(this, dom) : state.getString("profile_name", ""));
        com.subhub.app.util.FormRow details = new com.subhub.app.util.FormRow(this, null);
        form.addView(details, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout genderField = PersonUi.column(this), ageField = PersonUi.column(this);
        details.addView(genderField, new LinearLayout.LayoutParams(0, -2, 1));
        details.addView(ageField, new LinearLayout.LayoutParams(0, -2, 1));
        TextView genderLabel = fieldLabel(genderField, getString(R.string.profile_gender));
        gender = input(genderField, R.string.profile_optional, android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        gender.setTag("profile_gender"); gender.setContentDescription(getString(R.string.profile_gender));
        gender.setId(R.id.profile_gender_input); genderLabel.setLabelFor(gender.getId()); gender.setHint((CharSequence) null);
        com.subhub.app.util.InputBounds.limit(gender, ProfileData.MAX_GENDER_LENGTH);
        gender.setText(state == null ? profile.gender : state.getString("profile_gender", profile.gender));
        TextView ageLabel = fieldLabel(ageField, getString(R.string.profile_age));
        age = input(ageField, R.string.profile_optional, android.text.InputType.TYPE_CLASS_NUMBER);
        age.setTag("profile_age"); age.setContentDescription(getString(R.string.profile_age));
        age.setId(R.id.profile_age_input); ageLabel.setLabelFor(age.getId()); age.setHint((CharSequence) null);
        com.subhub.app.util.InputBounds.limit(age, 3);
        age.setText(state == null ? profile.age == 0 ? "" : Integer.toString(profile.age) : state.getString("profile_age", ""));
        fieldLabel(form, getString(R.string.profile_about_optional)).setTag("profile_about_label");
        about = input(form, R.string.profile_about, android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        about.setSingleLine(false); about.setMinLines(3); about.setGravity(android.view.Gravity.TOP);
        about.setTag("profile_about"); about.setContentDescription(getString(R.string.profile_about_optional));
        about.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(ProfileData.MAX_ABOUT_LENGTH)});
        about.setText(state == null ? profile.about : state.getString("profile_about", profile.about));
        if (!dom && !internal) {
            features = new com.subhub.app.security.SubFeatureControls.Draft(this, state);
            LinearLayout choices = card(page); choices.setTag("profile_edit_features");
            LinearLayout controls = PersonUi.column(this);
            choices.addView(controls, new LinearLayout.LayoutParams(-1, -2));
            FeatureConsentView grid = com.subhub.app.security.SubFeatureControls.bindDraft(this, controls, features);
            if (grid != null)
                fullAccess = new com.subhub.app.fullaccess.FullAccessSetup(this, controls, grid, this::mayEdit, state);
        } else if (internal) {
            fullAccess = new com.subhub.app.fullaccess.FullAccessSetup(this, card(page), null, this::mayEdit, state);
        }
        ProfileActionRow formActions = new ProfileActionRow(this);
        formActions.setTag("profile_form_actions");
        page.addView(formActions, new LinearLayout.LayoutParams(-1, -2));
        save = PersonUi.action(formActions, R.string.profile_save, this::save, true); save.setTag("profile_save");
        PersonUi.action(formActions, android.R.string.cancel, this::finish, false).setTag("profile_cancel");
        renderAvatar(); buttons();
        com.subhub.app.util.InputFocusPolicy.openActivityForReading(this);
    }

    private void renderAvatar() {
        portrait.removeAllViews();
        ProfileData value = new ProfileData(loadProfile().name, "", avatar);
        ProfileAvatarView view = new ProfileAvatarView(this, value); view.setTag("profile_avatar_preview");
        portrait.addView(view, new LinearLayout.LayoutParams(dp(96), dp(96)));
    }

    private void buttons() {
        if (save == null) return;
        save.setEnabled(!importing && !saving); change.setEnabled(!importing && !saving);
        remove.setVisibility(avatar.isEmpty() ? View.GONE : View.VISIBLE);
        remove.setEnabled(!importing && !saving);
    }

    private void save() {
        if (importing || saving || !mayEdit()) return;
        int enteredAge;
        try { enteredAge = ProfileData.parseAge(age.getText().toString()); }
        catch (IllegalArgumentException invalid) { age.setError(getString(R.string.profile_age_invalid)); return; }
        if (internal) {
            saveInternal(new ProfileData(name.getText().toString(), about.getText().toString(), avatar,
                    gender.getText().toString(), enteredAge));
            return;
        }
        if (!ProfileStore.save(this, dom, name.getText().toString(), about.getText().toString(), avatar,
                gender.getText().toString(), enteredAge)) {
            notice(getString(R.string.profile_save_failed)); return;
        }
        boolean openGag = features != null && features.enablesGag();
        if (features != null && !features.apply(this)) {
            notice(getString(R.string.profile_save_failed)); return;
        }
        com.subhub.app.remote.RemoteFeature.get(this).changed();
        if (openGag && com.subhub.app.atmosphere.GagActivity.canConfigure(this)) {
            startActivity(new android.content.Intent(this, com.subhub.app.atmosphere.GagActivity.class));
        }
        finish();
    }

    private boolean mayEdit() {
        return internal ? internalData.ownerDataReady() && DummySubPreview.isAllowed(this)
                && DummySubPreview.current(this, internalGeneration, internalControllerRevision)
                : ProfileStore.mayEdit(this, dom);
    }
    private ProfileData loadProfile() {
        return internal ? com.subhub.app.internal.InternalProfileAdapter.toProfileData(internalBase.profile)
                : ProfileStore.load(this, dom);
    }
    private void saveInternal(ProfileData profile) {
        saving = true; buttons();
        InternalSubData backend = internalData;
        com.subhub.app.internal.InternalSubRecord expected = internalBase;
        android.content.Context physical = getApplicationContext();
        String generation = internalGeneration; long revision = internalControllerRevision;
        data.loadSerial("internal-profile-save", () -> {
            return backend.await(backend.ownerData().submitEditor(generation, revision, access -> {
                if (!expected.equals(access.record())) throw new SecurityException("Internal profile changed");
                return com.subhub.app.internal.InternalProfileAdapter.save(backend.store, expected, profile);
            }));
        }, saved -> {
            saving = false;
            if (!mayEdit()) { finish(); return; }
            internalBase = saved; finish();
        }, failure -> {
            saving = false;
            if (!mayEdit()) { finish(); return; }
            buttons(); notice(getString(R.string.internal_sub_save_failed));
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (!mayEdit()) finish();
        else {
            if (internal) {
                findViewById(android.R.id.content).setVisibility(View.VISIBLE);
                authorityClock.post(checkInternalAuthority);
            }
            if (fullAccess != null) fullAccess.resume();
        }
    }

    @Override protected void onPause() {
        authorityClock.removeCallbacks(checkInternalAuthority);
        if (fullAccess != null) fullAccess.pause();
        if (internal) findViewById(android.R.id.content).setVisibility(View.INVISIBLE);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (internal && internalBase != null) state.putLong("internal_profile_base", internalBase.revision);
        if (name != null) {
            state.putString("profile_name", name.getText().toString());
            state.putString("profile_about", about.getText().toString());
            state.putString("profile_gender", gender.getText().toString());
            state.putString("profile_age", age.getText().toString());
            state.putString("profile_avatar", avatar);
        }
        if (features != null) features.saveState(state);
        if (fullAccess != null) fullAccess.saveState(state);
        super.onSaveInstanceState(state);
    }
}
