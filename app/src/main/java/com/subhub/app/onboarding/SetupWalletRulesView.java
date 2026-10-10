package com.subhub.app.onboarding;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.PenancePolicy;
import com.subhub.app.util.StateToggle;
import com.subhub.app.util.UiIdentity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/** A draft editor for just the five tribute rules; full Wallet policy stays in Wallet settings. */
final class SetupWalletRulesView extends LinearLayout {
    private static final PenanceInfraction[] RULES = {
        PenanceInfraction.NEW_DETECTION, PenanceInfraction.CENSORED_DWELL,
        PenanceInfraction.CENSORED_TAP, PenanceInfraction.WATCHED_APP_OPEN,
        PenanceInfraction.TAMPER_ATTEMPT
    };
    private static final int[] TITLES = {
        R.string.penance_rule_detection_title, R.string.penance_rule_dwell_title,
        R.string.penance_rule_tap_title, R.string.penance_rule_app_open_title,
        R.string.penance_rule_tamper_title
    };
    private final Map<PenanceInfraction, StateToggle> toggles = new EnumMap<>(PenanceInfraction.class);
    private final Map<PenanceInfraction, EditText> amounts = new EnumMap<>(PenanceInfraction.class);
    private final Map<PenanceInfraction, EditText> timings = new EnumMap<>(PenanceInfraction.class);
    private final BooleanSupplier editable;
    private final PenanceManager manager;
    private final SetupWalletGraphic graphic;
    private boolean dirty;

