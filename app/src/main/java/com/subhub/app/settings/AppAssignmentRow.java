package com.subhub.app.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.CompoundButtonCompat;
import com.subhub.app.R;

/** Compact independent assignments; large text gets a labeled second line instead of clipping. */
public final class AppAssignmentRow extends LinearLayout {
    private final LinearLayout identity;
    private final LinearLayout choices;
    private final TextView[] captions = new TextView[3];
    private final CheckBox[] checks = new CheckBox[3];
    private boolean stacked;

    public AppAssignmentRow(Context context, String appName, String packageName,
            android.graphics.drawable.Drawable appIcon, boolean[] selected) {
        super(context);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable row = shape(context.getColor(R.color.surface), 12);
        row.setStroke(dp(1), context.getColor(R.color.outline_subtle));
        setBackground(row);
        identity = new LinearLayout(context);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(context);
        icon.setImageDrawable(appIcon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(icon, new LayoutParams(dp(28), dp(28)));
        TextView name = new TextView(context);
        name.setText(appName);
        name.setTextColor(context.getColor(R.color.text_primary));
        name.setTextSize(12);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setContentDescription(appName + ", " + packageName);
        LayoutParams nameParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        nameParams.setMarginStart(dp(6));
        identity.addView(name, nameParams);
        addView(identity);
        choices = new LinearLayout(context);
        int[] modules = {R.string.app_selection_censor, R.string.app_selection_limit,
                R.string.app_selection_subliminal};
        for (int index = 0; index < 3; index++) {
            LinearLayout slot = new LinearLayout(context);
            slot.setOrientation(VERTICAL);
            slot.setGravity(Gravity.CENTER);
            TextView caption = new TextView(context);
            caption.setText(modules[index]);
            caption.setTextSize(12);
            caption.setTextColor(context.getColor(R.color.text_secondary));
            caption.setGravity(Gravity.CENTER);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            captions[index] = caption;
            slot.addView(caption, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            CheckBox check = new CheckBox(context);
            android.graphics.drawable.Drawable indicator = check.getButtonDrawable();
            if (indicator != null) {
                int extra = Math.max(0, dp(48) - indicator.getIntrinsicWidth());
                // Detach before wrapping so CompoundButton cannot clear the inner callback.
                check.setButtonDrawable(null);
                check.setButtonDrawable(new InsetDrawable(indicator, extra / 2, 0,
                        extra - extra / 2, 0));
            }
            check.setMinWidth(dp(48));
            check.setMinHeight(dp(48));
            check.setMinimumWidth(dp(48));
            check.setMinimumHeight(dp(48));
            check.setGravity(Gravity.CENTER_VERTICAL);
            check.setPadding(0, 0, 0, 0);
            check.setContentDescription(appName + ", " + context.getString(modules[index]));
            check.setTag("assignment:" + packageName + ":" + index);
            CompoundButtonCompat.setButtonTintList(check, context.getColorStateList(R.color.toggle_tint));
            StateListDrawable surfaces = new StateListDrawable();
            surfaces.addState(new int[]{-android.R.attr.state_enabled},
                    shape(context.getColor(R.color.surface_disabled), 12));
            GradientDrawable focused = shape(context.getColor(R.color.control_checked_surface), 12);
            focused.setStroke(dp(2), context.getColor(R.color.control_focus));
            surfaces.addState(new int[]{android.R.attr.state_focused}, focused);
            surfaces.addState(new int[]{android.R.attr.state_checked},
                    shape(context.getColor(R.color.control_checked_surface), 12));
            surfaces.addState(new int[]{}, shape(0x0017131E, 12));
            check.setBackground(new RippleDrawable(ColorStateList.valueOf(
                    context.getColor(R.color.control_pressed_surface)),
                    surfaces, shape(0xFFFFFFFF, 12)));
            check.setChecked(selected[index]);
            checks[index] = check;
            slot.addView(check, new LayoutParams(dp(48), dp(48)));
            choices.addView(slot);
        }
        addView(choices);
        arrange(false);
    }

    public CheckBox choice(int index) { return checks[index]; }
    public boolean isStacked() { return stacked; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        boolean next = MeasureSpec.getSize(widthSpec) < dp(280)
                || getResources().getConfiguration().fontScale > 1.4f;
        if (next != stacked) arrange(next);
        super.onMeasure(widthSpec, heightSpec);
    }

    private void arrange(boolean next) {
        stacked = next;
        setOrientation(next ? VERTICAL : HORIZONTAL);
        identity.setLayoutParams(new LayoutParams(next ? LayoutParams.MATCH_PARENT : 0,
                LayoutParams.WRAP_CONTENT, next ? 0 : 1));
        choices.setLayoutParams(new LayoutParams(next ? LayoutParams.MATCH_PARENT : dp(168),
                LayoutParams.WRAP_CONTENT));
        for (int index = 0; index < 3; index++) {
            captions[index].setVisibility(next ? VISIBLE : GONE);
            choices.getChildAt(index).setLayoutParams(new LayoutParams(next ? 0 : dp(56),
                    LayoutParams.WRAP_CONTENT, next ? 1 : 0));
        }
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
