package com.subhub.app.activity;

import android.Manifest;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.subhub.app.R;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** A protected, paged history of facts received from currently paired Subs. */
public final class DomActivityPane extends android.content.ContextWrapper {
    private final com.subhub.app.people.PeopleActivity host;
    private final View root;
    public static final String EXTRA_ENTRY_KEY = "subhub.activity.entry";
    public static final String EXTRA_LOCAL_HISTORY = "subhub.activity.local";
    private static final int PAGE_SIZE = 50;
    private AsyncUiScope work;
    private DomActivityActivity.FeedState state;
    private final ActivityResultLauncher<String> permission;
    private List<ActivityGroup> groups = Collections.emptyList();
    private ListView list;
    private TextView status, unread, retry, older, latest, notifications;
    private final FeedAdapter adapter = new FeedAdapter();
    private AutoCloseable observer;
    private boolean resumed, reading;
    private long readSequence, renderGeneration;
    public DomActivityPane(com.subhub.app.people.PeopleActivity host, Bundle saved) {
        super(host);
        this.host = host;
        root = host.getLayoutInflater().inflate(R.layout.activity_dom_activity, null, false);
        ViewGroup column = (ViewGroup) ((ViewGroup) root).getChildAt(0);
        column.removeViewAt(0);
        column.removeViewAt(0);
        column.setPadding(0, 0, 0, 0);
        ((ViewGroup) root).removeView(root.findViewById(R.id.bottom_navigation));
        permission = host.registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> updateNotificationAction());
        state = new ViewModelProvider(host).get(DomActivityActivity.FeedState.class);
        if (saved != null) {
            state.cursor = saved.getString("activity_cursor");
            state.position = saved.getInt("activity_position");
            state.offset = saved.getInt("activity_offset");
            ArrayList<String> expanded = saved.getStringArrayList("activity_expanded");
            if (expanded != null) state.expanded.addAll(expanded);
        }
        state.focusKey = saved == null ? host.getIntent().getStringExtra(EXTRA_ENTRY_KEY) : saved.getString("activity_focus");
        state.local = saved == null ? host.getIntent().getBooleanExtra(EXTRA_LOCAL_HISTORY, false)
                || state.focusKey != null && state.focusKey.startsWith("local:") : saved.getBoolean("activity_local");
        if (ActivitySources.repository().localOnly()) state.local = true;
        if (state.local && state.cursor == null) state.cursor = "local:MAX";
        host.getIntent().removeExtra(EXTRA_ENTRY_KEY);
        work = AsyncUiScope.forPage(host);
        list = root.findViewById(R.id.dom_activity_list);
        status = root.findViewById(R.id.dom_activity_status);
        unread = root.findViewById(R.id.dom_activity_unread_count);
        retry = root.findViewById(R.id.dom_activity_retry_action);
        latest = root.findViewById(R.id.dom_activity_latest_action);
        notifications = root.findViewById(R.id.dom_activity_notification_action);
        retry.setOnClickListener(view -> refresh(false));
        latest.setOnClickListener(view -> { state.cursor = state.local ? "local:MAX" : null; refresh(true); });
        notifications.setOnClickListener(view -> {
            boolean missing = Build.VERSION.SDK_INT >= 33 && host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED;
            android.content.SharedPreferences preferences = getSharedPreferences("subhub_activity_ui", 0);
            if (missing && (!preferences.getBoolean("notification_permission_requested", false)
                    || host.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                preferences.edit().putBoolean("notification_permission_requested", true).apply();
                permission.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
        LinearLayout footer = PersonUi.column(this);
        older = PersonUi.action(footer, R.string.dom_activity_older, () -> {
            if (state.nextCursor == null) return;
            state.cursor = state.nextCursor;
            refresh(true);
        }, false);
        older.setId(R.id.dom_activity_older_action);
        list.addFooterView(footer, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (!mayRead() || position >= groups.size()) return;
            PersonRoutes.open(host, groups.get(position).latest().personId);
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int scrollState) { markVisibleRead(); }
            public void onScroll(AbsListView view, int first, int visible, int total) { list.post(DomActivityPane.this::markVisibleRead); }
        });
        View nav = host.findViewById(R.id.bottom_navigation);
        nav.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> reserveNavigation());
        list.setVisibility(View.INVISIBLE);
    }

    public View view() { return root; }

