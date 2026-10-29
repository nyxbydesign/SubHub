package com.subhub.app.onboarding;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import com.subhub.app.R;
import com.subhub.app.security.DeviceRolePreferences;

/** The picker requests a role; its host retains every configuration/authentication guard. */
public final class OnboardingRolePickerView extends RadioGroup {
    public interface Listener { void requested(DeviceRolePreferences.Mode role); }
    private final DeviceRolePreferences.Mode selected;

    public OnboardingRolePickerView(Context context, DeviceRolePreferences.Mode selected, Listener listener) {
        super(context);
        this.selected = selected;
        setId(R.id.onboarding_role_choice);
        setTag("onboarding_role_picker");
        setOrientation(LinearLayout.VERTICAL);
        DeviceRolePreferences.Mode[] modes = {DeviceRolePreferences.Mode.SUB_ONLY,
                DeviceRolePreferences.Mode.DOM_ONLY, DeviceRolePreferences.Mode.BOTH};
        int[] labels = {R.string.onboarding_role_sub, R.string.onboarding_role_dom, R.string.onboarding_role_both};
        int[] icons = {R.drawable.ic_nav_censor, R.drawable.ic_nav_people, R.drawable.ic_nav_contract};
        for (int i = 0; i < modes.length; i++) {
            RadioButton button = new RadioButton(context);
            button.setId(android.view.View.generateViewId());
            button.setTag("device_role:" + modes[i].name());
            button.setText(labels[i]);
            button.setTextSize(22);
            button.setTypeface(null, android.graphics.Typeface.BOLD);
            button.setTextColor(context.getColor(R.color.text_primary));
            button.setButtonTintList(ColorStateList.valueOf(context.getColor(R.color.accent_text)));
            button.setMinHeight(dp(88));
            button.setPadding(dp(18), dp(16), dp(20), dp(16));
            button.setCompoundDrawablePadding(dp(16));
            Drawable icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, icons[i]);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(context.getColor(R.color.accent_text));
                icon.setBounds(0, 0, dp(32), dp(32));
                button.setCompoundDrawablesRelative(null, null, icon, null);
            }
            button.setBackgroundResource(R.drawable.bg_choice_card);
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(-1, -2);
            params.bottomMargin = dp(12);
            addView(button, params);
            DeviceRolePreferences.Mode requestedRole = modes[i];
            button.setOnClickListener(view -> {
                bind();
                listener.requested(requestedRole);
            });
        }
        bind();
    }

    private void bind() {
        clearCheck();
        for (int i = 0; i < getChildCount(); i++) {
            RadioButton item = (RadioButton) getChildAt(i);
            boolean chosen = selected != null && item.getTag().equals("device_role:" + selected.name());
            item.setSelected(chosen);
            if (chosen) check(item.getId());
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