    SetupWalletRulesView(Context context, Bundle draft, BooleanSupplier editable) {
        super(context);
        this.editable = editable;
        manager = new PenanceManager(context);
        setOrientation(VERTICAL);
        setTag("setup-wallet-rules");
        graphic = new SetupWalletGraphic(context);
        addView(graphic, new LayoutParams(-1, dp(116)));
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(12), dp(4), dp(12), dp(4));
        addView(card, new LayoutParams(-1, -2));
        for (int index = 0; index < RULES.length; index++) {
            PenanceInfraction rule = RULES[index];
            if (index > 0) {
                View divider = new View(context);
                divider.setBackgroundColor(context.getColor(R.color.outline_subtle));
                card.addView(divider, new LayoutParams(-1, dp(1)));
            }
            LinearLayout row = new RuleRow(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            card.addView(row, new LayoutParams(-1, -2));
            LinearLayout label = new LinearLayout(context);
            label.setOrientation(VERTICAL);
            LayoutParams labelParams = new LayoutParams(0, -2, 1);
            labelParams.setMarginEnd(dp(8));
            row.addView(label, labelParams);
            TextView title = label(label, context.getString(TITLES[index]), R.dimen.ui_text_body);
            title.setTextColor(context.getColor(R.color.text_primary));
            if (rule == PenanceInfraction.NEW_DETECTION) {
                timing(label, rule, R.string.tour_wallet_every, R.string.tour_wallet_censors,
                        manager.getDetectionBatch(), draft);
            } else if (rule == PenanceInfraction.CENSORED_DWELL) {
                timing(label, rule, 0, R.string.tour_wallet_seconds_still,
                        manager.getDwellSeconds(), draft);
            } else if (rule == PenanceInfraction.TAMPER_ATTEMPT) {
                timing(label, rule, R.string.tour_wallet_cooldown, R.string.tour_wallet_minutes,
                        manager.getTamperCooldownMinutes(), draft);
            }
            LinearLayout actions = new LinearLayout(context);
            actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            row.addView(actions, new LayoutParams(-2, -2));
            LinearLayout money = new LinearLayout(context);
            money.setGravity(Gravity.CENTER_VERTICAL);
            money.setAddStatesFromChildren(true);
            money.setBackgroundResource(R.drawable.bg_input);
            money.setPadding(dp(6), 0, 0, 0);
            actions.addView(money, new LayoutParams(dp(Math.round(76 * Math.max(1f,
                    getResources().getConfiguration().fontScale))), -2));
            TextView symbol = label(money, "EUR".equals(manager.getCurrency()) ? "€" : "$", R.dimen.ui_text_body);
            symbol.setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.control_text));
            EditText amount = input(money, 0, false);
            amount.setBackground(null);
            amount.setLayoutParams(new LayoutParams(0, -2, 1));
            amount.setTag("setup-wallet-amount:" + rule.preferenceKey());
            amount.setContentDescription(context.getString(TITLES[index]) + ", "
                    + manager.getCurrency() + ", " + context.getString(R.string.penance_rule_cost_label));
            amount.setText(draft == null ? String.format(Locale.ROOT, "%.2f",
                    manager.getInfractionCents(rule) / 100.0)
                    : draft.getString(rule.preferenceKey() + "_amount", "1.00"));
            amounts.put(rule, amount);
            StateToggle toggle = new StateToggle(context);
            toggle.setTag("setup-wallet-toggle:" + rule.preferenceKey());
            toggle.setContentDescription(context.getString(TITLES[index]));
            toggle.setChecked(draft == null ? manager.isInfractionEnabled(rule)
                    : draft.getBoolean(rule.preferenceKey() + "_enabled"));
            toggle.setEnabled(editable.getAsBoolean());
            LayoutParams toggleParams = new LayoutParams(-2, -2);
            toggleParams.setMarginStart(dp(8));
            actions.addView(toggle, toggleParams);
            toggles.put(rule, toggle);
            updateEnabled(rule);
            amount.addTextChangedListener(watcher());
            toggle.setOnCheckedChangeListener((button, checked) -> {
                dirty = true;
                updateEnabled(rule);
            });
        }
        dirty = draft != null && draft.getBoolean("dirty");
    }

    private void timing(LinearLayout parent, PenanceInfraction rule, int before, int after,
            int value, Bundle draft) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, new LayoutParams(-1, -2));
        if (before != 0) label(row, getContext().getString(before), R.dimen.ui_text_label);
        EditText input = input(row, dp(42), true);
        input.setTag("setup-wallet-timing:" + rule.preferenceKey());
        input.setContentDescription(getContext().getString(rule == PenanceInfraction.NEW_DETECTION
                ? R.string.penance_detection_batch_label : rule == PenanceInfraction.CENSORED_DWELL
                ? R.string.penance_dwell_seconds_label : R.string.penance_tamper_cooldown_label));
        input.setText(draft == null ? Integer.toString(value)
                : draft.getString(rule.preferenceKey() + "_timing", Integer.toString(value)));
        label(row, getContext().getString(after), R.dimen.ui_text_label);
        input.addTextChangedListener(watcher());
        timings.put(rule, input);
    }

    private TextView label(LinearLayout parent, String value, int size) {
        TextView label = new TextView(getContext());
        UiIdentity.textSize(label, size);
        label.setTextColor(getContext().getColor(R.color.text_secondary));
        label.setText(value);
        parent.addView(label, new LayoutParams(-2, -2));
        return label;
    }

    private EditText input(LinearLayout parent, int width, boolean integer) {
        EditText input = (EditText) LayoutInflater.from(getContext())
                .inflate(R.layout.view_preference_input, parent, false);
        input.setSingleLine();
        UiIdentity.inputType(input, InputType.TYPE_CLASS_NUMBER
                | (integer ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL));
        input.setGravity(Gravity.CENTER);
        input.setPadding(dp(integer ? 3 : 6), dp(4), dp(integer ? 3 : 6), dp(4));
        input.setMinHeight(dp(48));
        UiIdentity.textSize(input, integer ? R.dimen.ui_text_label : R.dimen.ui_text_body);
        LayoutParams params = new LayoutParams(width, -2);
        if (integer) params.setMargins(dp(4), dp(2), dp(4), dp(2));
        parent.addView(input, params);
        return input;
    }

    private TextWatcher watcher() {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { dirty = true; }
            @Override public void afterTextChanged(Editable text) {}
        };
    }

    private void updateEnabled(PenanceInfraction rule) {
        boolean enabled = editable.getAsBoolean() && toggles.get(rule).isChecked();
        amounts.get(rule).setEnabled(enabled);
        LinearLayout money = (LinearLayout) amounts.get(rule).getParent();
        money.setEnabled(enabled);
        money.getChildAt(0).setEnabled(enabled);
        if (timings.containsKey(rule)) timings.get(rule).setEnabled(enabled);
    }

    Bundle snapshot() {
        Bundle result = new Bundle();
        result.putBoolean("dirty", dirty);
        for (PenanceInfraction rule : RULES) {
            result.putBoolean(rule.preferenceKey() + "_enabled", toggles.get(rule).isChecked());
            result.putString(rule.preferenceKey() + "_amount", amounts.get(rule).getText().toString());
            if (timings.containsKey(rule))
                result.putString(rule.preferenceKey() + "_timing", timings.get(rule).getText().toString());
        }
        return result;
    }

    boolean applyRules() {
        if (!dirty || !editable.getAsBoolean()) return true;
        Map<PenanceInfraction, Integer> enabled = new EnumMap<>(PenanceInfraction.class);
        int batch = manager.getDetectionBatch(), dwell = manager.getDwellSeconds();
        int cooldown = manager.getTamperCooldownMinutes();
        for (PenanceInfraction rule : RULES) {
            if (!toggles.get(rule).isChecked()) continue;
            EditText amount = amounts.get(rule);
            Integer cents = parseAmount(amount.getText().toString());
            int maximum = Math.min(PenancePolicy.MAX_STRIKE_CENTS, manager.getDailyCapCents());
            if (cents == null || cents < PenancePolicy.MIN_STRIKE_CENTS || cents > maximum) {
                amount.setError(getContext().getString(R.string.tour_wallet_amount_invalid,
                        manager.money(PenancePolicy.MIN_STRIKE_CENTS), manager.money(maximum)));
                amount.requestFocus();
                return false;
            }
            enabled.put(rule, cents);
            if (!timings.containsKey(rule)) continue;
            EditText timing = timings.get(rule);
            int min = rule == PenanceInfraction.CENSORED_DWELL ? PenancePolicy.MIN_DWELL_SECONDS : 1;
            int max = rule == PenanceInfraction.NEW_DETECTION ? PenancePolicy.MAX_DETECTION_BATCH
                    : rule == PenanceInfraction.CENSORED_DWELL ? PenancePolicy.MAX_DWELL_SECONDS
                    : PenanceManager.MAX_TAMPER_COOLDOWN_MINUTES;
            Integer value = parseInteger(timing.getText().toString());
            if (value == null || value < min || value > max) {
                timing.setError(getContext().getString(R.string.tour_wallet_timing_invalid, min, max));
                timing.requestFocus();
                return false;
            }
            if (rule == PenanceInfraction.NEW_DETECTION) batch = value;
            else if (rule == PenanceInfraction.CENSORED_DWELL) dwell = value;
            else cooldown = value;
        }
        // Paid pauses, caps and correction timing are outside this compact editor.
        if (manager.isInfractionEnabled(PenanceInfraction.PAID_PAUSE))
            enabled.put(PenanceInfraction.PAID_PAUSE, manager.getInfractionCents(PenanceInfraction.PAID_PAUSE));
        manager.configure(true, enabled, manager.getDailyCapCents(), manager.getWeeklyCapCents(),
                manager.getMercyMinutes(), dwell, batch, cooldown);
        dirty = false;
        return true;
    }

    void fitGraphic(int spareHeight) {
        int height = Math.max(dp(48), Math.min(dp(132), graphic.getHeight() + spareHeight));
        if (height != graphic.getLayoutParams().height) {
            graphic.setLayoutParams(new LayoutParams(-1, height));
        }
    }

    static String encodeDraft(Bundle draft) {
        if (draft == null) return "";
        JSONObject json = new JSONObject();
        try {
            for (String key : draft.keySet()) json.put(key, draft.get(key));
        } catch (JSONException error) { throw new IllegalStateException(error); }
        return json.toString();
    }

    static Bundle readDraft(SharedPreferences preferences) {
        String encoded = preferences.getString("draft_wallet_rules", "");
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(encoded);
            Bundle draft = new Bundle();
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.get(key);
                if (value instanceof Boolean) draft.putBoolean(key, (Boolean) value);
                else if (value instanceof String) draft.putString(key, (String) value);
            }
            return draft;
        } catch (JSONException error) { return null; }
    }

    private static Integer parseAmount(String value) {
        try { return new BigDecimal(value.trim().replace(',', '.')).movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY).intValueExact(); }
        catch (ArithmeticException | NumberFormatException error) { return null; }
    }

    private static Integer parseInteger(String value) {
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException error) { return null; }
    }

    /** Keep labels whole; give the controls their own row on narrow, enlarged-text screens. */
    private static final class RuleRow extends LinearLayout {
        RuleRow(Context context) { super(context); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = MeasureSpec.getSize(widthSpec);
            float density = getResources().getDisplayMetrics().density;
            float fontScale = Math.max(1f, getResources().getConfiguration().fontScale);
            boolean stacked = available < Math.round((218 * fontScale + 80) * density);
            setOrientation(stacked ? VERTICAL : HORIZONTAL);
            LayoutParams label = (LayoutParams) getChildAt(0).getLayoutParams();
            label.width = stacked ? -1 : 0;
            label.weight = stacked ? 0 : 1;
            label.setMarginEnd(stacked ? 0 : Math.round(8 * density));
            LayoutParams actions = (LayoutParams) getChildAt(1).getLayoutParams();
            actions.width = stacked ? -1 : -2;
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
