package com.subhub.app.activity;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.LinearLayout;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.subhub.app.R;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonUi;
import com.subhub.app.people.ProfileViews;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.AsyncUiScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A bounded view of source-recorded facts, refreshed only while its page is visible. */
public final class ActivityPreview {
    private ActivityPreview() { }

    public static void add(LinearLayout parent, PersonId person, int limit, AsyncUiScope data, String jobKey) {
        if (limit < 1 || limit > 10 || jobKey == null || jobKey.isEmpty()) {
            throw new IllegalArgumentException("Invalid activity preview bounds");
        }
        Preview preview = new Preview(parent.getContext(), person, limit, data, jobKey);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = PersonUi.dp(parent.getContext(), 14);
        parent.addView(preview, size);
    }

    private static LifecycleOwner lifecycle(Context context) {
        for (int i = 0; i < 32; i++) {
            if (context instanceof LifecycleOwner) return (LifecycleOwner) context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        throw new IllegalArgumentException("Activity preview requires a page lifecycle");
    }

    private static final class Preview extends LinearLayout implements DefaultLifecycleObserver {
        private final PersonId person;
        private final int limit;
        private final AsyncUiScope data;
        private final String jobKey;
        private final LifecycleOwner owner;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final LinearLayout rows;
        private AutoCloseable observer;
        private boolean pendingRefresh, listening;
        private String newest;
        private final java.util.Map<String, EntryRow> renderedRows = new java.util.LinkedHashMap<>();
        private int message;
        private final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean();

        Preview(Context context, PersonId person, int limit, AsyncUiScope data, String jobKey) {
            super(context);
            this.person = person;
            this.limit = limit;
            this.data = java.util.Objects.requireNonNull(data);
            this.jobKey = jobKey;
            owner = lifecycle(context);
            setOrientation(VERTICAL);
            setVisibility(DeviceRolePreferences.supportsDom(context) ? VISIBLE : GONE);
            setTag("activity_preview:" + (person == null ? "all" : person.value()));
            setBackgroundResource(R.drawable.bg_card);
            int inset = PersonUi.dp(context, 16);
            setPadding(inset, inset, inset, inset);
            PersonUi.text(this, context.getString(ActivitySources.repository().localOnly()
                    ? R.string.dom_activity_recent_local : R.string.dom_activity_recent), true);
            rows = PersonUi.column(context);
            addView(rows, new LayoutParams(-1, -2));
            PersonUi.text(rows, context.getString(R.string.role_loading), false);
            PersonUi.action(this, R.string.dom_activity_view_all, () -> {
                if (!allowed()) return;
                Intent intent = new Intent(context, DomActivityActivity.class);
                if (person != null && person.isLocal()) intent.putExtra(DomActivityActivity.EXTRA_LOCAL_HISTORY, true);
                if (newest != null) intent.putExtra(DomActivityActivity.EXTRA_ENTRY_KEY, newest);
                context.startActivity(intent);
            }, false).setTag("activity_preview_view_all:" + (person == null ? "all" : person.value()));
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            owner.getLifecycle().addObserver(this);
            start();
        }
        @Override protected void onDetachedFromWindow() {
            stop();
            owner.getLifecycle().removeObserver(this);
            super.onDetachedFromWindow();
        }
        @Override public void onStart(LifecycleOwner page) { start(); }
        @Override public void onStop(LifecycleOwner page) { stop(); }
        @Override public void onDestroy(LifecycleOwner page) { stop(); owner.getLifecycle().removeObserver(this); }

        private boolean visible() {
            return isAttachedToWindow() && DeviceRolePreferences.supportsDom(getContext())
                    && owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
        }

        private boolean allowed() { return DeviceRolePreferences.supportsDom(getContext()) && ControllerPinManager.isDomModeActive(); }

        private boolean retainAccess() {
            if (allowed()) return true;
            setVisibility(GONE);
            clearRows();
            newest = null;
            data.cancel(jobKey);
            return false;
        }

        private void start() {
            if (!visible() || listening) return;
            listening = true;
            observer = ActivitySources.observe(() -> {
                if (scheduled.compareAndSet(false, true)) main.post(() -> {
                    scheduled.set(false);
                    refresh();
                });
            });
            refresh();
        }

        private void stop() {
            listening = false;
            pendingRefresh = false;
            scheduled.set(false);
            main.removeCallbacksAndMessages(null);
            data.cancel(jobKey);
            if (observer != null) {
                try { observer.close(); }
                catch (Exception failure) { android.util.Log.w("ActivityPreview", "Could not detach activity observer"); }
                observer = null;
            }
        }

        private void refresh() {
            if (!retainAccess() || !listening || !visible()) return;
            ActivityRepository source = ActivitySources.repository();
            if (!source.supports(person)) {
                data.cancel(jobKey);
                pendingRefresh = false;
                newest = null;
                clearRows();
                setVisibility(GONE);
                return;
            }
            setVisibility(VISIBLE);
            if (data.isPending(jobKey)) { pendingRefresh = true; return; }
            PersonId requested = person;
            int count = limit;
            Context app = getContext().getApplicationContext();
            data.load(jobKey, () -> {
                if (!DeviceRolePreferences.supportsDom(app) || !ControllerPinManager.isDomModeActive()) {
                    throw new SecurityException("Activity unavailable");
                }
                return source.recent(requested, count);
            }, entries -> {
                if (!retainAccess() || !listening || !visible()) return;
                if (source != ActivitySources.repository()) { pendingRefresh = true; }
                else render(entries);
                continueRefresh();
            }, failure -> {
                if (!retainAccess() || !listening || !visible()) return;
                if (source != ActivitySources.repository() || !source.supports(person)) {
                    pendingRefresh = true;
                    continueRefresh();
                    return;
                }
                showMessage(R.string.dom_activity_unavailable, true);
                newest = null;
                continueRefresh();
            });
        }

        private void continueRefresh() {
            if (!pendingRefresh) return;
            pendingRefresh = false;
            main.post(this::refresh);
        }

        private void clearRows() {
            rows.removeAllViews();
            renderedRows.clear();
            message = 0;
        }

        private void showMessage(int label, boolean retry) {
            int signature = retry ? -label : label;
            if (message == signature) return;
            clearRows();
            message = signature;
            PersonUi.text(rows, getContext().getString(label), false);
            if (retry) PersonUi.action(rows, R.string.role_retry, this::refresh, false);
        }

        private void render(List<ActivityEntry> entries) {
            newest = null;
            if (entries == null) {
                showMessage(R.string.dom_activity_unavailable, false);
                return;
            }
            List<ActivityEntry> sorted = new ArrayList<>(entries);
            Set<String> identities = new HashSet<>();
            boolean valid = sorted.size() <= limit;
            for (ActivityEntry entry : sorted) {
                valid &= entry != null && identities.add(entry.key)
                        && (person == null || person.equals(entry.personId));
            }
            if (!valid) {
                showMessage(R.string.dom_activity_unavailable, false);
                return;
            }
            sorted.sort(Comparator.comparingLong((ActivityEntry entry) -> entry.event.observedAt).reversed()
                    .thenComparing(entry -> entry.key));
            if (sorted.isEmpty()) { showMessage(R.string.dom_activity_empty, false); return; }
            newest = sorted.get(0).key;
            if (renderedRows.isEmpty()) clearRows();
            java.util.Iterator<java.util.Map.Entry<String, EntryRow>> previous = renderedRows.entrySet().iterator();
            while (previous.hasNext()) {
                java.util.Map.Entry<String, EntryRow> old = previous.next();
                if (!identities.contains(old.getKey())) { rows.removeView(old.getValue().view); previous.remove(); }
            }
            int index = 0;
            for (ActivityEntry entry : sorted) {
                List<String> details = new ArrayList<>();
                if (person == null) details.add(entry.personName + " · "
                        + ProfileViews.targetDiscriminator(getContext(), entry.personId, entry.device));
                String detail = ActivityText.detail(getContext(), entry);
                if (!detail.isEmpty()) details.add(detail);
                details.add(DateUtils.getRelativeTimeSpanString(entry.event.observedAt,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString());
                if (entry.unread) details.add(getContext().getString(R.string.dom_activity_unread));
                EntryRow row = renderedRows.get(entry.key);
                if (row == null) { row = new EntryRow(entry); renderedRows.put(entry.key, row); }
                row.bind(entry, String.join("\n", details));
                if (rows.indexOfChild(row.view) != index) {
                    rows.removeView(row.view);
                    rows.addView(row.view, index);
                }
                index++;
            }
        }

        private final class EntryRow {
            final LinearLayout view;
            final android.widget.ImageView icon;
            final android.widget.TextView title, detail;
            ActivityEntry entry;
            EntryRow(ActivityEntry entry) {
                this.entry = entry;
                view = PersonUi.row(rows, ActivityText.icon(entry), ActivityText.title(getContext(), entry), " ", () -> {
                    if (allowed()) PersonRoutes.open(getContext(), this.entry.personId);
                });
                view.setTag("activity_preview_entry:" + entry.key);
                icon = (android.widget.ImageView) view.getChildAt(0);
                LinearLayout copy = (LinearLayout) view.getChildAt(1);
                title = (android.widget.TextView) copy.getChildAt(0);
                detail = (android.widget.TextView) copy.getChildAt(1);
            }
            void bind(ActivityEntry value, String text) {
                entry = value;
                String label = ActivityText.title(getContext(), value);
                if (!android.text.TextUtils.equals(title.getText(), label)) title.setText(label);
                if (!android.text.TextUtils.equals(detail.getText(), text)) detail.setText(text);
                ActivityText.bindIcon(icon, value);
            }
        }
    }
}
