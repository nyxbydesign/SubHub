package com.subhub.app.remote;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import com.subhub.app.R;
import com.subhub.app.activity.*;
import com.subhub.app.people.PersonId;
import com.subhub.app.penance.PenanceEvent;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Local source history and received facts have separate custody, read state and delivery scope. */
public final class RemoteActivityRepository implements ActivityRepository, RemoteActivityRecorder.Sink, AutoCloseable {
    interface Notifications {
        boolean enabled(Context context);
        boolean show(Context context,ActivityEntry entry);
        void cancel(Context context,ActivityEntry entry);
        void cancelPeer(Context context,PersonId person);
    }
    private static final Notifications SYSTEM_NOTIFICATIONS=new Notifications(){
        public boolean enabled(Context context){return ActivityNotifications.enabled(context);}
        public boolean show(Context context,ActivityEntry entry){return ActivityNotifications.show(context,entry);}
        public void cancel(Context context,ActivityEntry entry){ActivityNotifications.cancel(context,entry);}
        public void cancelPeer(Context context,PersonId person){ActivityNotifications.cancelPeer(context,person);}
    };
    private static final String ELIGIBLE = "p.local_role='DOM' AND p.state='ACTIVE'";
    private static final String JOIN = "activity_receipts a JOIN peers p ON p.peer=a.peer AND p.generation=a.generation";
    private final Context context;
    private final RemoteStore store;
    private final Runnable synchronize;
    private final Notifications notifications;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), r -> new Thread(r, "subhub-activity"), new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean notificationQueued = new AtomicBoolean(), gap = new AtomicBoolean();
    private volatile RemoteStore.Peer source;
    private AutoCloseable observer;

    RemoteActivityRepository(Context context, RemoteStore store, Runnable synchronize) {
        this(context,store,synchronize,SYSTEM_NOTIFICATIONS);
    }
    RemoteActivityRepository(Context context, RemoteStore store, Runnable synchronize,Notifications notifications) {
        this.context = context.getApplicationContext();this.store = store;this.synchronize = synchronize;this.notifications=notifications;
        worker.allowCoreThreadTimeOut(true);
        refreshSource();
        enqueue(() -> {
            for (PenanceEvent event : new PenanceManager(this.context).durableActivityEvents())
                if (event.getInfraction() != PenanceInfraction.PAID_PAUSE)
                    append(tributeFact(event), "tribute:" + event.getId(), "UTC", "", null);
        });
    }

    void install() {
        RemoteActivityRecorder.install(this);
        observer = ActivitySources.observe(this::dispatchNotifications);
        dispatchNotifications();
    }

    void refreshSource() {
        synchronized (store) {
            source = null;
            for (RemoteStore.Peer peer : store.peers(false)) if (peer.localRole == RemoteProtocol.Role.SUB && peer.state == RemoteStore.State.ACTIVE) {
                if (!DeviceRolePreferences.supportsSub(context)) break;
                if (store.metadata(peer.identity, peer.generation, "activity_started_at", "").isEmpty())
                    store.putMetadata(peer.identity, peer.generation, "activity_started_at", Long.toString(System.currentTimeMillis()));
                source = peer;break;
            }
        }
    }

    void received(RemoteStore.Peer peer) { changed();dispatchNotifications(); }
    void revoked(RemoteStore.Peer peer) {
        refreshSource();notifications.cancelPeer(context, RemotePersonRepository.id(peer));changed();
    }

    @Override public void foreground(String previousPackage, String packageName, boolean selected, long observedAt) {
        if (!DeviceRolePreferences.supportsSub(context) || packageName == null || packageName.isEmpty()) return;
        RemoteStore.Peer captured = source;String zone = ZoneId.systemDefault().getId();
        String initial = previousPackage == null ? "" : previousPackage;
        enqueue(() -> {
            synchronized (store) {
                SQLiteDatabase db = store.getWritableDatabase();
                String previous = RemoteActivityStore.readState(db, "foreground", initial.isEmpty() ? packageName : initial);
                if (selected && !previous.equals(packageName)) {
                    String id = "app_" + UUID.randomUUID();
                    append(new ActivityEvent(id, ActivityEvent.Kind.APP_OPENED, observedAt, packageName, 0, "", "", 0),
                            id, zone, "", captured, packageName);
                } else RemoteActivityStore.writeState(db, "foreground", packageName);
            }
        });
    }

    @Override public void tribute(String ledgerId) {
        if (!DeviceRolePreferences.supportsSub(context)) return;
        RemoteStore.Peer captured = source;String zone = ZoneId.systemDefault().getId();
        enqueue(() -> {
            // Flush the source ledger before taking the peer-store monitor: its writers use the opposite boundary.
            for (PenanceEvent event : new PenanceManager(context).durableActivityEvents())
                if (event.getId().equals(ledgerId) && event.getInfraction() != PenanceInfraction.PAID_PAUSE) {
                    append(tributeFact(event), "tribute:" + ledgerId, zone, "", captured);break;
                }
        });
    }

    private static ActivityEvent tributeFact(PenanceEvent event) {
        return new ActivityEvent("tribute_" + event.getId(), ActivityEvent.Kind.TRIBUTE_TRIGGERED,
                event.getCreatedAtMillis(), "", event.getAmountCents(), event.getCurrency(), event.getInfraction().preferenceKey(), 0);
    }

    @Override public void limit(String packageName, boolean perApp, int minutes, Set<String> scope, long observedAt) {
        if (!DeviceRolePreferences.supportsSub(context)) return;
        RemoteStore.Peer captured = source;String zone = ZoneId.systemDefault().getId();
        Set<String> packages = new TreeSet<>(scope);
        enqueue(() -> {
            String configuration = RemoteProtocol.sha256((minutes + ":" + packages).getBytes(StandardCharsets.UTF_8));
            String day = Instant.ofEpochMilli(observedAt).atZone(ZoneId.of(zone)).toLocalDate().toString();
            String key = "limit:" + day + ":" + zone + ":" + (perApp ? packageName : "shared") + ":" + configuration;
            String id = "limit_" + RemoteProtocol.sha256(key.getBytes(StandardCharsets.UTF_8));
            append(new ActivityEvent(id, perApp ? ActivityEvent.Kind.APP_LIMIT_REACHED : ActivityEvent.Kind.SHARED_LIMIT_REACHED,
                    observedAt, packageName, 0, "", "", minutes), key, zone, configuration, captured);
        });
    }

    private void append(ActivityEvent event, String key, String zone, String configuration, RemoteStore.Peer captured) throws Exception {
        append(event,key,zone,configuration,captured,null);
    }

    private void append(ActivityEvent event, String key, String zone, String configuration, RemoteStore.Peer captured, String foreground) throws Exception {
        JSONObject body = RemoteActivityWire.encode(event, zone, configuration);String encoded = RemoteProtocol.canonical(body);
        boolean modified = false, published = false;
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();db.beginTransaction();
            try {
                if(foreground!=null)RemoteActivityStore.writeState(db,"foreground",foreground);
                boolean exists;String priorPeer;String priorBody;
                try (Cursor old = db.rawQuery("SELECT peer,body FROM activity_source WHERE source_key=?", new String[]{key})) {
                    exists = old.moveToFirst();priorPeer = exists ? old.getString(0) : null;priorBody = exists ? old.getString(1) : null;
                }
                boolean permitted = captured != null && activeSource(captured)
                        && event.observedAt >= Long.parseLong(store.metadata(captured.identity, captured.generation, "activity_started_at", "0"));
                if (!exists) {
                    ContentValues row = new ContentValues();row.put("event_id", event.id);row.put("source_key", key);
                    row.put("body", encoded);row.put("observed", event.observedAt);
                    if (permitted) { row.put("peer", captured.identity);row.put("generation", captured.generation); }
                    db.insertOrThrow("activity_source", null, row);modified = true;
                } else if (permitted && priorPeer == null && key.startsWith("tribute:")) {
                    ActivityEvent old = RemoteActivityWire.decode(new JSONObject(priorBody));
                    if (old.observedAt != event.observedAt || old.amountCents != event.amountCents || !old.reason.equals(event.reason)
                            || !old.currency.equals(event.currency)) throw new SecurityException("Ledger identity changed");
                    ContentValues row = new ContentValues();row.put("peer", captured.identity);row.put("generation", captured.generation);row.put("body", encoded);
                    db.update("activity_source", row, "source_key=? AND peer IS NULL", new String[]{key});modified = true;
                }
                if (permitted) published = publishPending(db, captured);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        if (modified) changed();
        if (published) synchronize.run();
    }

    private boolean activeSource(RemoteStore.Peer peer) {
        RemoteStore.Peer current = store.peer(peer.identity, peer.generation);
        return current != null && current.state == RemoteStore.State.ACTIVE && current.localRole == RemoteProtocol.Role.SUB;
    }

    private boolean publishPending(SQLiteDatabase db, RemoteStore.Peer peer) throws Exception {
        if (!activeSource(peer)) return false;
        boolean published = false;
        try (Cursor rows = db.rawQuery("SELECT event_id,body FROM activity_source WHERE peer=? AND generation=? AND published=0 ORDER BY id LIMIT 256",
                new String[]{peer.identity, peer.generation})) {
            while (rows.moveToNext()) {
                JSONObject body = new JSONObject(rows.getString(1));ActivityEvent event = RemoteActivityWire.decode(body);
                store.publish(peer.identity, peer.generation, RemoteProtocol.Stream.EVENTS, event.id, body, false, event.observedAt);
                db.execSQL("UPDATE activity_source SET published=1 WHERE event_id=?", new Object[]{event.id});published = true;
            }
        }
        return published;
    }

    void prepare(RemoteStore.Peer peer) throws Exception {
        if (peer.localRole != RemoteProtocol.Role.SUB) return;
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();db.beginTransaction();
            try { publishPending(db, peer);store.putMetadata(peer.identity, peer.generation, "unavailable_EVENTS", Boolean.toString(gap.get()));db.setTransactionSuccessful(); }
            finally { db.endTransaction(); }
        }
    }

    @Override public Page load(String before, int limit) throws Exception {
        requireRead();checkLimit(limit);
        if(before!=null&&before.startsWith("local:")) {
            String position=before.substring(6);long boundary="MAX".equals(position)?Long.MAX_VALUE:cursor(position);
            synchronized(store){return localPage(boundary,before,limit);}
        }
        long boundary = cursor(before);
        synchronized (store) { return page(boundary, before, limit, null); }
    }

    @Override public Page locate(String entryKey, int limit) throws Exception {
        requireRead();checkLimit(limit);
        if(entryKey!=null&&entryKey.startsWith("local:")) {
            String id=RemoteProtocol.identifier(entryKey.substring(6));
            synchronized(store){try(Cursor row=store.getReadableDatabase().rawQuery("SELECT id FROM activity_source WHERE event_id=?",new String[]{id})){
                if(!row.moveToFirst())return new Page(Collections.emptyList(),null,"local:MAX");
                long boundary=Math.addExact(row.getLong(0),1);return localPage(boundary,"local:"+boundary,limit);
            }}
        }
        checkKey(entryKey);
        synchronized (store) {
            try (Cursor row = store.getReadableDatabase().rawQuery("SELECT a.id FROM " + JOIN + " WHERE " + ELIGIBLE + " AND a.entry_key=?", new String[]{entryKey})) {
                if (!row.moveToFirst()) return new Page(Collections.emptyList(), null, null);
                long boundary = Math.addExact(row.getLong(0), 1);return page(boundary, Long.toString(boundary), limit, null);
            }
        }
    }

    private Page page(long boundary, String requested, int limit, RemoteStore.Peer only) throws Exception {
        List<ActivityEntry> entries = new ArrayList<>();long last = 0;boolean more = false;
        String selection = ELIGIBLE + " AND a.id<?";List<String> args = new ArrayList<>();args.add(Long.toString(boundary));
        if (only != null) { selection += " AND a.peer=? AND a.generation=?";args.add(only.identity);args.add(only.generation); }
        try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT a.id,a.entry_key,a.peer,a.generation,a.body,a.received,a.is_read FROM "
                + JOIN + " WHERE " + selection + " ORDER BY a.id DESC LIMIT " + (limit + 1), args.toArray(new String[0]))) {
            while (rows.moveToNext()) {
                if (entries.size() == limit) { more = true;break; }
                RemoteStore.Peer peer = store.peer(rows.getString(2), rows.getString(3));
                entries.add(entry(peer, rows.getString(1), rows.getString(4), rows.getLong(5), rows.getInt(6) == 0));last = rows.getLong(0);
            }
        }
        return new Page(entries, more ? Long.toString(last) : null, requested);
    }

    private Page localPage(long boundary,String requested,int limit)throws Exception {
        List<ActivityEntry> entries=new ArrayList<>();boolean more=false;long last=0;
        try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT id,event_id,body,observed FROM activity_source WHERE id<? ORDER BY id DESC LIMIT "+(limit+1),new String[]{Long.toString(boundary)})){
            while(rows.moveToNext()){
                if(entries.size()==limit){more=true;break;}
                ActivityEvent event=RemoteActivityWire.decode(new JSONObject(rows.getString(2)));
                entries.add(new ActivityEntry("local:"+rows.getString(1),PersonId.LOCAL,context.getString(R.string.role_this_device),clean(Build.MODEL),event,rows.getLong(3),false,localLabel(event.packageName)));last=rows.getLong(0);
            }
        }
        return new Page(entries,more?"local:"+last:null,requested);
    }

    @Override public List<ActivityEntry> recent(PersonId person, int limit) throws Exception {
        requireRead();checkLimit(limit);
        synchronized (store) {
            if (PersonId.LOCAL.equals(person)) {
                return localPage(Long.MAX_VALUE,"local:MAX",limit).entries;
            }
            RemoteStore.Peer peer = null;
            if (person != null) {
                for (RemoteStore.Peer candidate : store.peers(false))
                    if (candidate.state == RemoteStore.State.ACTIVE && candidate.localRole == RemoteProtocol.Role.DOM
                            && RemotePersonRepository.id(candidate).equals(person)) { peer = candidate;break; }
                if (peer == null) return Collections.emptyList();
            }
            return page(Long.MAX_VALUE, null, limit, peer).entries;
        }
    }

    @Override public int unreadCount() {
        requireRead();synchronized (store) {
            try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + JOIN + " WHERE " + ELIGIBLE + " AND a.is_read=0", null)) {
                return rows.moveToFirst() ? (int) Math.min(Integer.MAX_VALUE, rows.getLong(0)) : 0;
            }
        }
    }

    @Override public void markRead(List<String> keys) throws Exception {
        requireRead();if (keys == null || keys.size() > 100) throw new IllegalArgumentException("Invalid activity acknowledgement");
        List<ActivityEntry> cancel = new ArrayList<>();
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();db.beginTransaction();
            try {
                for (String key : new HashSet<>(keys)) {
                    checkKey(key);
                    try (Cursor row = db.rawQuery("SELECT a.peer,a.generation,a.body,a.received FROM " + JOIN + " WHERE " + ELIGIBLE + " AND a.entry_key=? AND a.is_read=0", new String[]{key})) {
                        if (!row.moveToFirst()) continue;
                        cancel.add(entry(store.peer(row.getString(0), row.getString(1)), key, row.getString(2), row.getLong(3), false));
                        db.execSQL("UPDATE activity_receipts SET is_read=1 WHERE entry_key=?", new Object[]{key});
                    }
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        for (ActivityEntry entry : cancel) notifications.cancel(context, entry);
        if (!cancel.isEmpty()) changed();
    }

    private ActivityEntry entry(RemoteStore.Peer peer, String key, String body, long received, boolean unread) throws Exception {
        ActivityEvent event = RemoteActivityWire.decode(new JSONObject(body));String device = "", label = event.packageName;
        JSONObject runtime = store.mirrored(peer.identity, peer.generation, RemoteProtocol.Stream.RUNTIME, "current");
        if (RemoteTelemetry.validRuntime(runtime)) device = clean(runtime.optString("device"));
        if (!event.packageName.isEmpty()) {
            JSONObject app = store.mirrored(peer.identity, peer.generation, RemoteProtocol.Stream.APP_CATALOG, event.packageName);
            if (app != null && event.packageName.equals(app.optString("package"))) label = clean(app.optString("label", label));
        }
        return new ActivityEntry(key, RemotePersonRepository.id(peer), peer.name, device, event, received, unread, label);
    }

    void dispatchNotifications() {
        if (!notificationQueued.compareAndSet(false, true)) return;
        if (!enqueue(() -> { notificationQueued.set(false);notifyPending(); })) notificationQueued.set(false);
    }

    private void notifyPending() throws Exception {
        if (!DeviceRolePreferences.supportsDom(context) || !notifications.enabled(context)) return;
        boolean progress = false;int count = 0;
        synchronized (store) {
            try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT a.entry_key,a.peer,a.generation,a.body,a.received FROM "
                    + JOIN + " WHERE " + ELIGIBLE + " AND a.is_read=0 AND a.notified=0 ORDER BY a.id LIMIT 100", null)) {
                while (rows.moveToNext()) {
                    count++;RemoteStore.Peer peer = store.peer(rows.getString(1), rows.getString(2));
                    ActivityEntry entry = entry(peer, rows.getString(0), rows.getString(3), rows.getLong(4), true);
                    if (DeviceRolePreferences.supportsDom(context) && notifications.show(context, entry)) {
                        store.getWritableDatabase().execSQL("UPDATE activity_receipts SET notified=1 WHERE entry_key=?", new Object[]{entry.key});progress = true;
                    }
                }
            }
        }
        if (progress && count == 100) dispatchNotifications();
    }

    private void requireRead() {
        if (!DeviceRolePreferences.supportsDom(context) || !ControllerPinManager.isDomModeActive()) throw new SecurityException("Dom access required");
    }
    private static void checkLimit(int value) { if (value < 1 || value > 100) throw new IllegalArgumentException("Invalid activity page size"); }
    private static void checkKey(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid activity entry"); }
    private static long cursor(String value) {
        if (value == null) return Long.MAX_VALUE;
        if (!value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid activity cursor");
        long number = Long.parseLong(value);if (number <= 0) throw new IllegalArgumentException("Invalid activity cursor");return number;
    }
    private String localLabel(String packageName) {
        if (packageName.isEmpty()) return "";
        try { return clean(context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(packageName, 0)).toString()); }
        catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return packageName; }
    }
    private static String clean(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < 160; i++) {
            char c = value.charAt(i);if (!Character.isISOControl(c) && !(c >= 0x202a && c <= 0x202e) && !(c >= 0x2066 && c <= 0x2069)) result.append(c);
        }
        return result.toString().trim();
    }
    private interface Operation { void run() throws Exception; }
    private boolean enqueue(Operation operation) {
        try {
            worker.execute(() -> { try { operation.run(); } catch (Exception unavailable) { gap.set(true);android.util.Log.w("PeerSync", "Activity recording deferred"); } });return true;
        } catch (RejectedExecutionException unavailable) { gap.set(true);android.util.Log.w("PeerSync", "Activity queue unavailable");return false; }
    }
    private static void changed() { try { ActivitySources.changed(); } catch (RuntimeException unavailable) { android.util.Log.w("PeerSync", "Activity observer unavailable"); } }
    @Override public void close() throws Exception {
        RemoteActivityRecorder.remove(this);if (observer != null) observer.close();worker.shutdown();
        if (!worker.awaitTermination(10, TimeUnit.SECONDS)) worker.shutdownNow();
    }
}
