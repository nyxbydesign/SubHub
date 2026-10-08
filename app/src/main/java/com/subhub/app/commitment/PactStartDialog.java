package com.subhub.app.commitment;

import android.app.Activity;
import android.text.InputType;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.subhub.app.R;
import java.math.BigDecimal;

/** User reviews the permitted range before any deadline is drawn or protection is changed. */
public final class PactStartDialog {
    public interface Selection { void selected(long minimum, long maximum, boolean hidden); }
    private PactStartDialog() { }
    public static void show(Activity activity, long selectedDuration, Selection selection) {
        LinearLayout panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density); panel.setPadding(padding, padding, padding, padding);
        TextView explanation = new TextView(activity); explanation.setText(R.string.pact_options_help);
        explanation.setTextColor(activity.getColor(R.color.text_secondary)); panel.addView(explanation);
        com.subhub.app.util.StateToggle random = new com.subhub.app.util.StateToggle(activity); random.setText(R.string.pact_random_duration); panel.addView(random);
        LinearLayout range = new LinearLayout(activity); range.setOrientation(LinearLayout.VERTICAL); panel.addView(range);
        EditText minimum = field(activity, range, R.string.pact_minimum_hours, selectedDuration);
        EditText maximum = field(activity, range, R.string.pact_maximum_hours, Math.min(PactDuration.MAX, selectedDuration * 2));
        range.setVisibility(android.view.View.GONE);
        random.setOnCheckedChangeListener((button, checked) -> range.setVisibility(checked ? android.view.View.VISIBLE : android.view.View.GONE));
        com.subhub.app.util.StateToggle hidden = new com.subhub.app.util.StateToggle(activity); hidden.setText(R.string.pact_hide_countdown); panel.addView(hidden);
        AlertDialog dialog = com.subhub.app.util.ThemedDialogs.builder(activity).setTitle(R.string.pact_options_title).setView(panel)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.pact_confirm_start, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                long min = random.isChecked() ? PactDuration.hours(minimum.getText().toString()) : selectedDuration;
                long max = random.isChecked() ? PactDuration.hours(maximum.getText().toString()) : selectedDuration;
                PactDuration.validate(min, max);
                dialog.dismiss(); selection.selected(min, max, hidden.isChecked());
            } catch (IllegalArgumentException invalid) { maximum.setError(activity.getString(R.string.pact_range_invalid)); }
        }));
        dialog.show();
    }
    private static EditText field(Activity activity, LinearLayout parent, int label, long milliseconds) {
        TextView title = new TextView(activity); title.setText(label); title.setTextColor(activity.getColor(R.color.text_secondary)); parent.addView(title);
        EditText input = new EditText(activity); input.setHint(label); input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(BigDecimal.valueOf(milliseconds).divide(BigDecimal.valueOf(3_600_000)).stripTrailingZeros().toPlainString());
        parent.addView(input); return input;
    }
}
