package com.subhub.app.tasks;

import com.subhub.app.remote.RemoteProtocol;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.*;
import java.util.*;

/** A recurring task keeps its wall clock and IANA zone, independently of either device's zone. */
public final class TaskDefinition {
    public static final long MAX_DURATION_MILLIS = 30L * 24 * 60 * 60 * 1000;
    public final String id, title, text;
    public final LocalDateTime firstStart;
    public final ZoneId zone;
    public final long durationMillis;
    public final Set<DayOfWeek> weekdays;
    public final boolean allowRefusal, failureTribute;
    public final TaskAttachment attachment;

    public TaskDefinition(String id, String title, String text, LocalDateTime firstStart, ZoneId zone,
            long durationMillis, Set<DayOfWeek> weekdays, boolean allowRefusal, boolean failureTribute,
            TaskAttachment attachment) {
        this.id = RemoteProtocol.identifier(id);
        if (title == null || title.trim().isEmpty() || title.length() > 160 || text == null
                || text.length() > 8000 || firstStart == null || zone == null || weekdays == null
                || firstStart.getYear() < 2000 || firstStart.getYear() > 2100
                || firstStart.getSecond() != 0 || firstStart.getNano() != 0
                || durationMillis < 60_000 || durationMillis > MAX_DURATION_MILLIS)
            throw new IllegalArgumentException("Check the title, start time and duration");
        this.title = title.trim(); this.text = text; this.firstStart = firstStart; this.zone = zone;
        this.durationMillis = durationMillis;
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class); days.addAll(weekdays);
        this.weekdays = Collections.unmodifiableSet(days);
        this.allowRefusal = allowRefusal; this.failureTribute = failureTribute; this.attachment = attachment;
    }
    public boolean recurring() { return !weekdays.isEmpty(); }
    public boolean occursOn(LocalDate date) {
        return !date.isBefore(firstStart.toLocalDate()) && date.getYear() <= 2100
                && (recurring() ? weekdays.contains(date.getDayOfWeek()) : date.equals(firstStart.toLocalDate()));
    }
    /** Gaps move forward by the DST gap; overlaps use the earlier offset, once per calendar day. */
    public Instant start(LocalDate date) {
        if (!occursOn(date)) throw new IllegalArgumentException("Not a scheduled task day");
        return date.atTime(firstStart.toLocalTime()).atZone(zone).withEarlierOffsetAtOverlap().toInstant();
    }
    public Instant end(LocalDate date) { return start(date).plusMillis(durationMillis); }
    public String occurrenceId(LocalDate date) {
        if (!occursOn(date)) throw new IllegalArgumentException("Not a scheduled task day");
        return id + ":" + date;
    }
    public LocalDate nextDate(LocalDate from) {
        LocalDate date = from.isBefore(firstStart.toLocalDate()) ? firstStart.toLocalDate() : from;
        for (int i = 0; i < 8; i++, date = date.plusDays(1)) if (occursOn(date)) return date;
        return null;
    }
    public JSONObject json() throws JSONException {
        JSONArray days = new JSONArray(); for (DayOfWeek day : weekdays) days.put(day.getValue());
        return new JSONObject().put("id", id).put("title", title).put("text", text)
                .put("start", firstStart.toString()).put("zone", zone.getId()).put("duration", durationMillis)
                .put("weekdays", days).put("allowRefusal", allowRefusal).put("failureTribute", failureTribute)
                .put("attachment", attachment == null ? JSONObject.NULL : attachment.json());
    }
    public static TaskDefinition parse(JSONObject value) throws JSONException {
        TaskJson.keys(value, Set.of("id", "title", "text", "start", "zone", "duration", "weekdays", "allowRefusal", "failureTribute", "attachment"));
        JSONArray rows = value.getJSONArray("weekdays");
        if (rows.length() > 7) throw new JSONException("Too many weekdays");
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (int i = 0; i < rows.length(); i++) {
            Object raw = rows.get(i);
            if (!(raw instanceof Integer) && !(raw instanceof Long)) throw new JSONException("Invalid weekday");
            long number = ((Number) raw).longValue();
            if (number < 1 || number > 7) throw new JSONException("Invalid weekday");
            DayOfWeek day = DayOfWeek.of((int) number);
            if (!days.add(day)) throw new JSONException("Duplicate weekday");
        }
        return new TaskDefinition(TaskJson.text(value, "id", 128), TaskJson.text(value, "title", 160),
                TaskJson.text(value, "text", 8000), LocalDateTime.parse(TaskJson.text(value, "start", 32)),
                ZoneId.of(TaskJson.text(value, "zone", 80)), RemoteProtocol.integer(value, "duration"), days,
                TaskJson.bool(value, "allowRefusal"), TaskJson.bool(value, "failureTribute"),
                value.isNull("attachment") ? null : TaskAttachment.parse(value.getJSONObject("attachment")));
    }
}
