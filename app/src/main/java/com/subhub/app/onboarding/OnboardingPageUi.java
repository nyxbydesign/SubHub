package com.subhub.app.onboarding;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.settings.CensorPreviewView;

/** Shared native type, spacing and artwork keep the three walkthroughs visually coherent. */
public final class OnboardingPageUi {
    private OnboardingPageUi() { }

    public static void render(LinearLayout parent, OnboardingPage spec) {
        Context context = parent.getContext();
        parent.setTag("onboarding_page:" + spec.id);
        TextView workspace = text(parent, context.getString(spec.workspace == OnboardingPage.Workspace.SUB
                ? R.string.onboarding_sub_workspace : spec.workspace == OnboardingPage.Workspace.DOM
                ? R.string.onboarding_dom_workspace : R.string.onboarding_device_workspace), 13, true);
        workspace.setId(R.id.onboarding_workspace);
        workspace.setTextColor(context.getColor(R.color.accent_text));
        workspace.setTypeface(null, Typeface.BOLD);
        if (spec.kind != OnboardingPage.Kind.APPEARANCE && spec.kind != OnboardingPage.Kind.APPS
                && spec.kind != OnboardingPage.Kind.WALLET) {
            View graphic;
            if (spec.scene == OnboardingPage.Scene.CENSOR_SETTINGS) {
                CensorPreviewView preview = new CensorPreviewView(context, null);
                preview.setPhonePreview(true);
                preview.setTag("onboarding_graphic:CENSOR_SETTINGS");
                preview.setId(R.id.onboarding_graphic);
                graphic = preview;
            } else if (spec.scene == OnboardingPage.Scene.LOCAL_FEATURES) {
                graphic = new SetupFeaturePreviewView(context);
                graphic.setTag("setup-feature-overview");
                graphic.setId(R.id.onboarding_graphic);
            } else graphic = new OnboardingGraphic(context, spec.scene);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,
                    spec.scene == OnboardingPage.Scene.CENSOR_SETTINGS ? dp(context, 200) : -2);
            params.topMargin = dp(context, 8); params.bottomMargin = dp(context, 14);
            parent.addView(graphic, params);
        }
        TextView title = text(parent, context.getString(spec.title), 26, false);
        title.setTypeface(null, Typeface.BOLD);
        title.setId(R.id.tour_step_title);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        if (spec.description != 0) text(parent, context.getString(spec.description), 15, true);
        int index = 0;
        for (OnboardingPage.Detail detail : spec.details) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(0, dp(context, 12), 0, dp(context, 8));
            parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
            TextView number = new TextView(context);
            number.setText(Integer.toString(++index)); number.setTextSize(13);
            number.setTextColor(context.getColor(R.color.accent_text));
            number.setTypeface(null, Typeface.BOLD); number.setGravity(Gravity.CENTER);
            number.setBackgroundResource(R.drawable.bg_header_icon);
            number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(dp(context, 30), dp(context, 30));
            badge.setMarginEnd(dp(context, 12)); row.addView(number, badge);
            LinearLayout copy = new LinearLayout(context); copy.setOrientation(LinearLayout.VERTICAL);
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            text(copy, context.getString(detail.title), 16, false).setTypeface(null, Typeface.BOLD);
            text(copy, context.getString(detail.body), 14, true);
        }
    }

    static TextView text(LinearLayout parent, String value, int size, boolean muted) {
        Context context = parent.getContext();
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size);
        text.setTag("onboarding_copy");
        text.setTextColor(context.getColor(muted ? R.color.text_secondary : R.color.text_primary));
        text.setLineSpacing(dp(context, 2), 1);
        text.setPadding(0, dp(context, 3), 0, dp(context, 5));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2)); return text;
    }

    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
