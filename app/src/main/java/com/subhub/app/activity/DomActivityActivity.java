package com.subhub.app.activity;

import androidx.lifecycle.ViewModel;
import com.subhub.app.people.PeopleActivity;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Notification and history links enter the retained Subs host on its Activity pane. */
public final class DomActivityActivity extends PeopleActivity {
    public static final String EXTRA_ENTRY_KEY = "subhub.activity.entry";
    public static final String EXTRA_LOCAL_HISTORY = "subhub.activity.local";

    @Override protected boolean initiallyShowActivity() { return true; }

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