    public int title() { return state.local ? R.string.dom_activity_local_title : R.string.dom_activity_title; }

    private boolean mayRead() {
        return com.subhub.app.security.DeviceRolePreferences.supportsDom(this)
                && ControllerPinManager.isDomModeActive();
    }

    public void resume() {
        if (resumed || !mayRead()) return;
        resumed = true;
        observer = ActivitySources.observe(() -> host.runOnUiThread(() -> {
            if (resumed && mayRead()) refresh(false);
        }));
        updateNotificationAction();
        refresh(false);
    }

    public void pause() {
        ++renderGeneration;
        rememberPosition();
        resumed = false;
        work.cancel("activity-page");
        list.setVisibility(View.INVISIBLE);
        unread.setVisibility(View.GONE);
        if (observer != null) {
            try { observer.close(); }
            catch (Exception failure) { throw new IllegalStateException("Activity observer did not close", failure); }
            observer = null;
        }
    }

    public void acceptIntent(Intent intent) {
        state.focusKey = intent.getStringExtra(EXTRA_ENTRY_KEY);
        intent.removeExtra(EXTRA_ENTRY_KEY);
        state.local = ActivitySources.repository().localOnly()
                || intent.getBooleanExtra(EXTRA_LOCAL_HISTORY, false)
                || state.focusKey != null && state.focusKey.startsWith("local:");
        state.cursor = state.local ? "local:MAX" : null;
        if (resumed && mayRead()) refresh(true);
    }

    public void save(Bundle output) {
        rememberPosition();
        output.putBoolean("activity_local", state.local);
        output.putString("activity_cursor", state.cursor);
        output.putString("activity_focus", state.focusKey);
        output.putInt("activity_position", state.position);
        output.putInt("activity_offset", state.offset);
        output.putStringArrayList("activity_expanded", new ArrayList<>(state.expanded));
    }

    private void reserveNavigation() {
        View navigation = host.findViewById(R.id.bottom_navigation);
        int bottom = navigation.getHeight() + PersonUi.dp(this, 16);
        if (list.getPaddingBottom() != bottom)
            list.setPaddingRelative(list.getPaddingStart(), list.getPaddingTop(), list.getPaddingEnd(), bottom);
    }

    private void updateNotificationAction() {
        if (notifications != null) notifications.setVisibility(ActivitySources.repository().localOnly()
                || !ActivitySources.repository().supports(null) || ActivityNotifications.enabled(this) ? View.GONE : View.VISIBLE);
    }

    private boolean atLatest() { return state.cursor == null || "local:MAX".equals(state.cursor); }

    private void refresh(boolean reset) {
        if (!resumed || !mayRead()) return;
        long generation = ++renderGeneration;
        if (!ActivitySources.repository().supports(null)) {
            state.entries = Collections.emptyList();
            rebuildGroups();
            list.setVisibility(View.INVISIBLE);
            unread.setVisibility(View.GONE);
            retry.setVisibility(View.GONE);
            older.setVisibility(View.GONE);
            latest.setVisibility(View.GONE);
            notifications.setVisibility(View.GONE);
            status.setText(R.string.dom_activity_not_shared);
            status.setVisibility(View.VISIBLE);
            return;
        }
        if (!reset) rememberPosition();
        else { state.position = 0; state.offset = 0; }
        ActivityRepository repository = ActivitySources.repository();
        boolean local = state.local;
        String cursor = state.cursor;
        String requestedFocus = state.focusKey;
        list.setVisibility(View.INVISIBLE);
        status.setText(R.string.role_loading);
        status.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        latest.setVisibility(atLatest() ? View.GONE : View.VISIBLE);
        work.load("activity-page", () -> new PageResult(requestedFocus == null ? repository.load(cursor, PAGE_SIZE) : repository.locate(requestedFocus, PAGE_SIZE), local ? 0 : repository.unreadCount()), result -> {
            if (!resumed || !mayRead()) return;
            if (repository != ActivitySources.repository()) { refresh(false); return; }
            state.entries = result.page.entries;
            if (requestedFocus != null) state.cursor = result.page.cursor;
            state.nextCursor = result.page.nextCursor;
            rebuildGroups();
            older.setVisibility(state.nextCursor == null ? View.GONE : View.VISIBLE);
            unread.setText(result.unread > 0 ? getString(R.string.dom_activity_unread_count, result.unread) : "");
            unread.setVisibility(result.unread > 0 ? View.VISIBLE : View.GONE);
            status.setText(requestedFocus != null && state.entries.isEmpty()
                    ? R.string.dom_activity_entry_unavailable : R.string.dom_activity_empty);
            status.setVisibility(state.entries.isEmpty() ? View.VISIBLE : View.GONE);
            list.setVisibility(View.VISIBLE);
            int position = state.position;
            String focused = state.focusKey;
            if (focused != null) {
                for (int i = 0; i < groups.size(); i++) if (groups.get(i).contains(focused)) {
                    position = i;
                    state.expanded.add(groups.get(i).key);
                    adapter.notifyDataSetChanged();
                    break;
                }
                state.focusKey = null;
            }
            latest.setVisibility(atLatest() ? View.GONE : View.VISIBLE);
            list.setSelectionFromTop(position, state.offset);
            reserveNavigation();
            if (focused == null) list.post(this::markVisibleRead);
            else revealFocusedEntry(focused, position, generation);
        }, failure -> {
            if (!resumed || !mayRead()) return;
            state.entries = Collections.emptyList();
            rebuildGroups();
            list.setVisibility(View.INVISIBLE);
            unread.setVisibility(View.GONE);
            status.setText(R.string.dom_activity_unavailable);
            status.setVisibility(View.VISIBLE);
            retry.setVisibility(View.VISIBLE);
        });
    }

