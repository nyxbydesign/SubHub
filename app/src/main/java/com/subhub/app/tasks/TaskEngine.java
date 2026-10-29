package com.subhub.app.tasks;

import com.subhub.app.remote.RemoteProtocol;
import org.json.*;
import java.time.*;
import java.util.*;

/** Pure task projection. The Sub owns deadline outcomes; a timely pending proof never times out. */
public final class TaskEngine {
    public enum Status { SCHEDULED, ACTIVE, PENDING, ACCEPTED, DENIED, TIMED_OUT, REFUSED, SKIPPED, CANCELLED }
    public static final class Occurrence {
        public final TaskDefinition task;
        public final LocalDate date;
        public final String id;
        public final Status status;
        public final TaskAction submission, review;
        Occurrence(TaskDefinition task, LocalDate date, Status status, TaskAction submission, TaskAction review) {
            this.task = task; this.date = date; id = task.occurrenceId(date); this.status = status;
            this.submission = submission; this.review = review;
        }
        public boolean failed() { return status == Status.DENIED || status == Status.TIMED_OUT; }
    }
    private final Map<String, TaskDefinition> tasks = new LinkedHashMap<>();
    private final Map<String, TaskAction> seen = new LinkedHashMap<>();
    private final Map<String, Status> states = new HashMap<>();
    private final Map<String, TaskAction> submissions = new HashMap<>();
    private final Map<String, TaskAction> reviews = new HashMap<>();
    private final Set<String> refusedSeries = new HashSet<>();
    private final Set<String> cancelledOccurrences = new HashSet<>(), cancelledSeries = new HashSet<>();
    private final Map<String, TaskAttachment> media = new HashMap<>();

    public static int activeCount(Collection<Occurrence> occurrences) {
        Set<String> active = new HashSet<>();
        for (Occurrence occurrence : occurrences)
            if (occurrence.status == Status.ACTIVE) active.add(occurrence.id);
        return active.size();
    }

