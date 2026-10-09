package com.subhub.app.util;

import android.content.Context;
import android.view.*;
import android.widget.*;

import androidx.core.view.ViewCompat;

import com.subhub.app.R;

/** Shared expandable settings card, with one header, summary and content owner. */
public final class ExpandableSectionView extends LinearLayout {
    private final LinearLayout header, content;
    private final TextView summary, arrow;
    private boolean summaryVisible = true;

    public ExpandableSectionView(Context context, String key, int title, int icon) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_card);
        setPadding(dp(14), dp(8), dp(14), dp(10));
        LayoutInflater.from(context).inflate(R.layout.view_expandable_section, this, true);
        header = findViewById(R.id.section_header);
        content = findViewById(R.id.section_content);
        summary = findViewById(R.id.section_summary);
        arrow = findViewById(R.id.section_arrow);
        ((TextView) findViewById(R.id.section_title)).setText(title);
        ((ImageView) findViewById(R.id.section_icon)).setImageResource(icon);
        header.setTag("settings:" + key);
        ViewCompat.setScreenReaderFocusable(header, true);
        setExpanded(false);
    }

    public LinearLayout header() {
        return header;
    }

    public LinearLayout content() {
        return content;
    }

    public TextView summary() {
        return summary;
    }

    public void setSummaryVisible(boolean visible) {
        summaryVisible = visible;
    }

    public void setExpanded(boolean expanded) {
        content.setVisibility(expanded ? VISIBLE : GONE);
        arrow.setText(expanded ? "⌃" : "⌄");
        summary.setVisibility(!expanded && summaryVisible ? VISIBLE : GONE);
        ViewCompat.setStateDescription(
                header,
                getContext()
                        .getString(
                                expanded
                                        ? R.string.keyholder_expanded
                                        : R.string.keyholder_collapsed));
    }

    public void addControls(View... controls) {
        for (View control : controls) {
            if (control.getParent() != null) ((ViewGroup) control.getParent()).removeView(control);
            if (control instanceof LinearLayout) {
                control.setBackground(null);
                control.setPadding(0, 0, 0, 0);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(6);
            content.addView(control, params);
        }
    }

    public void reveal() {
        post(
                () ->
                        requestRectangleOnScreen(
                                new android.graphics.Rect(
                                        0, 0, getWidth(), Math.min(getHeight(), dp(280))),
                                false));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
