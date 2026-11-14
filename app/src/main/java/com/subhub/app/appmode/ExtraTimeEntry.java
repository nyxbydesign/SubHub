package com.subhub.app.appmode;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonUi;
import com.subhub.app.remote.ExtraTimeRequests;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.AsyncUiScope;

/** Person-scoped routes remain available from the existing allowance cards. */
public final class ExtraTimeEntry {
    private ExtraTimeEntry() { }

    public static void inline(LinearLayout parent, ExtraTimeRequests.Snapshot snapshot, ExtraTimeRequests.App app,
            ExtraTimeOverviewController.Operation operation, Runnable submit) {
        Context context = parent.getContext();
        String packageName = app.packageName;
        ExtraTimeRequests.Request saved = operation != null && operation.stage == ExtraTimeOverviewController.Stage.SAVED
                ? operation.request : null;
        if (saved != null && snapshot.requests.stream().noneMatch(request -> request.id.equals(saved.id)))
            receipt(parent, snapshot, saved, true);
        boolean latest = saved == null;
        for (ExtraTimeRequests.Request request : snapshot.requests) {
            if (!packageName.equals(request.packageName) || !snapshot.isOnSubDay(request)) continue;
            receipt(parent, snapshot, request, latest || saved != null && request.id.equals(saved.id));
            latest = false;
        }
        if (operation != null && operation.stage == ExtraTimeOverviewController.Stage.BUSY) {
            TextView busy = PersonUi.action(parent, R.string.extra_time_inline_submitting, () -> { }, true);
            busy.setTag("extra_time_inline_submit:" + packageName);
            busy.setEnabled(false);
            busy.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            return;
        }
        if (app.pending != null || saved != null) return;
        if (operation != null && operation.stage == ExtraTimeOverviewController.Stage.ERROR) {
            int message = operation.failure == ExtraTimeOverviewController.Failure.CHANGED ? R.string.extra_time_inline_changed
                    : operation.failure == ExtraTimeOverviewController.Failure.UNAVAILABLE ? R.string.extra_time_inline_unavailable
                    : R.string.extra_time_inline_unconfirmed;
            status(parent, context.getString(message), "extra_time_inline_error:" + packageName, false);
        }
        if (!app.exhausted) return;
        boolean available = snapshot.supported && snapshot.canRequest && snapshot.tributeCents >= 0;
        if (snapshot.tributeCents > 0) PersonUi.text(parent, context.getString(R.string.extra_time_request_tribute,
                money(snapshot.tributeCents, snapshot.currency)), false);
        else if (snapshot.tributeCents == 0) PersonUi.text(parent, context.getString(R.string.extra_time_no_tribute), false);
        TextView action = PersonUi.action(parent, available ? R.string.extra_time_request
                : R.string.extra_time_request_unavailable, submit, true);
        action.setTag("extra_time_inline_submit:" + packageName);
        action.setEnabled(available);
        action.setAlpha(available ? 1f : 0.5f);
        if (!available) status(parent, unavailableReason(context, snapshot),
                "extra_time_inline_unavailable_reason:" + packageName, false);
    }

    private static void receipt(LinearLayout parent, ExtraTimeRequests.Snapshot snapshot,
            ExtraTimeRequests.Request request, boolean latest) {
        Context context = parent.getContext();
        LinearLayout card = ExtraTimeViews.receipt(parent, request.id);
        PersonUi.text(card, context.getString(R.string.extra_time_requested_at,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        .format(new java.util.Date(request.requestedAt))), false);
        String message;
        switch (request.status) {
            case PENDING: message = context.getString(R.string.extra_time_pending); break;
            case APPROVED: message = context.getString(R.string.extra_time_approved, request.approvedMinutes); break;
            case DECLINED: message = context.getString(R.string.extra_time_declined); break;
            default: message = context.getString(R.string.extra_time_expired); break;
        }
        status(card, message,
                latest ? "extra_time_inline_status:" + request.packageName : null,
                request.status == ExtraTimeRequests.Status.APPROVED);
        if (request.status == ExtraTimeRequests.Status.PENDING) {
            PersonUi.text(card, context.getString(R.string.extra_time_inline_saved), false);
            if (!snapshot.supported) PersonUi.text(card, unavailableReason(context, snapshot), false);
        }
        if (request.tributeCents > 0) PersonUi.text(card, context.getString(R.string.extra_time_tribute_receipt,
                money(request.tributeCents, request.currency)), false);
    }

