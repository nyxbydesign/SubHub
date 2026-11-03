package com.subhub.app.people;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;

/** Person identity and device selection stay visible throughout profile navigation. */
public final class ProfileViews {
    private ProfileViews() {}

    public static String targetDiscriminator(Context context, PersonSnapshot person) {
        return targetDiscriminator(context, person.id, person.device);
    }

    /** Peer IDs carry a relationship generation after the stable 64-character device identity. */
    public static String targetDiscriminator(Context context, PersonId id, String device) {
        String label = device == null ? "" : device.trim();
        if (id.isLocal()) return label.isEmpty() ? context.getString(R.string.role_this_device) : label;
        String identity = id.value();
        if (identity.startsWith("remote_") && identity.length() > 72 && identity.charAt(71) == '_') {
            String peer = identity.substring(7, 71);
            if (peer.matches("[0-9a-f]{64}")) identity = peer.substring(0, 8) + "…" + peer.substring(60);
        }
        return label.isEmpty() ? identity : context.getString(R.string.profile_target_device_identity, label, identity);
    }

    public static void identity(LinearLayout parent, PersonSnapshot person) {
        Context context = parent.getContext();
        LinearLayout hero = PersonUi.card(parent); hero.setBackgroundResource(R.drawable.bg_home_composition_person);
        ProfilePreview identity = new ProfilePreview(context, person.profile, false, "person", false, null);
        View about = identity.findViewWithTag("person_about");
        if (about != null) about.setTag("person_about:" + person.id.value());
        hero.addView(identity, new LinearLayout.LayoutParams(-1, -2));
        connectionSummary(hero, person, "person_connection:" + person.id.value(), true);
        if (context instanceof android.app.Activity) {
            PersonId target = person.id.isLocal() ? new PersonId("journal_local_sub") : person.id;
            com.subhub.app.journal.JournalPreview.add(hero,
                    com.subhub.app.security.DeviceRolePreferences.isDomWorkspace(context), target,
                    () -> com.subhub.app.journal.JournalRoutes.profile((android.app.Activity) context, person.id));
        }
    }

    public static LinearLayout person(LinearLayout parent, PersonSnapshot person, Runnable open) {
        Context context = parent.getContext();
        LinearLayout card = PersonUi.card(parent);
        card.setBackgroundResource(R.drawable.bg_home_composition_person);
        String prefix = "roster:" + person.id.value();
        card.addView(ProfilePreview.roster(context, person.profile, prefix), new LinearLayout.LayoutParams(-1, -2));
        connectionSummary(card, person, prefix + "_connection", false);
        card.setOnClickListener(view -> open.run());
        card.setFocusable(true);
        card.setClickable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        card.setForeground(context.getDrawable(ripple.resourceId));
        card.setTag("person:" + person.id.value());
        ViewCompat.setScreenReaderFocusable(card, true);
        return card;
    }

    private static void connectionSummary(LinearLayout parent, PersonSnapshot person, String tag, boolean profile) {
        if (person.connection == PersonSnapshot.Connection.LOCAL) return;
        TextView state = PersonUi.text(parent, profile && person.connection == PersonSnapshot.Connection.OFFLINE
                ? parent.getContext().getString(R.string.role_connection_offline)
                : PersonUi.connection(parent.getContext(), person), false);
        state.setTag(tag); state.setTextSize(12); state.setPadding(0, PersonUi.dp(parent.getContext(), 10), 0, 0);
        if (profile) {
            lastOnline(parent, person.id, person.lastSyncedAt);
            if (person.sync.contract == PersonSnapshot.Freshness.STALE || person.sync.stats == PersonSnapshot.Freshness.STALE
                    || person.sync.runtime == PersonSnapshot.Freshness.STALE)
                PersonUi.text(parent, parent.getContext().getString(R.string.peer_cached_data), false)
                        .setTag("profile_cached_data:" + person.id.value());
        }
    }

    public static void lastOnline(LinearLayout parent, PersonId target, long observed) {
        if (target == null || target.isLocal()) return;
        Context context = parent.getContext(); long now = System.currentTimeMillis();
        CharSequence time = observed <= 0 || observed > now ? context.getString(R.string.remote_unknown)
                : android.text.format.DateUtils.formatDateTime(context, observed,
                        android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME
                                | android.text.format.DateUtils.FORMAT_SHOW_YEAR);
        TextView value = PersonUi.text(parent, context.getString(R.string.peer_last_online, time), false);
        value.setTextSize(12); value.setTag("profile_last_online:" + target.value());
    }

    public static View avatar(Context context, ProfileData profile) {
        return new ProfileAvatarView(context, profile);
    }

    public static void allowedFeatures(LinearLayout parent, PersonSnapshot person) {
        Context context = parent.getContext();
        LinearLayout card = FeatureConsentView.card(parent);
        card.setTag("person_allowed_features");
        if (person.featureConsent.isEmpty() || person.sync.runtime != PersonSnapshot.Freshness.CURRENT)
            PersonUi.text(card, context.getString(person.featureConsent.isEmpty()
                    ? R.string.role_unknown_data : R.string.role_stale_data), false);
        if (person.consentHardcoreOverride)
            PersonUi.text(card, context.getString(R.string.profile_hardcore_allowances), false);
        String[] keys = {"censor", "limits", "wallet", "subliminal", "popup"};
        int[] labels = {R.string.sub_censor_title, R.string.sub_limits_title, R.string.sub_wallet_title,
                R.string.sub_feature_whispers, R.string.popup_title};
        FeatureConsentView grid = new FeatureConsentView(context);
        card.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        for (int index = 0; index < keys.length; index++)
            grid.readOnly(keys[index], labels[index], person.featureConsent.get(keys[index]));
    }

    public static TextView avatar(Context context, String name) {
        TextView value = new TextView(context);
        String[] words = name.trim().split("\\s+");
        String initials = words.length == 0 || words[0].isEmpty() ? "?"
                : initial(words[0]) + (words.length > 1 ? initial(words[words.length - 1]) : "");
        value.setText(initials.toUpperCase(java.util.Locale.ROOT));
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                value, 10, 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        value.setTextColor(context.getColor(R.color.accent_text));
        value.setTypeface(null, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        value.setBackgroundResource(R.drawable.bg_profile_avatar);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return value;
    }

    private static String initial(String word) {
        return word.substring(0, word.offsetByCodePoints(0, 1));
    }
}
