package com.subhub.app.people;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** One identity presentation for own profiles and already-authorized paired projections. */
public final class ProfilePreview extends LinearLayout {
    public static final int AVATAR_DP = 72;
    private TextView about, more;
    private boolean expanded;
    private final Consumer<Boolean> expansion;
    private final ExpansionState state;
    private final String stateKey;
    private final android.view.ViewTreeObserver.OnPreDrawListener overflowObserver = () -> {
        if (about == null || about.getLayout() == null) return true;
        android.text.Layout layout = about.getLayout();
        boolean overflowing = layout.getLineCount() > 2 || layout.getLineCount() > 0
                && layout.getEllipsisCount(layout.getLineCount() - 1) > 0;
        int visibility = overflowing ? VISIBLE : GONE;
        if (more.getVisibility() == visibility) return true;
        more.setVisibility(visibility);
        return false;
    };

    public static final class ExpansionState extends androidx.lifecycle.ViewModel {
        final java.util.Map<String, Boolean> values = new java.util.HashMap<>();
    }

    public ProfilePreview(Context context, ProfileData profile, boolean dom, String prefix,
            boolean expanded, Consumer<Boolean> expansion) {
        super(context);
        this.expansion = expansion; stateKey = prefix + ":" + dom;
        state = context instanceof androidx.lifecycle.ViewModelStoreOwner
                ? new androidx.lifecycle.ViewModelProvider((androidx.lifecycle.ViewModelStoreOwner) context).get(ExpansionState.class) : null;
        this.expanded = state == null ? expanded : state.values.getOrDefault(stateKey, expanded);
        setOrientation(VERTICAL); setId(R.id.profile_preview);
        setTag("profile_preview:" + (dom ? "dom" : "sub"));
        LinearLayout identity = new LinearLayout(context); identity.setGravity(Gravity.CENTER_VERTICAL);
        View avatar = ProfileViews.avatar(context, profile); avatar.setTag(prefix + "_avatar");
        identity.addView(avatar, new LayoutParams(PersonUi.dp(context, AVATAR_DP), PersonUi.dp(context, AVATAR_DP)));
        TextView name = new TextView(context); name.setText(profile.name); name.setTag(prefix + "_name");
        name.setTextSize(26); name.setTextColor(context.getColor(R.color.text_primary));
        name.setTypeface(null, android.graphics.Typeface.BOLD); name.setLineSpacing(PersonUi.dp(context, 2), 1);
        ViewCompat.setAccessibilityHeading(name, true);
        LayoutParams nameSize = new LayoutParams(0, -2, 1); nameSize.setMarginStart(PersonUi.dp(context, 16));
        identity.addView(name, nameSize); addView(identity, new LayoutParams(-1, -2));
        List<String> details = new ArrayList<>();
        String role = context.getString(dom ? R.string.role_dom : R.string.role_sub);
        if (!profile.name.equals(role)) details.add(role);
        if (!profile.gender.isEmpty()) details.add(profile.gender);
        if (profile.age != 0) details.add(getResources().getQuantityString(R.plurals.profile_age_value, profile.age, profile.age));
        if (!details.isEmpty()) {
            TextView summary = PersonUi.text(this, String.join(" · ", details), false);
            summary.setTag(prefix + "_details"); summary.setTextSize(14);
            summary.setPadding(0, PersonUi.dp(context, 12), 0, 0);
        }
        if (!profile.about.isEmpty()) {
            TextView label = PersonUi.text(this, context.getString(R.string.profile_about), true);
            label.setPadding(0, PersonUi.dp(context, 18), 0, PersonUi.dp(context, 6));
            about = PersonUi.text(this, profile.about, false); about.setTag(prefix + "_about");
            about.setTextSize(15); about.setTextColor(context.getColor(R.color.text_primary));
            about.setPadding(0, 0, 0, 0); about.setLineSpacing(PersonUi.dp(context, 4), 1);
            more = PersonUi.action(this, this.expanded ? R.string.profile_show_less : R.string.profile_show_more,
                    () -> setExpanded(!this.expanded), false);
            more.setTag(prefix + "_about_expand"); more.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            more.setBackground(null); more.setPadding(0, 0, 0, 0); more.setVisibility(GONE);
            setExpanded(this.expanded);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(overflowObserver);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(overflowObserver);
        super.onDetachedFromWindow();
    }

    private void setExpanded(boolean value) {
        expanded = value;
        if (state != null) state.values.put(stateKey, value);
        if (about != null) {
            about.setMaxLines(value ? Integer.MAX_VALUE : 2);
            about.setEllipsize(value ? null : TextUtils.TruncateAt.END);
            more.setText(value ? R.string.profile_show_less : R.string.profile_show_more);
        }
        if (expansion != null) expansion.accept(value);
    }

    @Override protected Parcelable onSaveInstanceState() {
        Bundle state = new Bundle(); state.putParcelable("view", super.onSaveInstanceState());
        state.putBoolean("expanded", expanded); return state;
    }
    @Override protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof Bundle)) { super.onRestoreInstanceState(state); return; }
        Bundle saved = (Bundle) state;
        super.onRestoreInstanceState(saved.getParcelable("view")); setExpanded(saved.getBoolean("expanded"));
    }
}
