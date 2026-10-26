package com.subhub.app.activity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Queries and read acknowledgements always revalidate the current peer relationship. */
public interface ActivityRepository {
    Page load(String before, int limit) throws Exception;
    Page locate(String entryKey, int limit) throws Exception;
    List<ActivityEntry> recent(com.subhub.app.people.PersonId person, int limit) throws Exception;
    int unreadCount() throws Exception;
    void markRead(List<String> keys) throws Exception;

    final class Page {
        public final List<ActivityEntry> entries;
        public final String nextCursor;
        public final String cursor;

        public Page(List<ActivityEntry> entries, String nextCursor) { this(entries, nextCursor, null); }

        public Page(List<ActivityEntry> entries, String nextCursor, String cursor) {
            Objects.requireNonNull(entries);
            if (entries.size() > 100) throw new IllegalArgumentException("Oversized activity page");
            HashSet<String> identities = new HashSet<>();
            for (ActivityEntry entry : entries) {
                if (!identities.add(Objects.requireNonNull(entry).key))
                    throw new IllegalArgumentException("Duplicate activity entry");
            }
            if (nextCursor != null && nextCursor.length() > 2048)
                throw new IllegalArgumentException("Oversized activity cursor");
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            if (cursor != null && cursor.length() > 2048) throw new IllegalArgumentException("Oversized activity cursor");
            this.nextCursor = nextCursor;
            this.cursor = cursor;
        }
    }
}
