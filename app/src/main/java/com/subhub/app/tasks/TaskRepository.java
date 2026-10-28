package com.subhub.app.tasks;

import android.content.Context;
import android.net.Uri;
import com.subhub.app.people.*;
import com.subhub.app.remote.*;
import com.subhub.app.security.*;
import java.io.*;
import java.time.*;
import java.util.*;

/** Every operation resolves the selected person and live pairing again, including after a picker. */
public final class TaskRepository {
    private final Context context;
    public final PersonId person;
    private final RemoteFeature remote;
    public TaskRepository(Context context, PersonId person) {
        this.context = context.getApplicationContext(); this.person = person; remote = RemoteFeature.get(context);
    }
    public static final class Target {
        public final String scope;
        public final RemoteStore.Peer peer;
        Target(String scope, RemoteStore.Peer peer) { this.scope = scope; this.peer = peer; }
    }
    public Target target() {
        if (person.isLocal()) {
            if (!DeviceRolePreferences.supportsSub(context)) throw new SecurityException("Sub profile unavailable");
            for (RemoteStore.Peer peer : remote.store.peers(false))
                if (peer.localRole == RemoteProtocol.Role.SUB && peer.state == RemoteStore.State.ACTIVE)
                    return new Target(peer.identity + "_" + peer.generation, peer);
            return new Target("local", null);
        }
        if (!ControllerPinManager.isDomModeActive() || !DeviceRolePreferences.supportsDom(context))
            throw new SecurityException("Dom access required");
        RemoteStore.Peer peer = new RemotePersonRepository(context, remote).peer(person);
        if (peer.localRole != RemoteProtocol.Role.DOM || peer.state != RemoteStore.State.ACTIVE)
            throw new SecurityException("Pairing is no longer active");
        return new Target(peer.identity + "_" + peer.generation, peer);
    }
    public boolean isDom() { target(); return ControllerPinManager.isDomModeActive(); }
    public boolean supported() {
        Target target = target();
        return target.peer == null || "1".equals(remote.store.metadata(target.peer.identity, target.peer.generation, "tasks_version", "0"))
                && (cancellationSupported() || !TaskRuntime.get(context).hasCancellations(target.scope));
    }
    public boolean allowed() {
        if (person.isLocal()) return SubFeaturePolicy.allowed(context, "tasks");
        Target target = target();
        return "true".equals(remote.store.metadata(target.peer.identity, target.peer.generation, "tasks_allowed", "false"));
    }
    public void setAllowed(boolean value) {
        if (!person.isLocal() || DeviceRolePreferences.isDomWorkspace(context)) throw new SecurityException("Local Sub choice required");
        if (!SubFeaturePolicy.setAllowed(context, "tasks", value)) throw new IllegalStateException("Could not save Tasks preference");
        TaskRuntime.get(context).changed();
    }
    public TaskEngine engine() throws Exception { return TaskRuntime.get(context).store.load(target().scope); }
    public List<TaskEngine.Occurrence> list(long now) throws Exception {
        Target target = target();
        if (allowed()) TaskRuntime.get(context).sweep(target);
        TaskEngine engine = TaskRuntime.get(context).store.load(target.scope);
        List<TaskEngine.Occurrence> result = new ArrayList<>(engine.recorded(now));
        Set<String> present = new HashSet<>(); for (TaskEngine.Occurrence row : result) present.add(row.id);
        for (TaskDefinition task : engine.definitions()) {
            if (engine.seriesRefused(task.id) || engine.seriesCancelled(task.id)) continue;
            LocalDate today = Instant.ofEpochMilli(now).atZone(task.zone).toLocalDate();
            LocalDate upcoming = task.nextDate(today);
            if (upcoming != null) {
                TaskEngine.Occurrence row = engine.occurrence(task.id, upcoming, now);
                if (present.add(row.id)) result.add(row);
            }
            // Include overlapping durations and the next scheduled day.
            LocalDate date = today.minusDays((task.durationMillis + 86_399_999L) / 86_400_000L);
            for (int i = 0; i < 39; i++, date = date.plusDays(1)) {
                if (!task.occursOn(date)) continue;
                TaskEngine.Occurrence row = engine.occurrence(task.id, date, now);
                if (present.add(row.id)) result.add(row);
                if (task.start(date).toEpochMilli() > now) break;
            }
        }
        result.sort(Comparator.comparingLong((TaskEngine.Occurrence row) -> row.task.start(row.date).toEpochMilli()).reversed());
        return result;
    }
    public ZoneId schedulingZone() {
        Target target = target();
        if (person.isLocal()) return ZoneId.systemDefault();
        synchronized (remote.store) {
            Target live = target();
            String zone = remote.store.metadata(live.peer.identity, live.peer.generation, "tasks_timezone", "");
            long received;
            try { received = Long.parseLong(remote.store.metadata(live.peer.identity, live.peer.generation, "tasks_timezone_received", "0")); }
            catch (NumberFormatException invalid) { received = 0; }
            return TaskTimezone.require(zone, received, System.currentTimeMillis());
        }
    }
    public void create(TaskDefinition definition) throws Exception {
        Target target = target(); requireDom(target);
        long now = System.currentTimeMillis();
        if (definition.start(definition.nextDate(definition.firstStart.toLocalDate())).toEpochMilli() < now - 60_000)
            throw new IllegalArgumentException("Choose a future start time");
        requireMedia(target, definition.attachment == null ? List.of() : List.of(definition.attachment));
        append(target, new TaskAction(UUID.randomUUID().toString(), definition.id, TaskAction.Kind.CREATE, null,
                now, "", "", List.of(), definition), true);
    }
    public void act(String taskId, LocalDate date, TaskAction.Kind kind, String text,
            List<TaskAttachment> media, String submissionId) throws Exception {
        Target target = target();
        boolean dom = kind == TaskAction.Kind.ACCEPT || kind == TaskAction.Kind.DENY;
        if (kind == TaskAction.Kind.CANCEL_ONCE || kind == TaskAction.Kind.CANCEL_SERIES) throw new SecurityException("Use task cancellation");
        if (dom) requireDom(target); else requireSub(target);
        if (kind == TaskAction.Kind.CREATE || kind == TaskAction.Kind.TIMEOUT || kind == TaskAction.Kind.SKIP)
            throw new SecurityException("Invalid user action");
        requireMedia(target, media);
        append(target, new TaskAction(UUID.randomUUID().toString(), taskId, kind, date,
                System.currentTimeMillis(), text, submissionId, media, null), dom);
    }
    public boolean cancellationSupported() {
        Target target = target();
        return target.peer == null || "1".equals(remote.store.metadata(target.peer.identity,
                target.peer.generation, "tasks_cancel_version", "0"));
    }
    public void cancel(String taskId, LocalDate date, boolean series) throws Exception {
        synchronized (SubFeaturePolicy.LOCK) {
            synchronized (remote.store) {
                Target target = target(); requireDom(target);
                if (!cancellationSupported()) throw new IllegalStateException("Update both devices to cancel tasks");
                TaskEngine engine = TaskRuntime.get(context).store.load(target.scope);
                TaskEngine.Occurrence occurrence = engine.occurrence(taskId, date, System.currentTimeMillis());
                if (series && !occurrence.task.recurring()) series = false;
                if (series ? engine.seriesCancelled(taskId) : engine.cancelled(taskId, date)) return;
                TaskAction action = new TaskAction(UUID.randomUUID().toString(), taskId,
                        series ? TaskAction.Kind.CANCEL_SERIES : TaskAction.Kind.CANCEL_ONCE,
                        date, System.currentTimeMillis(), "", "", List.of(), null);
                append(target, action, true);
            }
        }
    }

