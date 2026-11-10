package com.subhub.app.appmode;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import com.subhub.app.R;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.PersonUi;

/** Shared, non-animated daily-allowance meter for Profile and Overview. */
public final class AllowanceProgress {
    private AllowanceProgress() {}

    public static ProgressBar add(LinearLayout parent, PersonSnapshot.Allowance allowance) {
        // Unknown usage is text-only, never a reassuring empty meter.
        if (allowance.usedMillis < 0) return null;
        Context context = parent.getContext();
        ProgressBar progress = new ProgressBar(context, null, 0, R.style.Widget_SubHub_AllowanceProgress);
        progress.setMax(1000);
        progress.setProgress(allowance.budgetMillis <= 0 ? 0 : (int) Math.min(1000,
                allowance.usedMillis / (double) allowance.budgetMillis * 1000));
        progress.setContentDescription(context.getString(R.string.role_allowance_progress_description,
                allowance.label, allowance.usedMillis / 60_000L, allowance.displayMinutes()));
        progress.setTag("allowance_progress:" + allowance.packageName);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, PersonUi.dp(context, 10));
        size.setMarginStart(PersonUi.dp(context, 36));
        size.topMargin = PersonUi.dp(context, 2);
        size.bottomMargin = PersonUi.dp(context, 12);
        parent.addView(progress, size);
        return progress;
    }
}
