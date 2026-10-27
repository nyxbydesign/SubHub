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

/** A protected, paged history of facts received from currently paired Subs. */
public final class DomActivityActivity extends AppCompatActivity {
    public static final String EXTRA_ENTRY_KEY = "subhub.activity.entry";
    public static final String EXTRA_LOCAL_HISTORY = "subhub.activity.local";
    private static final int PAGE_SIZE = 50;
    private AsyncUiScope work;
    private FeedState state;
    private ListView list;
    private TextView status, unread, retry, older, latest, notifications;
    private final FeedAdapter adapter = new FeedAdapter();
    private AutoCloseable observer;
    private boolean resumed, authenticating, reading;
    private long readSequence;
    private final android.os.Handler authorityClock = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable checkAuthority = new Runnable() {
        public void run() {
            if (!resumed) return;
            if (!authenticating && !mayRead()) {
                list.setVisibility(View.INVISIBLE);
                unread.setVisibility(View.GONE);
                ControllerEditMode.enterSubMode(DomActivityActivity.this);
                return;
            }
            authorityClock.postDelayed(this, 500);
        }
    };
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> updateNotificationAction());

    public static final class FeedState extends ViewModel {
        public boolean local;
        public String cursor;
        public String nextCursor;
        public String focusKey;
        public int position, offset;
        public List<ActivityEntry> entries = Collections.emptyList();
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_dom_activity);
        state = new ViewModelProvider(this).get(FeedState.class);
        if (saved != null) {
            state.cursor = saved.getString("activity_cursor");
            state.position = saved.getInt("activity_position");
            state.offset = saved.getInt("activity_offset");
        }
        state.focusKey = saved == null ? getIntent().getStringExtra(EXTRA_ENTRY_KEY) : saved.getString("activity_focus");
        state.local = saved == null ? getIntent().getBooleanExtra(EXTRA_LOCAL_HISTORY, false)
                || state.focusKey != null && state.focusKey.startsWith("local:") : saved.getBoolean("activity_local");
        if (state.local && state.cursor == null) state.cursor = "local:MAX";
        getIntent().removeExtra(EXTRA_ENTRY_KEY);
        work = AsyncUiScope.forPage(this);
        list = findViewById(R.id.dom_activity_list);
        status = findViewById(R.id.dom_activity_status);
        unread = findViewById(R.id.dom_activity_unread_count);
        retry = findViewById(R.id.dom_activity_retry_action);
        latest = findViewById(R.id.dom_activity_latest_action);
        notifications = findViewById(R.id.dom_activity_notification_action);
        PrimaryHeader.bind(findViewById(android.R.id.content), 0, R.string.dom_activity_title, 0);
        retry.setOnClickListener(view -> refresh(false));
        latest.setOnClickListener(view -> { state.cursor = state.local ? "local:MAX" : null; refresh(true); });
        notifications.setOnClickListener(view -> {
            boolean missing = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED;
            android.content.SharedPreferences preferences = getSharedPreferences("subhub_activity_ui", 0);
            if (missing && (!preferences.getBoolean("notification_permission_requested", false)
                    || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
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
            if (!mayRead() || position >= state.entries.size()) return;
            PersonRoutes.open(this, state.entries.get(position).personId);
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int scrollState) { markVisibleRead(); }
            public void onScroll(AbsListView view, int first, int visible, int total) { list.post(DomActivityActivity.this::markVisibleRead); }
        });
        View nav = findViewById(R.id.bottom_navigation);
        nav.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> reserveNavigation());
        list.setVisibility(View.INVISIBLE);
    }

    @Override protected void onStart() {
        super.onStart();
        observer = ActivitySources.observe(() -> runOnUiThread(() -> {
            if (!resumed) return;
            if (mayRead()) refresh(false);
            else if (!authenticating) checkAuthority.run();
        }));
    }

    @Override protected void onStop() {
        if (observer != null) {
            try { observer.close(); }
            catch (Exception failure) { throw new IllegalStateException("Activity observer did not close", failure); }
            observer = null;
        }
        super.onStop();
    }

    private boolean mayRead() { return com.subhub.app.security.DeviceRolePreferences.supportsDom(this) && ControllerPinManager.isDomModeActive(); }

    @Override protected void onResume() {
        super.onResume();
        if (!com.subhub.app.security.DeviceRolePreferences.supportsDom(this)) { finish(); return; }
        resumed = true;
        authorityClock.removeCallbacks(checkAuthority);
        authorityClock.postDelayed(checkAuthority, 500);
        TextView role = PrimaryHeader.editLockButton(findViewById(android.R.id.content));
        ControllerEditMode.renderButton(this, role);
        role.setOnClickListener(view -> ControllerEditMode.enterSubMode(this));
        SubHubNavigation.bind(this, findViewById(android.R.id.content), SubHubNavigation.Screen.ACTIVITY);
        updateNotificationAction();
        if (mayRead()) {
            authenticating = false;
            refresh(false);
        } else if (!authenticating) {
            authenticating = true;
            list.setVisibility(View.INVISIBLE);
            unread.setVisibility(View.GONE);
            ControllerPinGate.unlock(this, () -> {
                authenticating = false;
                if (resumed && mayRead()) {
                    ControllerEditMode.renderButton(this, role);
                    SubHubNavigation.bind(this, findViewById(android.R.id.content), SubHubNavigation.Screen.ACTIVITY);
                    refresh(false);
                }
            }, true);
        }
    }

    @Override protected void onPause() {
        rememberPosition();
        resumed = false;
        authorityClock.removeCallbacks(checkAuthority);
        work.cancel("activity-page");
        list.setVisibility(View.INVISIBLE);
        unread.setVisibility(View.GONE);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        state.focusKey = intent.getStringExtra(EXTRA_ENTRY_KEY);
        intent.removeExtra(EXTRA_ENTRY_KEY);
        state.local = intent.getBooleanExtra(EXTRA_LOCAL_HISTORY, false)
                || state.focusKey != null && state.focusKey.startsWith("local:");
        state.cursor = state.local ? "local:MAX" : null;
        if (resumed && mayRead()) refresh(true);
    }

    @Override protected void onSaveInstanceState(Bundle output) {
        rememberPosition();
        output.putBoolean("activity_local", state.local);
        output.putString("activity_cursor", state.cursor);
        output.putString("activity_focus", state.focusKey);
        output.putInt("activity_position", state.position);
        output.putInt("activity_offset", state.offset);
        super.onSaveInstanceState(output);
    }

    private void reserveNavigation() {
        View navigation = findViewById(R.id.bottom_navigation);
        int bottom = navigation.getHeight() + PersonUi.dp(this, 16);
        if (list.getPaddingBottom() != bottom)
            list.setPaddingRelative(list.getPaddingStart(), list.getPaddingTop(), list.getPaddingEnd(), bottom);
    }

    private void updateNotificationAction() {
        if (notifications != null) notifications.setVisibility(ActivityNotifications.enabled(this) ? View.GONE : View.VISIBLE);
    }

    private boolean atLatest() { return state.cursor == null || "local:MAX".equals(state.cursor); }

    private void refresh(boolean reset) {
        if (!resumed || !mayRead()) return;
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
            adapter.notifyDataSetChanged();
            older.setVisibility(state.nextCursor == null ? View.GONE : View.VISIBLE);
            unread.setText(result.unread > 0 ? getString(R.string.dom_activity_unread_count, result.unread) : "");
            unread.setVisibility(result.unread > 0 ? View.VISIBLE : View.GONE);
            status.setText(requestedFocus != null && state.entries.isEmpty()
                    ? R.string.dom_activity_entry_unavailable : R.string.dom_activity_empty);
            status.setVisibility(state.entries.isEmpty() ? View.VISIBLE : View.GONE);
            list.setVisibility(View.VISIBLE);
            int position = state.position;
            if (state.focusKey != null) {
                for (int i = 0; i < state.entries.size(); i++) if (state.focusKey.equals(state.entries.get(i).key)) { position = i; break; }
                state.focusKey = null;
            }
            latest.setVisibility(atLatest() ? View.GONE : View.VISIBLE);
            list.setSelectionFromTop(position, state.offset);
            reserveNavigation();
            list.post(this::markVisibleRead);
        }, failure -> {
            if (!resumed || !mayRead()) return;
            state.entries = Collections.emptyList();
            adapter.notifyDataSetChanged();
            list.setVisibility(View.INVISIBLE);
            unread.setVisibility(View.GONE);
            status.setText(R.string.dom_activity_unavailable);
            status.setVisibility(View.VISIBLE);
            retry.setVisibility(View.VISIBLE);
        });
    }

    private void rememberPosition() {
        if (list == null || !list.isShown() || list.getChildCount() == 0) return;
        state.position = list.getFirstVisiblePosition();
        state.offset = list.getChildAt(0).getTop();
    }

    private void markVisibleRead() {
        if (!resumed || reading || !mayRead() || !list.isShown()) return;
        List<ActivityEntry> visible = new ArrayList<>();
        for (int index = list.getFirstVisiblePosition(); index <= list.getLastVisiblePosition() && index < state.entries.size(); index++) {
            View container = list.getChildAt(index - list.getFirstVisiblePosition());
            View row = container == null ? null : container.findViewWithTag("activity_entry:" + state.entries.get(index).key);
            Rect bounds = new Rect();
            ActivityEntry entry = state.entries.get(index);
            if (entry.unread && row != null && row.getGlobalVisibleRect(bounds)) {
                Rect rail = new Rect();
                if (findViewById(R.id.bottom_navigation).getGlobalVisibleRect(rail)) bounds.bottom = Math.min(bounds.bottom, rail.top);
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
            adapter.notifyDataSetChanged();
            for (ActivityEntry entry : visible) ActivityNotifications.cancel(getApplicationContext(), entry);
            ActivitySources.changed();
        }, failure -> { reading = false; });
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
        public int getCount() { return state == null ? 0 : state.entries.size(); }
        public ActivityEntry getItem(int position) { return state.entries.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            ActivityEntry entry = getItem(position);
            LinearLayout outside = PersonUi.column(DomActivityActivity.this);
            if (position == 0 || !sameDay(getItem(position - 1).event.observedAt, entry.event.observedAt)) {
                TextView heading = PersonUi.text(outside, DateUtils.formatDateTime(DomActivityActivity.this,
                        entry.event.observedAt, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                                | DateUtils.FORMAT_ABBREV_MONTH), true);
                androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
                heading.setTag("activity_date:" + entry.key);
            }
            LinearLayout card = PersonUi.card(outside);
            card.setTag("activity_entry:" + entry.key);
            LinearLayout row = new LinearLayout(DomActivityActivity.this);
            row.setGravity(Gravity.TOP);
            ImageView icon = new ImageView(DomActivityActivity.this);
            icon.setImageResource(ActivityText.icon(entry));
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent_text)));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams image = new LinearLayout.LayoutParams(PersonUi.dp(DomActivityActivity.this, 24), PersonUi.dp(DomActivityActivity.this, 24));
            image.setMarginEnd(PersonUi.dp(DomActivityActivity.this, 12));
            row.addView(icon, image);
            LinearLayout copy = PersonUi.column(DomActivityActivity.this);
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
            PersonUi.text(copy, entry.personName, true);
            PersonUi.text(copy, ActivityText.title(DomActivityActivity.this, entry), false);
            String detail = ActivityText.detail(DomActivityActivity.this, entry);
            if (!detail.isEmpty()) PersonUi.text(copy, detail, false);
            TextView time = PersonUi.text(copy, DateUtils.formatDateTime(DomActivityActivity.this,
                    entry.event.observedAt, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH), false);
            time.setTextSize(12);
            if (entry.unread) {
                TextView indicator = PersonUi.text(copy, getString(R.string.dom_activity_unread), false);
                indicator.setTextColor(getColor(R.color.accent_text));
                indicator.setTag("activity_unread:" + entry.key);
            }
            card.setMinimumHeight(PersonUi.dp(DomActivityActivity.this, 72));
            card.setContentDescription(entry.personName + ". " + ActivityText.title(DomActivityActivity.this, entry)
                    + ". " + detail + ". " + time.getText() + (entry.unread ? ". " + getString(R.string.dom_activity_unread) : ""));
            return outside;
        }
    }
}
