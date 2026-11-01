package com.subhub.app.people;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.commitment.ServiceCountdownView;

/** Reuses the service's actual clock and controls in the compact overview card. */
final class OverviewSession {
    private final AppCompatActivity activity;
    private final LinearLayout service, active, status, commitment;
    private final ServiceCountdownView clock;
    private final View originalCaption;
    private final ViewGroup.LayoutParams originalClockSize;
    private final LinearLayout header, time;
    private final TextView state;
    private final View stateDot;
    private final int[] originalPadding, commitmentPadding;
    private final int originalTopMargin;
    private boolean compact;
    private int renderedState;

    OverviewSession(AppCompatActivity activity) {
        this.activity = activity;
        service = activity.findViewById(R.id.home_service_widget);
        active = activity.findViewById(R.id.commitment_active_panel);
        status = activity.findViewById(R.id.protection_status_row);
        commitment = activity.findViewById(R.id.commitment_card);
        clock = activity.findViewById(R.id.service_countdown);
        originalClockSize = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) clock.getLayoutParams());
        originalCaption = active.getChildAt(0);
        originalPadding = new int[]{service.getPaddingStart(), service.getPaddingTop(), service.getPaddingEnd(), service.getPaddingBottom()};
        originalTopMargin = ((ViewGroup.MarginLayoutParams) service.getLayoutParams()).topMargin;
        commitmentPadding = new int[]{commitment.getPaddingStart(), commitment.getPaddingTop(), commitment.getPaddingEnd(), commitment.getPaddingBottom()};
        header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = OverviewUi.text(activity, activity.getString(R.string.role_session), 16, true);
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout statusLine = new LinearLayout(activity); statusLine.setGravity(Gravity.CENTER_VERTICAL);
        stateDot = new View(activity); stateDot.setBackgroundResource(R.drawable.bg_status_dot);
        statusLine.addView(stateDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        state = OverviewUi.text(activity, activity.getString(R.string.overview_session_inactive), 12, false);
        state.setTag("overview_session_state");
        LinearLayout.LayoutParams stateSize = new LinearLayout.LayoutParams(-2, -2); stateSize.setMarginStart(dp(6));
        statusLine.addView(state, stateSize); header.addView(statusLine, new LinearLayout.LayoutParams(-2, -2));
        time = new LinearLayout(activity); time.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(activity); icon.setImageResource(R.drawable.ic_overview_clock);
        icon.setBackgroundResource(R.drawable.bg_profile_avatar); icon.setPadding(dp(14), dp(14), dp(14), dp(14));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent_text)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        time.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout copy = PersonUi.column(activity);
        LinearLayout.LayoutParams copySize = new LinearLayout.LayoutParams(0, -2, 1); copySize.setMarginStart(dp(14));
        time.addView(copy, copySize);
    }

    void bind(boolean enabled) {
        if (compact == enabled) return;
        compact = enabled;
        ViewGroup.MarginLayoutParams serviceSize = (ViewGroup.MarginLayoutParams) service.getLayoutParams();
        serviceSize.topMargin = enabled ? 0 : originalTopMargin; service.setLayoutParams(serviceSize);
        clock.setOverview(enabled);
        ((ViewGroup) clock.getParent()).removeView(clock);
        status.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) {
            service.setBackgroundResource(R.drawable.bg_overview_card);
            com.subhub.app.util.UiIdentity.cardPadding(service);
            commitment.setPadding(0, dp(8), 0, 0);
            boolean stacked = activity.getResources().getConfiguration().fontScale >= 1.3f;
            header.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            header.getChildAt(0).setLayoutParams(stacked ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -2, 1));
            service.addView(header, 0, new LinearLayout.LayoutParams(-1, -2));
            originalCaption.setVisibility(View.GONE);
            LinearLayout copy = (LinearLayout) time.getChildAt(1);
            copy.addView(clock, new LinearLayout.LayoutParams(-1, clockHeight()));
            TextView caption = OverviewUi.text(activity, activity.getString(R.string.overview_time_remaining), 13, false);
            caption.setTag("overview_time_caption"); copy.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            active.addView(time, new LinearLayout.LayoutParams(-1, -2));
        } else {
            service.removeView(header);
            active.removeView(time); ((LinearLayout) time.getChildAt(1)).removeAllViews();
            originalClockSize.height = dp(Math.round(180 * Math.max(1f, activity.getResources().getConfiguration().fontScale)));
            active.addView(clock, originalClockSize); originalCaption.setVisibility(View.VISIBLE);
            service.setBackgroundResource(R.drawable.bg_home_composition_stat);
            service.setPaddingRelative(originalPadding[0], originalPadding[1], originalPadding[2], originalPadding[3]);
            commitment.setPaddingRelative(commitmentPadding[0], commitmentPadding[1], commitmentPadding[2], commitmentPadding[3]);
        }
    }

    void state(boolean active, boolean paused) {
        int next = paused ? R.string.overview_session_paused : active
                ? R.string.overview_session_active : R.string.overview_session_inactive;
        if (renderedState == next) return;
        renderedState = next;
        state.setText(next);
        stateDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activity.getColor(
                active && !paused ? R.color.home_composition_current : R.color.text_muted)));
    }

    int clockHeight() { return dp(Math.round(44 * Math.max(1f, activity.getResources().getConfiguration().fontScale))); }
    private int dp(int value) { return PersonUi.dp(activity, value); }
}
