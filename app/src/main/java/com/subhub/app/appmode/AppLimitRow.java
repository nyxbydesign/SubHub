package com.subhub.app.appmode;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.subhub.app.R;

/** One app's retained daily allowance and live recorded usage, with no nested card surface. */
final class AppLimitRow extends LinearLayout {
    final EditText allowance;
    private final TextView usage;
    private final ProgressBar progress;
    private String receipt = "";

    AppLimitRow(Context context, String packageName, String name, Drawable icon, String minutes) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(0, dp(10), 0, dp(10));
        boolean large = getResources().getConfiguration().fontScale >= 1.35f;
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LayoutParams(-1, -2));
        ImageView image = new ImageView(context);
        image.setImageDrawable(icon);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(image, new LayoutParams(dp(32), dp(32)));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(VERTICAL);
        LayoutParams labelParams = new LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(10));
        labelParams.setMarginEnd(dp(10));
        header.addView(labels, labelParams);
        TextView title = text(name, 14, false);
        title.setTypeface(null, Typeface.BOLD);
        labels.addView(title, new LayoutParams(-1, -2));
        usage = text("", 12, true);
        LayoutParams usageParams = new LayoutParams(-1, -2);
        usageParams.topMargin = dp(3);
        labels.addView(usage, usageParams);
        LinearLayout field = new LinearLayout(context);
        field.setOrientation(large ? HORIZONTAL : VERTICAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        TextView unit = text(context.getString(R.string.limits_minutes_per_day), 11, true);
        field.addView(unit, large ? new LayoutParams(0, -2, 1) : new LayoutParams(-1, -2));
        allowance = new androidx.appcompat.widget.AppCompatEditText(context);
        allowance.setId(View.generateViewId());
        allowance.setTag("limit:" + packageName);
        unit.setLabelFor(allowance.getId());
        allowance.setContentDescription(
                context.getString(R.string.app_timer_allowance_accessibility, name));
        allowance.setBackgroundResource(R.drawable.bg_input);
        allowance.setInputType(InputType.TYPE_CLASS_NUMBER);
        allowance.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        allowance.setSingleLine(true);
        allowance.setIncludeFontPadding(false);
        allowance.setTextColor(context.getColor(R.color.text_primary));
        allowance.setTextSize(14);
        allowance.setGravity(Gravity.CENTER);
        allowance.setPadding(dp(8), dp(8), dp(8), dp(8));
        allowance.setText(minutes);
        LayoutParams inputParams = new LayoutParams(large ? dp(104) : -1, dp(48));
        if (!large) inputParams.topMargin = dp(3);
        field.addView(allowance, inputParams);
        if (large) {
            LayoutParams fieldParams = new LayoutParams(-1, -2);
            fieldParams.topMargin = dp(6);
            addView(field, fieldParams);
        } else header.addView(field, new LayoutParams(dp(96), -2));
        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(
                android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_mid)));
        progress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        context.getColor(R.color.outline_subtle)));
        LayoutParams progressParams = new LayoutParams(-1, dp(4));
        progressParams.topMargin = dp(8);
        addView(progress, progressParams);
    }

    void renderUsage(String description, int percent, boolean limited) {
        String next = description + "|" + percent + "|" + limited;
        if (next.equals(receipt)) return;
        receipt = next;
        usage.setText(description);
        progress.setVisibility(limited ? VISIBLE : GONE);
        progress.setProgress(percent);
        progress.setContentDescription(description);
    }

    private TextView text(String value, int size, boolean secondary) {
        TextView label = new TextView(getContext());
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(
                getContext().getColor(secondary ? R.color.text_secondary : R.color.text_primary));
        return label;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