    private static String unavailableReason(Context context, ExtraTimeRequests.Snapshot snapshot) {
        return snapshot.unavailableReason == null || snapshot.unavailableReason.isEmpty()
                ? context.getString(R.string.extra_time_unavailable) : snapshot.unavailableReason;
    }

    public static void unavailable(LinearLayout parent, String packageName, boolean failed) {
        status(parent, parent.getContext().getString(failed ? R.string.extra_time_inline_unavailable
                : R.string.extra_time_inline_refresh), "extra_time_inline_unavailable_reason:" + packageName, false);
    }

    private static void status(LinearLayout parent, String message, String tag, boolean accent) {
        TextView status = PersonUi.text(parent, message, false);
        status.setTag(tag);
        status.setTextColor(parent.getContext().getColor(accent ? R.color.accent_text : R.color.text_secondary));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private static String money(int cents, String currency) {
        java.text.NumberFormat formatter = java.text.NumberFormat.getCurrencyInstance();
        try { formatter.setCurrency(java.util.Currency.getInstance(currency)); }
        catch (IllegalArgumentException invalid) { return currency + " " + String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0); }
        return formatter.format(cents / 100.0);
    }

    public static void review(LinearLayout parent, PersonId person) {
        Context context = parent.getContext();
        if (!DeviceRolePreferences.isDomWorkspace(context)) return;
        TextView action = PersonUi.action(parent, R.string.extra_time_review,
                () -> ExtraTimeActivity.open(context, person, null), false);
        action.setTag("extra_time_review:" + person.value());
        refresh(parent, person);
    }

    /** Called by the owning page's established refresh, without adding another timer. */
    public static void refresh(View root, PersonId person) {
        TextView action = root.findViewWithTag("extra_time_review:" + person.value());
        if (action == null) return;
        Context context = action.getContext();
        if (!DeviceRolePreferences.isDomWorkspace(context)) return;
        AsyncUiScope data = AsyncUiScope.forContext(context);
        String key = "extra-time-count:" + person.value();
        if (data.isPending(key)) return;
        Context application = context.getApplicationContext();
        com.subhub.app.relationships.RelationshipUi.Provider provider =
                com.subhub.app.relationships.RelationshipUi.provider();
        com.subhub.app.security.KeyholderProvider authority = com.subhub.app.people.People.keyholder();
        data.load(key,
                () -> new ExtraTimeRequests(application, person).snapshot(System.currentTimeMillis()), value -> {
                    if (!action.isAttachedToWindow() || !DeviceRolePreferences.isDomWorkspace(context)
                            || provider != com.subhub.app.relationships.RelationshipUi.provider()
                            || authority != com.subhub.app.people.People.keyholder() || !sameRoute(context, person)
                            || root.findViewWithTag("extra_time_review:" + person.value()) != action) return;
                    int count = 0;
                    for (ExtraTimeRequests.Request request : value.requests)
                        if (request.status == ExtraTimeRequests.Status.PENDING) count++;
                    action.setText(count > 0 ? context.getString(R.string.extra_time_review_count, count)
                            : context.getString(R.string.extra_time_review));
                }, ignored -> {
                    if (action.isAttachedToWindow() && sameRoute(context, person))
                        action.setText(R.string.extra_time_review);
                });
    }

    private static boolean sameRoute(Context context, PersonId person) {
        for (int depth = 0; depth < 32; depth++) {
            if (context instanceof android.app.Activity) {
                String route = ((android.app.Activity) context).getIntent()
                        .getStringExtra(com.subhub.app.people.PersonRoutes.EXTRA_PERSON_ID);
                return route == null || route.equals(person.value());
            }
            if (!(context instanceof android.content.ContextWrapper)) break;
            Context parent = ((android.content.ContextWrapper) context).getBaseContext();
            if (parent == context) break;
            context = parent;
        }
        return false;
    }
}
