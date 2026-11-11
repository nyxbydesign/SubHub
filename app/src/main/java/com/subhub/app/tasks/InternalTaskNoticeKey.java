package com.subhub.app.tasks;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/** Bounded metadata lets cancellation recover an exact notice without consulting physical notices. */
final class InternalTaskNoticeKey {
    final String task;
    final LocalDate date;
    final boolean cancellation;
    private InternalTaskNoticeKey(String task, LocalDate date, boolean cancellation) {
        this.task = task; this.date = date; this.cancellation = cancellation;
    }
    static String create(String task, LocalDate date, boolean cancellation, String kind, String effect) {
        if (task == null || !task.matches("[A-Za-z0-9_-]{1,128}") || kind == null || effect == null)
            throw new IllegalArgumentException("Invalid internal notice identity");
        return "v1:" + task + ":" + (date == null ? "series" : date) + ":" + (cancellation ? "cancel" : "event") + ":"
                + UUID.nameUUIDFromBytes((kind + ":" + effect).getBytes(StandardCharsets.UTF_8));
    }
    static InternalTaskNoticeKey parse(String key) {
        String[] fields = key.split(":", -1);
        if (fields.length != 5 || !fields[0].equals("v1") || !fields[1].matches("[A-Za-z0-9_-]{1,128}")
                || !fields[3].equals("cancel") && !fields[3].equals("event")
                || !UUID.fromString(fields[4]).toString().equals(fields[4]))
            throw new IllegalStateException("Unknown internal notice identity");
        return new InternalTaskNoticeKey(fields[1], fields[2].equals("series") ? null : LocalDate.parse(fields[2]), fields[3].equals("cancel"));
    }
}
