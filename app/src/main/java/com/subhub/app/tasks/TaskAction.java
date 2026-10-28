package com.subhub.app.tasks;

import com.subhub.app.remote.RemoteProtocol;
import org.json.*;
import java.time.LocalDate;
import java.util.*;

/** An immutable user action has its own retry identity and references one stable occurrence. */
public final class TaskAction {
    public enum Kind { CREATE, SUBMIT, OUTSIDE, ACCEPT, DENY, REFUSE_ONCE, REFUSE_SERIES, TIMEOUT, SKIP }
    public final String id, taskId, text, submissionId;
    public final Kind kind;
    public final LocalDate date;
    public final long at;
    public final List<TaskAttachment> attachments;
    public final TaskDefinition definition;

    public TaskAction(String id, String taskId, Kind kind, LocalDate date, long at, String text,
            String submissionId, List<TaskAttachment> attachments, TaskDefinition definition) {
        this.id = RemoteProtocol.identifier(id); this.taskId = RemoteProtocol.identifier(taskId);
        if (kind == null || at <= 0 || text == null || text.length() > 8000 || submissionId == null
                || attachments == null || attachments.size() > 5 || attachments.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Invalid task action");
        if (kind == Kind.CREATE) {
            if (definition == null || !definition.id.equals(taskId) || date != null) throw new IllegalArgumentException("Invalid task definition");
        } else if (definition != null || date == null) throw new IllegalArgumentException("Missing occurrence");
        boolean proof = kind == Kind.SUBMIT;
        if (!proof && (!attachments.isEmpty() || !text.isEmpty())) throw new IllegalArgumentException("Unexpected proof");
        if (proof && attachments.isEmpty() && text.trim().isEmpty()) throw new IllegalArgumentException("Add text or an attachment");
        if (kind == Kind.ACCEPT || kind == Kind.DENY) RemoteProtocol.identifier(submissionId);
        else if (!submissionId.isEmpty()) throw new IllegalArgumentException("Unexpected submission identity");
        Set<String> hashes = new HashSet<>();
        for (TaskAttachment attachment : attachments) if (!hashes.add(attachment.hash)) throw new IllegalArgumentException("Duplicate attachment");
        this.kind = kind; this.date = date; this.at = at; this.text = text; this.submissionId = submissionId;
        this.attachments = Collections.unmodifiableList(new ArrayList<>(attachments)); this.definition = definition;
    }
    public boolean domAction() { return kind == Kind.CREATE || kind == Kind.ACCEPT || kind == Kind.DENY; }
    public JSONObject json() throws JSONException {
        JSONArray media = new JSONArray(); for (TaskAttachment attachment : attachments) media.put(attachment.json());
        return new JSONObject().put("id", id).put("task", taskId).put("kind", kind.name())
                .put("date", date == null ? JSONObject.NULL : date.toString()).put("at", at).put("text", text)
                .put("submission", submissionId).put("attachments", media)
                .put("definition", definition == null ? JSONObject.NULL : definition.json());
    }
    public static TaskAction parse(JSONObject value) throws JSONException {
        TaskJson.keys(value, Set.of("id", "task", "kind", "date", "at", "text", "submission", "attachments", "definition"));
        JSONArray rows = value.getJSONArray("attachments");
        if (rows.length() > 5) throw new JSONException("At most five proof attachments");
        List<TaskAttachment> media = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) media.add(TaskAttachment.parse(rows.getJSONObject(i)));
        return new TaskAction(TaskJson.text(value, "id", 128), TaskJson.text(value, "task", 128),
                Kind.valueOf(TaskJson.text(value, "kind", 20)), value.isNull("date") ? null
                : LocalDate.parse(TaskJson.text(value, "date", 10)), RemoteProtocol.integer(value, "at"),
                TaskJson.text(value, "text", 8000), TaskJson.text(value, "submission", 128), media,
                value.isNull("definition") ? null : TaskDefinition.parse(value.getJSONObject("definition")));
    }
}
