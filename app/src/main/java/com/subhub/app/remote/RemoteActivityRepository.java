package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.activity.ActivityEntry;
import com.subhub.app.activity.ActivityRepository;
import com.subhub.app.people.PersonId;
import java.util.List;
import java.util.Set;

/** No Activity capture, persistence, synchronization or notification worker is installed. */
public final class RemoteActivityRepository implements ActivityRepository, RemoteActivityRecorder.Sink, AutoCloseable {
    interface Notifications {
        boolean enabled(Context context);
        boolean show(Context context, ActivityEntry entry);
        void cancel(Context context, ActivityEntry entry);
        void cancelPeer(Context context, PersonId person);
    }
    RemoteActivityRepository(Context context, RemoteStore store, Runnable synchronize) { }
    RemoteActivityRepository(Context context, RemoteStore store, Runnable synchronize, Notifications notifications) { }
    void install() { }
    void refreshSource() { }
    void received(RemoteStore.Peer peer) { }
    void revoked(RemoteStore.Peer peer) { }
    void prepare(RemoteStore.Peer peer) { }
    void dispatchNotifications() { }
    @Override public void foreground(String previousPackage, String packageName, boolean selected, long observedAt) { }
    @Override public void tribute(String ledgerId) { }
    @Override public void limit(String packageName, boolean perApp, int minutes, Set<String> scope, long observedAt) { }
    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("Activity is unavailable in this build");
    }
    @Override public Page load(String before, int limit) { throw unavailable(); }
    @Override public Page locate(String entryKey, int limit) { throw unavailable(); }
    @Override public List<ActivityEntry> recent(PersonId person, int limit) { throw unavailable(); }
    @Override public int unreadCount() { return 0; }
    @Override public void markRead(List<String> keys) { throw unavailable(); }
    @Override public void close() { }
}
