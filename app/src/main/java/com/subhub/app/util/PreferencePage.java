package com.subhub.app.util;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.subhub.app.R;
import java.util.function.Consumer;

/** Small themed composition helpers for focused settings and media screens. */
public abstract class PreferencePage extends AppCompatActivity {
    protected LinearLayout page;
    protected void page(int title) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(12), dp(20), dp(32));
        scroll.addView(page); setContentView(scroll);
        View header = getLayoutInflater().inflate(R.layout.view_secondary_header, page, false);
        page.addView(header);
        PrimaryHeader.bindSecondary(header, title, false);
        PrimaryHeader.backButton(header).setOnClickListener(view -> finish());
    }
    protected LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card); card.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(14);
        parent.addView(card, params); return card;
    }
    protected TextView text(LinearLayout parent, CharSequence value, int size, boolean muted) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(getColor(muted ? R.color.text_secondary : R.color.text_primary));
        view.setPadding(0, dp(5), 0, dp(7)); view.setLineSpacing(dp(2), 1);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    protected Button button(LinearLayout parent, CharSequence label, Runnable action) {
        MaterialButton button = new MaterialButton(this); button.setText(label); button.setAllCaps(false);
        button.setMinHeight(dp(48)); button.setOnClickListener(v -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2)); return button;
    }
    protected View toggle(LinearLayout parent, int label, boolean enabled, Consumer<Boolean> changed) {
        StateToggle toggle = new StateToggle(this); toggle.setText(label);
        toggle.setTextColor(getColor(R.color.text_primary)); toggle.setChecked(enabled);
        toggle.setOnCheckedChangeListener((button, checked) -> changed.accept(checked));
        parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2)); return toggle;
    }
    protected EditText input(LinearLayout parent, int hint, int inputType) {
        EditText input = new EditText(this); input.setSingleLine(); input.setHint(hint);
        input.setInputType(inputType); input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_secondary)); input.setMinHeight(dp(48));
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    protected void notice(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    protected int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
