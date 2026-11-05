package com.subhub.app.appmode;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonUi;
import com.subhub.app.remote.ExtraTimeRequests;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.PreferencePage;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/** A request never edits the configured daily allowance or grants time before a Dom decides. */
public final class ExtraTimeActivity extends PreferencePage {
    public static final String EXTRA_PACKAGE = "subhub.extra_time.package";
    private PersonId person;
    private AsyncUiScope data;
    private LinearLayout content;
    private ExtraTimeRequests.Snapshot snapshot;
    private final Bundle minuteDrafts = new Bundle();
    private final Set<String> pendingActions = new HashSet<>();
    private final Handler clock = new Handler(Looper.getMainLooper());
    private boolean resumed, focused;
    private String rendered = "";
    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refresh();
            clock.postDelayed(this, 2_000L);
        }
    };

    public static Intent intent(Context context, PersonId person, String packageName) {
        Intent intent = new Intent(context, ExtraTimeActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, person.value());
        if (packageName != null && !packageName.isEmpty()) intent.putExtra(EXTRA_PACKAGE, packageName);
        return intent;
    }

    public static void open(Context context, PersonId person, String packageName) {
        Intent intent = intent(context, person, packageName);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { person = PersonRoutes.requireId(getIntent()); }
        catch (RuntimeException invalid) { finish(); return; }
        if (state != null) {
            Bundle drafts = state.getBundle("extra_time_minutes");
            if (drafts != null) minuteDrafts.putAll(drafts);
        }
        page(R.string.extra_time_title);
        content = PersonUi.column(this);
        content.setFocusableInTouchMode(true);
        page.addView(content, new LinearLayout.LayoutParams(-1, -2));
        data = AsyncUiScope.forPage(this);
        PersonUi.text(content, getString(R.string.role_loading), false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (data == null) return;
        resumed = true;
        clock.removeCallbacks(refreshTick);
        clock.post(refreshTick);
    }

    @Override protected void onPause() {
        resumed = false;
        clock.removeCallbacks(refreshTick);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBundle("extra_time_minutes", new Bundle(minuteDrafts));
        super.onSaveInstanceState(state);
    }

    private void refresh() {
        if (data.isPending("extra-time-snapshot") || !pendingActions.isEmpty()) return;
        Context application = getApplicationContext();
        PersonId target = person;
        data.load("extra-time-snapshot", () -> new ExtraTimeRequests(application, target)
                .snapshot(System.currentTimeMillis()), this::render, this::failed);
    }

    private void render(ExtraTimeRequests.Snapshot value) {
        snapshot = value;
        String signature = signature(value) + pendingActions;
        if (signature.equals(rendered)) return;
        rendered = signature;
        View previousFocus = getCurrentFocus();
        String focusTag = previousFocus instanceof EditText && previousFocus.getTag() instanceof String
                ? (String) previousFocus.getTag() : null;
        int selection = previousFocus instanceof EditText ? ((EditText) previousFocus).getSelectionStart() : 0;
        android.widget.ScrollView scroll = (android.widget.ScrollView) page.getParent();
        int scrollY = scroll.getScrollY();
        content.removeAllViews();
        LinearLayout intro = ExtraTimeViews.card(content, "extra_time_intro");
        PersonUi.text(intro, getString(value.dom ? R.string.extra_time_dom_intro : R.string.extra_time_sub_intro), false);
        if (value.dom && value.personName != null && !value.personName.isEmpty())
            ExtraTimeViews.status(intro, getString(R.string.extra_time_for_person, value.personName), true);
        PersonUi.text(intro, getString(R.string.extra_time_today_only), false);
        if (value.unavailableReason != null && !value.unavailableReason.isEmpty()) {
            PersonUi.text(intro, value.unavailableReason, false);
        } else if (!value.supported) {
            PersonUi.text(intro, getString(R.string.extra_time_update_devices), false);
        }
        if (value.dom) renderDom(value); else renderSub(value);
        if (focusTag != null) {
            EditText field = content.findViewWithTag(focusTag);
            if (field != null && field.isEnabled()) {
                field.requestFocus();
                field.setSelection(Math.min(Math.max(0, selection), field.length()));
            }
        }
        scroll.post(() -> { if (!isFinishing() && content.isAttachedToWindow()) scroll.scrollTo(0, scrollY); });
        if (!focused) {
            focused = true;
            content.requestFocus();
        }
    }

    private void renderSub(ExtraTimeRequests.Snapshot value) {
        if (value.apps.isEmpty()) {
            PersonUi.text(ExtraTimeViews.card(content, "extra_time_empty"), getString(R.string.extra_time_sub_empty), false);
            return;
        }
        String selected = getIntent().getStringExtra(EXTRA_PACKAGE);
        boolean selectedExists = false;
        for (ExtraTimeRequests.App app : value.apps) if (app.packageName.equals(selected)) selectedExists = true;
        for (ExtraTimeRequests.App app : value.apps) {
            if (selectedExists && !selected.equals(app.packageName)) continue;
            LinearLayout card = ExtraTimeViews.card(content, "extra_time_app:" + app.packageName);
            ExtraTimeViews.app(card, app.packageName, app.label, true);
            ExtraTimeViews.usage(card, app.usedMillis, app.dailyMinutes, app.bonusMinutes);
            ExtraTimeRequests.Request request = app.pending != null ? app.pending : app.latest;
            if (request != null) receipt(card, request);
            if (app.exhausted) ExtraTimeViews.status(card, getString(app.combinedLimitReached
                    ? R.string.extra_time_total_reached : R.string.extra_time_reached), true);
            else if (app.dailyMinutes > 0 || app.remainingMillis > 0) {
                long remaining = Math.max(0L, app.remainingMillis);
                ExtraTimeViews.status(card, getString(R.string.extra_time_remaining,
                        (remaining + 59_999L) / 60_000L), false);
            }
            if (app.pending == null && app.exhausted) {
                boolean knownTribute = value.tributeCents >= 0;
                if (value.tributeCents > 0) PersonUi.text(card, getString(R.string.extra_time_request_tribute,
                        money(value.tributeCents, value.currency)), false);
                else if (value.tributeCents == 0) PersonUi.text(card, getString(R.string.extra_time_no_tribute), false);
                String key = "submit:" + app.packageName;
                TextView requestButton = PersonUi.action(card, pendingActions.contains(key)
                        ? R.string.extra_time_saving : R.string.extra_time_request,
                        () -> submit(app.packageName, value.tributeCents, value.currency), true);
                requestButton.setTag("extra_time_submit:" + app.packageName);
                requestButton.setEnabled(value.supported && value.canRequest && knownTribute
                        && !pendingActions.contains(key));
            }
        }
    }

    private void renderDom(ExtraTimeRequests.Snapshot value) {
        if (value.requests.isEmpty()) {
            LinearLayout empty = ExtraTimeViews.card(content, "extra_time_empty");
            ExtraTimeViews.status(empty, getString(R.string.extra_time_dom_empty), true);
            PersonUi.text(empty, getString(R.string.extra_time_dom_empty_detail), false);
            return;
        }
        for (ExtraTimeRequests.Request request : value.requests) {
            LinearLayout card = ExtraTimeViews.card(content, "extra_time_request:" + request.id);
            ExtraTimeViews.app(card, request.packageName, request.appLabel, false);
            PersonUi.text(card, getString(R.string.extra_time_at_request), false);
            ExtraTimeViews.requestUsage(card, request.usedMillis);
            receipt(card, request);
            if (request.status != ExtraTimeRequests.Status.PENDING || !value.supported) continue;
            TextView label = PersonUi.text(card, getString(R.string.extra_time_minutes), false);
            EditText minutes = (EditText) getLayoutInflater().inflate(R.layout.view_preference_input, card, false);
            minutes.setId(View.generateViewId());
            minutes.setTag("extra_time_minutes:" + request.id);
            minutes.setSaveEnabled(false);
            minutes.setSingleLine(true);
            minutes.setMinHeight(dp(48));
            minutes.setHint(R.string.extra_time_minutes_hint);
            minutes.setContentDescription(getString(R.string.extra_time_minutes));
            com.subhub.app.util.UiIdentity.inputType(minutes, InputType.TYPE_CLASS_NUMBER);
            minutes.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
            minutes.setText(minuteDrafts.getString(request.id, "15"));
            minutes.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    minuteDrafts.putString(request.id, s.toString());
                }
                @Override public void afterTextChanged(Editable s) { }
            });
            card.addView(minutes, new LinearLayout.LayoutParams(-1, -2));
            label.setLabelFor(minutes.getId());
            String key = "decision:" + request.id;
            boolean saving = pendingActions.contains(key);
            TextView approve = PersonUi.action(card, saving ? R.string.extra_time_saving : R.string.extra_time_approve,
                    () -> approve(request, minutes), true);
            approve.setTag("extra_time_approve:" + request.id);
            approve.setEnabled(!saving);
            TextView decline = PersonUi.action(card, R.string.extra_time_decline,
                    () -> decide(request.id, 0), false);
            decline.setTag("extra_time_decline:" + request.id);
            decline.setEnabled(!saving);
            minutes.setEnabled(!saving);
        }
    }

    private void receipt(LinearLayout card, ExtraTimeRequests.Request request) {
        String status;
        switch (request.status) {
            case PENDING: status = getString(R.string.extra_time_pending); break;
            case APPROVED: status = getString(R.string.extra_time_approved, request.approvedMinutes); break;
            case DECLINED: status = getString(R.string.extra_time_declined); break;
            default: status = getString(R.string.extra_time_expired); break;
        }
        ExtraTimeViews.status(card, status, request.status == ExtraTimeRequests.Status.APPROVED);
        PersonUi.text(card, getString(R.string.extra_time_requested_at,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(request.requestedAt))), false);
        if (request.tributeCents > 0) PersonUi.text(card, getString(R.string.extra_time_tribute_receipt,
                money(request.tributeCents, request.currency)), false);
        if (!snapshot.dom && request.status == ExtraTimeRequests.Status.PENDING)
            PersonUi.text(card, getString(R.string.extra_time_awaiting), false);
    }

    private void submit(String packageName, int expectedTributeCents, String expectedCurrency) {
        String key = "submit:" + packageName;
        if (snapshot == null || !snapshot.supported || !snapshot.canRequest
                || snapshot.tributeCents < 0 || !pendingActions.add(key)) return;
        Context application = getApplicationContext();
        PersonId target = person;
        data.cancel("extra-time-snapshot");
        render(snapshot);
        data.loadSerial(key, () -> {
            ExtraTimeRequests repository = new ExtraTimeRequests(application, target);
            repository.submit(packageName, expectedTributeCents, expectedCurrency);
            return repository.snapshot(System.currentTimeMillis());
        }, result -> completed(key, result), error -> actionFailed(key, error));
    }

    private void approve(ExtraTimeRequests.Request request, EditText input) {
        int minutes;
        try { minutes = Integer.parseInt(input.getText().toString().trim()); }
        catch (NumberFormatException invalid) { input.setError(getString(R.string.extra_time_minutes_error)); return; }
        if (minutes < 1 || minutes > 1440) { input.setError(getString(R.string.extra_time_minutes_error)); return; }
        decide(request.id, minutes);
    }

    private void decide(String requestId, int minutes) {
        String key = "decision:" + requestId;
        if (snapshot == null || !snapshot.dom || !snapshot.supported || !pendingActions.add(key)) return;
        View focus = getCurrentFocus();
        if (focus != null) {
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
            content.requestFocus();
        }
        Context application = getApplicationContext();
        PersonId target = person;
        data.cancel("extra-time-snapshot");
        render(snapshot);
        data.loadSerial(key, () -> {
            ExtraTimeRequests repository = new ExtraTimeRequests(application, target);
            if (minutes == 0) repository.decline(requestId); else repository.approve(requestId, minutes);
            return repository.snapshot(System.currentTimeMillis());
        }, result -> completed(key, result), error -> actionFailed(key, error));
    }

    private void completed(String key, ExtraTimeRequests.Snapshot result) {
        pendingActions.remove(key);
        render(result);
    }

    private void actionFailed(String key, Exception error) {
        pendingActions.remove(key);
        if (snapshot != null) render(snapshot);
        if (error instanceof SecurityException) { finish(); return; }
        notice(getString(R.string.extra_time_action_failed));
        refresh();
    }

    private void failed(Exception error) {
        if (error instanceof SecurityException) { finish(); return; }
        if (snapshot != null) return;
        content.removeAllViews();
        LinearLayout card = ExtraTimeViews.card(content, "extra_time_unavailable");
        PersonUi.text(card, getString(R.string.extra_time_unavailable), false);
        PersonUi.action(card, R.string.extra_time_retry, this::refresh, false);
    }

    private String money(int cents, String currency) {
        NumberFormat formatter = NumberFormat.getCurrencyInstance();
        try { formatter.setCurrency(Currency.getInstance(currency)); }
        catch (IllegalArgumentException invalid) { return currency + " " + String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0); }
        return formatter.format(cents / 100.0);
    }

    private static String signature(ExtraTimeRequests.Snapshot value) {
        StringBuilder out = new StringBuilder().append(value.dom).append('|').append(value.supported)
                .append('|').append(value.canRequest).append('|').append(value.unavailableReason)
                .append('|').append(value.personName).append('|').append(value.tributeCents).append('|').append(value.currency);
        for (ExtraTimeRequests.App app : value.apps) out.append('|').append(app.packageName).append('|').append(app.label)
                .append('|').append(app.dailyMinutes).append('|').append(app.bonusMinutes).append('|').append(app.usedMillis)
                .append('|').append(app.remainingMillis).append('|').append(app.combinedLimitReached)
                .append('|').append(app.exhausted).append('|').append(app.pending == null ? "" : app.pending.id)
                .append('|').append(app.latest == null ? "" : app.latest.id + app.latest.status + app.latest.approvedMinutes);
        for (ExtraTimeRequests.Request request : value.requests) out.append('|').append(request.id).append('|')
                .append(request.status).append('|').append(request.approvedMinutes).append('|').append(request.requestedAt);
        return out.toString();
    }
}
