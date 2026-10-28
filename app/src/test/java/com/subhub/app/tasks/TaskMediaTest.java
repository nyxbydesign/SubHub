package com.subhub.app.tasks;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TaskMediaTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);
    private static final String HASH = String.join("", Collections.nCopies(64, "a"));

    private static TaskDefinition task(String id, TaskAttachment attachment) {
        return new TaskDefinition(id, "Task", "Instructions", DATE.atTime(9, 0),
                ZoneId.of("Europe/Berlin"), 60_000, EnumSet.allOf(DayOfWeek.class),
                true, false, attachment);
    }

    private static void create(TaskEngine engine, TaskDefinition task) throws Exception {
        engine.apply(new TaskAction("create-" + task.id, task.id, TaskAction.Kind.CREATE, null,
                task.start(DATE).toEpochMilli() - 1, "", "", List.of(), task), true);
    }

    @Test public void referenceCollectionDeduplicatesMatchingAssignmentAndProofMetadata() throws Exception {
        TaskAttachment attachment = new TaskAttachment(HASH, "image/png", 100);
        TaskDefinition task = task("task-a", attachment);
        TaskEngine engine = new TaskEngine();
        create(engine, task);
        engine.apply(new TaskAction("proof", task.id, TaskAction.Kind.SUBMIT, DATE,
                task.start(DATE).toEpochMilli(), "", "", List.of(attachment), null), false);
        Map<String, TaskAttachment> references = TaskMedia.references(engine);
        assertEquals(Collections.singleton(HASH), references.keySet());
        assertEquals(attachment.mime, references.get(HASH).mime);
        assertEquals(attachment.length, references.get(HASH).length);
    }

    @Test public void publishedMediaIsRestrictedToTheActionWriter() throws Exception {
        TaskAttachment assignment = new TaskAttachment(HASH, "image/png", 100);
        String proofHash = String.join("", Collections.nCopies(64, "b"));
        TaskAttachment proof = new TaskAttachment(proofHash, "image/jpeg", 200);
        TaskDefinition task = task("task-a", assignment);
        TaskEngine engine = new TaskEngine();
        create(engine, task);
        engine.apply(new TaskAction("proof", task.id, TaskAction.Kind.SUBMIT, DATE,
                task.start(DATE).toEpochMilli(), "", "", List.of(proof), null), false);
        assertEquals(Collections.singleton(HASH), TaskMedia.published(engine, true).keySet());
        assertEquals(Collections.singleton(proofHash), TaskMedia.published(engine, false).keySet());
        assertEquals(java.util.Set.of(HASH, proofHash), TaskMedia.references(engine).keySet());
    }

    @Test public void conflictingAssignmentIsRejectedWithoutChangingProjectionOrReferences() throws Exception {
        for (TaskAttachment conflicting : List.of(new TaskAttachment(HASH, "image/jpeg", 100),
                new TaskAttachment(HASH, "image/png", 101))) {
            TaskEngine engine = new TaskEngine();
            TaskAttachment original = new TaskAttachment(HASH, "image/png", 100);
            create(engine, task("task-a", original));
            String before = com.subhub.app.remote.RemoteProtocol.canonical(engine.json());
            assertThrows(SecurityException.class, () -> create(engine, task("task-b", conflicting)));
            assertEquals(before, com.subhub.app.remote.RemoteProtocol.canonical(engine.json()));
            assertEquals(1, engine.definitions().size());
            assertThrows(IllegalArgumentException.class, () -> engine.occurrence("task-b", DATE, 1));
            assertEquals(Collections.singleton(HASH), TaskMedia.references(engine).keySet());
            TaskEngine restored = TaskEngine.parse(new org.json.JSONObject(engine.json().toString()));
            assertEquals(before, com.subhub.app.remote.RemoteProtocol.canonical(restored.json()));
            create(engine, task("task-b", original));
            assertEquals(2, engine.definitions().size());
            assertEquals(Collections.singleton(HASH), TaskMedia.references(engine).keySet());
        }
    }

    @Test public void mp4AudioVideoAliasesShareReferencesAcrossWritersAndSurviveReplay() throws Exception {
        for (String assignmentMime : List.of("audio/mp4", "video/mp4")) {
            String proofMime = assignmentMime.equals("audio/mp4") ? "video/mp4" : "audio/mp4";
            TaskAttachment assignment = new TaskAttachment(HASH, assignmentMime, 100);
            TaskAttachment proof = new TaskAttachment(HASH, proofMime, 100);
            TaskDefinition task = task("task-a", assignment);
            TaskEngine engine = new TaskEngine();
            create(engine, task);
            create(engine, task("task-b", proof));
            long at = task.start(DATE).toEpochMilli();
            engine.apply(new TaskAction("proof", task.id, TaskAction.Kind.SUBMIT, DATE,
                    at, "", "", List.of(proof), null), false);
            String before = com.subhub.app.remote.RemoteProtocol.canonical(engine.json());
            TaskEngine restored = TaskEngine.parse(new org.json.JSONObject(engine.json().toString()));
            assertEquals(before, com.subhub.app.remote.RemoteProtocol.canonical(restored.json()));
            assertEquals(Collections.singleton(HASH), TaskMedia.references(restored).keySet());
            assertTrue(TaskMedia.references(restored).get(HASH).compatible(assignment));
            assertTrue(TaskMedia.references(restored).get(HASH).compatible(proof));
            assertEquals(Collections.singleton(HASH), TaskMedia.published(restored, true).keySet());
            assertEquals(Collections.singleton(HASH), TaskMedia.published(restored, false).keySet());
            assertEquals(proofMime, restored.occurrence(task.id, DATE, at).submission.attachments.get(0).mime);
            assertTrue(restored.apply(new TaskAction("accept", task.id, TaskAction.Kind.ACCEPT, DATE,
                    at + 1, "", "proof", List.of(), null), true));
            assertEquals(TaskEngine.Status.ACCEPTED, restored.occurrence(task.id, DATE, at).status);
        }
    }

    @Test public void referenceCollectionIncludesProofsAfterExplicitReviewAndReplay() throws Exception {
        TaskDefinition task = task("task-a", null);
        TaskEngine engine = new TaskEngine();
        create(engine, task);
        long at = task.start(DATE).toEpochMilli();
        engine.apply(new TaskAction("proof", task.id, TaskAction.Kind.SUBMIT, DATE,
                at, "", "", List.of(new TaskAttachment(HASH, "image/png", 100)), null), false);
        engine.apply(new TaskAction("review", task.id, TaskAction.Kind.ACCEPT, DATE,
                at + 1, "", "proof", List.of(), null), true);
        TaskEngine restored = TaskEngine.parse(new org.json.JSONObject(engine.json().toString()));
        assertEquals(Collections.singleton(HASH), TaskMedia.references(restored).keySet());
    }
}
