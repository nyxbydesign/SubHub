package com.subhub.app.activity;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Recorded Activity has its own page; Back returns to the caller's retained position. */
public final class DomActivityActivity extends AppCompatActivity {
    public static final String EXTRA_ENTRY_KEY = "subhub.activity.entry";
    public static final String EXTRA_LOCAL_HISTORY = "subhub.activity.local";
    private DomActivityPane feed;
    private boolean resumed, authenticating;
    private final android.os.Handler authority = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable checkAuthority = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (!mayRead() && !authenticating) {
                feed.pause();
                com.subhub.app.security.ControllerEditMode.enterSubMode(DomActivityActivity.this);
                return;
            }
            authority.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_people);
        findViewById(R.id.people_roster_scroll).setVisibility(View.GONE);
        feed = new DomActivityPane(this, saved);
        ((android.widget.FrameLayout) findViewById(R.id.people_panes)).addView(feed.view(),
                new android.widget.FrameLayout.LayoutParams(-1, -1));
        PrimaryHeader.backButton(findViewById(android.R.id.content)).setOnClickListener(view -> finish());
    }

    private boolean mayRead() {
        return ControllerPinManager.isDomModeActive() && DeviceRolePreferences.supportsDom(this);
    }

    private void bindPage() {
        View root = findViewById(android.R.id.content);
        SubHubNavigation.bind(this, root, SubHubNavigation.Screen.ACTIVITY);
        root.findViewById(R.id.nav_people).setOnClickListener(view -> {
            if (mayRead()) startActivity(new android.content.Intent(this, com.subhub.app.people.PeopleActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        });
        root.findViewById(R.id.primary_header_settings).setVisibility(View.GONE);
        PrimaryHeader.bindSecondary(root, feed.title(), true);
        com.subhub.app.security.ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(root));
        PrimaryHeader.editLockButton(root).setOnClickListener(view ->
                com.subhub.app.security.ControllerEditMode.enterSubMode(this));
    }

    @Override protected void onResume() {
        super.onResume();
        if (!DeviceRolePreferences.supportsDom(this)) { finish(); return; }
        resumed = true;
        bindPage();
        authority.removeCallbacks(checkAuthority); authority.postDelayed(checkAuthority, 500);
        if (mayRead()) feed.resume();
        else if (!authenticating) {
            authenticating = true; feed.pause();
            com.subhub.app.security.ControllerPinGate.unlock(this, () -> {
                authenticating = false;
                if (resumed && mayRead()) { bindPage(); feed.resume(); }
            }, true);
        }
    }

    @Override protected void onPause() {
        resumed = false; authority.removeCallbacks(checkAuthority);
        feed.pause(); super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        feed.save(state); super.onSaveInstanceState(state);
    }
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.hasExtra(EXTRA_ENTRY_KEY) || intent.hasExtra(EXTRA_LOCAL_HISTORY)) {
            feed.pause(); feed.acceptIntent(intent);
        }
        bindPage(); if (resumed && mayRead()) feed.resume();
    }

    public static final class FeedState extends ViewModel {
        public boolean local;
        public String cursor;
        public String nextCursor;
        public String focusKey;
        public int position, offset;
        public List<ActivityEntry> entries = Collections.emptyList();
        public final Set<String> expanded = new HashSet<>();
    }

    static boolean sameDay(long first, long second) { return DomActivityPane.sameDay(first, second); }
}