    public List<TaskDefinition> definitions() { return new ArrayList<>(tasks.values()); }
    public boolean seriesRefused(String id) { return refusedSeries.contains(id); }
    public boolean seriesCancelled(String id) { return cancelledSeries.contains(id); }
    public boolean cancelled(String id, LocalDate date) {
        return cancelledSeries.contains(id) || date != null && cancelledOccurrences.contains(id + ":" + date);
    }
    public boolean hasCancellations() { return !cancelledSeries.isEmpty() || !cancelledOccurrences.isEmpty(); }
    public TaskAction action(String id) { return seen.get(id); }
    public Occurrence occurrence(String id, LocalDate date, long now) {
        TaskDefinition task = tasks.get(id);
        if (task == null) throw new IllegalArgumentException("Task unavailable");
        String key = task.occurrenceId(date);
        Status state = states.get(key);
        if (cancelled(id, date)) state = Status.CANCELLED;
        if (state == null) state = refusedSeries.contains(id) ? Status.REFUSED
                : now < task.start(date).toEpochMilli() ? Status.SCHEDULED : Status.ACTIVE;
        TaskAction submission = state == Status.CANCELLED ? null : submissions.get(key);
        return new Occurrence(task, date, state, submission,
                submission == null ? null : reviews.get(submission.id));
    }
    /** A duplicate exact action is acknowledged without repeating its state transition or effects. */
    public boolean apply(TaskAction action, boolean actorDom) throws JSONException {
        if (actorDom != action.domAction()) throw new SecurityException("Wrong task author");
        TaskAction previous = seen.get(action.id);
        if (previous != null) {
            if (!RemoteProtocol.canonical(previous.json()).equals(RemoteProtocol.canonical(action.json())))
                throw new SecurityException("Conflicting task retry");
            return false;
        }
        if (seen.size() >= 20_000) throw new IllegalStateException("Task history is full");
        List<TaskAttachment> attachments = new ArrayList<>(action.attachments);
        if (action.definition != null && action.definition.attachment != null) attachments.add(action.definition.attachment);
        for (TaskAttachment attachment : attachments) {
            TaskAttachment previousMedia = media.get(attachment.hash);
            if (previousMedia != null && !previousMedia.compatible(attachment))
                throw new SecurityException("Conflicting task attachment");
        }
        if (action.kind == TaskAction.Kind.CREATE) {
            if (tasks.containsKey(action.taskId)) throw new SecurityException("Task identity already exists");
            if (tasks.size() >= 250) throw new IllegalStateException("Task limit reached");
            tasks.put(action.taskId, action.definition);
        } else {
            Occurrence occurrence = occurrence(action.taskId, action.date, action.at);
            TaskDefinition task = occurrence.task;
            String key = occurrence.id;
            if (action.cancellation()) {
                if (action.kind == TaskAction.Kind.CANCEL_SERIES) cancelledSeries.add(task.id);
                else cancelledOccurrences.add(key);
                states.put(key, Status.CANCELLED);
            } else if (!cancelled(task.id, action.date)) switch (action.kind) {
                case SUBMIT: case OUTSIDE:
                    if (occurrence.status != Status.ACTIVE || action.at >= task.end(action.date).toEpochMilli())
                        throw new IllegalStateException("Task is not accepting completion requests");
                    states.put(key, Status.PENDING); submissions.put(key, action); break;
                case ACCEPT: case DENY:
                    if (occurrence.status != Status.PENDING || occurrence.submission == null
                            || !occurrence.submission.id.equals(action.submissionId))
                        throw new IllegalStateException("Completion request is no longer pending");
                    states.put(key, action.kind == TaskAction.Kind.ACCEPT ? Status.ACCEPTED : Status.DENIED);
                    reviews.put(action.submissionId, action); break;
                case REFUSE_ONCE: case REFUSE_SERIES:
                    if (!task.allowRefusal) throw new SecurityException("Task refusal is not enabled");
                    if (occurrence.status != Status.ACTIVE && occurrence.status != Status.SCHEDULED)
                        throw new IllegalStateException("Task can no longer be refused");
                    if (action.at >= task.end(action.date).toEpochMilli()) throw new IllegalStateException("Task has expired");
                    states.put(key, Status.REFUSED);
                    if (action.kind == TaskAction.Kind.REFUSE_SERIES) {
                        refusedSeries.add(task.id);
                        // Already submitted work still gets its explicit review; future days cannot reopen.
                    }
                    break;
                case TIMEOUT: case SKIP:
                    if (action.at < task.end(action.date).toEpochMilli()) throw new IllegalStateException("Deadline has not passed");
                    // An offline refusal or timely proof wins over a later deadline sweep.
                    if (occurrence.status == Status.ACTIVE) states.put(key,
                            action.kind == TaskAction.Kind.SKIP ? Status.SKIPPED : Status.TIMED_OUT);
                    break;
                default: throw new IllegalArgumentException("Invalid task action");
            }
        }
        seen.put(action.id, action);
        for (TaskAttachment attachment : attachments) media.putIfAbsent(attachment.hash, attachment);
        return true;
    }
    public List<Occurrence> recorded(long now) {
        List<Occurrence> result = new ArrayList<>();
        for (String key : states.keySet()) {
            int split = key.indexOf(':');
            result.add(occurrence(key.substring(0, split), LocalDate.parse(key.substring(split + 1)), now));
        }
        return result;
    }
    public JSONObject json() throws JSONException {
        JSONArray actions = new JSONArray(); for (TaskAction action : seen.values()) actions.put(action.json());
        return new JSONObject().put("actions", actions);
    }
    public static TaskEngine parse(JSONObject value) throws JSONException {
        TaskEngine result = new TaskEngine(); JSONArray actions = value.getJSONArray("actions");
        if (actions.length() > 20_000) throw new JSONException("Task history is full");
        for (int i = 0; i < actions.length(); i++) {
            TaskAction action = TaskAction.parse(actions.getJSONObject(i)); result.apply(action, action.domAction());
        }
        return result;
    }
}