    private void revealFocusedEntry(String key, int position, long generation) {
        list.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                list.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!resumed || !mayRead() || generation != renderGeneration) return true;
                View target = list.findViewWithTag("activity_entry:" + key);
                int child = position - list.getFirstVisiblePosition();
                if (target == null || child < 0 || child >= list.getChildCount()) return true;
                Rect withinList = new Rect(0, 0, target.getWidth(), target.getHeight());
                list.offsetDescendantRectToMyCoords(target, withinList);
                // A grouped row can exceed the viewport. Position its nested fact directly;
                // a single smoothScrollBy step may stop after only one viewport.
                list.setSelectionFromTop(position, list.getChildAt(child).getTop() - withinList.top);
                list.post(DomActivityPane.this::markVisibleRead);
                return false;
            }
        });
        list.invalidate();
    }

    private void rememberPosition() {
        if (list == null || !list.isShown() || list.getChildCount() == 0) return;
        state.position = list.getFirstVisiblePosition();
        state.offset = list.getChildAt(0).getTop();
    }

    private void markVisibleRead() {
        if (!resumed || reading || !mayRead() || !list.isShown()) return;
        List<ActivityEntry> visible = new ArrayList<>();
        for (ActivityEntry entry : state.entries) {
            View row = list.findViewWithTag("activity_entry:" + entry.key);
            Rect bounds = new Rect();
            if (entry.unread && row != null && row.getGlobalVisibleRect(bounds)) {
                Rect rail = new Rect();
                if (host.findViewById(R.id.bottom_navigation).getGlobalVisibleRect(rail)) bounds.bottom = Math.min(bounds.bottom, rail.top);
                if (bounds.height() >= Math.min(row.getHeight(), PersonUi.dp(this, 48)) / 2) visible.add(entry);
            }
        }
        if (visible.isEmpty()) return;
        reading = true;
        ActivityRepository repository = ActivitySources.repository();
        List<String> keys = new ArrayList<>();
        for (ActivityEntry entry : visible) keys.add(entry.key);
        work.loadSerial("activity-read-" + (++readSequence), () -> { repository.markRead(keys); return Boolean.TRUE; }, complete -> {
            reading = false;
            if (repository != ActivitySources.repository()) return;
            List<ActivityEntry> updated = new ArrayList<>();
            for (ActivityEntry entry : state.entries) updated.add(keys.contains(entry.key) ? entry.read() : entry);
            state.entries = Collections.unmodifiableList(updated);
            if (resumed && mayRead()) rebuildGroups();
            for (ActivityEntry entry : visible) ActivityNotifications.cancel(getApplicationContext(), entry);
            ActivitySources.changed();
        }, failure -> { reading = false; });
    }

    private void rebuildGroups() {
        groups = ActivityGroup.from(state.entries);
        Set<String> keys = new HashSet<>();
        for (ActivityGroup group : groups) keys.add(group.key);
        state.expanded.retainAll(keys);
        adapter.notifyDataSetChanged();
    }

    private static final class PageResult {
        final ActivityRepository.Page page;
        final int unread;
        PageResult(ActivityRepository.Page page, int unread) { this.page = page; this.unread = Math.max(0, unread); }
    }

    static boolean sameDay(long first, long second) {
        java.util.Calendar a = java.util.Calendar.getInstance();
        java.util.Calendar b = java.util.Calendar.getInstance();
        a.setTimeInMillis(first);
        b.setTimeInMillis(second);
        return a.get(java.util.Calendar.ERA) == b.get(java.util.Calendar.ERA)
                && a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private final class FeedAdapter extends BaseAdapter {
        public int getCount() { return groups.size(); }
        public ActivityGroup getItem(int position) { return groups.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            ActivityGroup group = getItem(position);
            ActivityEntry entry = group.latest();
            LinearLayout outside = PersonUi.column(host);
            if (position == 0 || !sameDay(getItem(position - 1).latest().event.observedAt, entry.event.observedAt)) {
                TextView heading = PersonUi.text(outside, DateUtils.formatDateTime(host,
                        entry.event.observedAt, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                                | DateUtils.FORMAT_ABBREV_MONTH), true);
                androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
                heading.setTag("activity_date:" + entry.key);
            }
            LinearLayout card = entryCard(outside, entry);
            if (group.entries.size() > 1) {
                card.setOnClickListener(view -> { if (mayRead()) PersonRoutes.open(host, entry.personId); });
                boolean expanded = state.expanded.contains(group.key);
                int hiddenUnread = group.unread() - (entry.unread ? 1 : 0);
                if (!expanded && hiddenUnread > 0)
                    PersonUi.text(card, getString(R.string.dom_activity_unread_count, hiddenUnread), false);
                TextView toggle = PersonUi.action(card, R.string.dom_activity_group_expand, () -> {
                    if (!mayRead()) return;
                    rememberPosition();
                    if (!state.expanded.remove(group.key)) state.expanded.add(group.key);
                    notifyDataSetChanged();
                    list.setSelectionFromTop(state.position, state.offset);
                    list.post(DomActivityPane.this::markVisibleRead);
                }, false);
                toggle.setText(expanded ? getString(R.string.dom_activity_group_collapse)
                        : getString(R.string.dom_activity_group_expand, group.entries.size()));
                toggle.setTag("activity_group:" + entry.key);
                if (expanded) for (int index = 1; index < group.entries.size(); index++) {
                    ActivityEntry child = group.entries.get(index);
                    LinearLayout detail = entryCard(outside, child);
                    detail.setOnClickListener(view -> { if (mayRead()) PersonRoutes.open(host, child.personId); });
                }
            }
            return outside;
        }
        private LinearLayout entryCard(LinearLayout outside, ActivityEntry entry) {
            LinearLayout card = PersonUi.card(outside);
            card.setTag("activity_entry:" + entry.key);
            LinearLayout row = new LinearLayout(host);
            row.setGravity(Gravity.TOP);
            ImageView icon = new ImageView(host);
            ActivityText.bindIcon(icon, entry);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams image = new LinearLayout.LayoutParams(PersonUi.dp(host, 24), PersonUi.dp(host, 24));
            image.setMarginEnd(PersonUi.dp(host, 12));
            row.addView(icon, image);
            LinearLayout copy = PersonUi.column(host);
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
            PersonUi.text(copy, entry.personName, true);
            PersonUi.text(copy, ActivityText.title(host, entry), false);
            String detail = ActivityText.detail(host, entry);
            if (!detail.isEmpty()) PersonUi.text(copy, detail, false);
            TextView time = PersonUi.text(copy, DateUtils.formatDateTime(host,
                    entry.event.observedAt, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH), false);
            time.setTextSize(12);
            if (entry.unread) {
                TextView indicator = PersonUi.text(copy, getString(R.string.dom_activity_unread), false);
                indicator.setTextColor(getColor(R.color.accent_text));
                indicator.setTag("activity_unread:" + entry.key);
            }
            card.setMinimumHeight(PersonUi.dp(host, 72));
            card.setContentDescription(entry.personName + ". " + ActivityText.title(host, entry)
                    + ". " + detail + ". " + time.getText() + (entry.unread ? ". " + getString(R.string.dom_activity_unread) : ""));
            return card;
        }
    }
}