    private void append(Target target, TaskAction action, boolean dom) throws Exception {
        synchronized (SubFeaturePolicy.LOCK) {
            synchronized (remote.store) {
                Target live = target();
                if (!live.scope.equals(target.scope) || !allowed() && !action.cancellation()) throw new SecurityException("Tasks are unavailable");
                if (dom) requireDom(live); else requireSub(live);
                TaskRuntime runtime = TaskRuntime.get(context);
                if (action.kind == TaskAction.Kind.CREATE) {
                    for (TaskDefinition existing : runtime.store.load(target.scope).definitions()) {
                        if (!existing.id.equals(action.taskId)) continue;
                        if (!RemoteProtocol.canonical(existing.json()).equals(RemoteProtocol.canonical(action.definition.json())))
                            throw new SecurityException("Conflicting task retry");
                        return;
                    }
                    if (!schedulingZone().equals(action.definition.zone))
                        throw new IllegalStateException("Sub timezone changed; reload the schedule");
                }
                runtime.store.append(target.scope, action, dom, target.peer == null && !dom);
                runtime.effects(target); runtime.changed();
            }
        }
    }
    private void requireDom(Target target) {
        if (!supported()) throw new SecurityException("Update both paired devices to use Tasks");
        if (!ControllerPinManager.isDomModeActive() || !ControllerPinManager.hasCredentials(context)
                || (target.peer != null && target.peer.localRole != RemoteProtocol.Role.DOM)
                || (person.isLocal() && !PersonRoutes.canEditLocal(context, person)))
            throw new SecurityException("Task author access required");
    }
    private void requireSub(Target target) {
        if (!person.isLocal() || DeviceRolePreferences.isDomWorkspace(context)
                || ControllerPinManager.isDomModeActive()
                || (target.peer != null && target.peer.localRole != RemoteProtocol.Role.SUB))
            throw new SecurityException("Sub access required");
    }
    private void requireMedia(Target target, List<TaskAttachment> attachments) throws Exception {
        RemoteAssets assets = TaskRuntime.get(context).assets(target.scope);
        for (TaskAttachment attachment : attachments)
            if (!assets.isComplete(attachment.hash) || assets.length(attachment.hash) != attachment.length)
                throw new IllegalArgumentException("Attachment is not ready");
    }
    public TaskAttachment importMedia(Uri uri) throws Exception {
        Target target = target();
        if (isDom()) requireDom(target); else requireSub(target);
        if (!allowed()) throw new SecurityException("Tasks are disabled");
        return TaskMedia.importMedia(context, uri, TaskRuntime.get(context).assets(target.scope));
    }
    public InputStream openMedia(TaskAttachment attachment) throws Exception {
        Target target = target();
        if (!allowed()) throw new SecurityException("Tasks are disabled");
        // A caller must name an attachment actually visible in this relationship's projection.
        if (!TaskMedia.references(engine()).containsKey(attachment.hash)) throw new SecurityException("Unreferenced task attachment");
        return TaskRuntime.get(context).assets(target.scope).open(attachment.hash);
    }
    public File mediaFile(TaskAttachment attachment) throws Exception {
        Target target = target();
        try (InputStream ignored = openMedia(attachment)) {
            return TaskRuntime.get(context).assets(target.scope).finish(attachment.hash);
        }
    }
}
