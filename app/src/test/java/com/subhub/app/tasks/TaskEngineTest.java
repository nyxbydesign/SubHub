package com.subhub.app.tasks;

import com.subhub.app.remote.RemoteProtocol;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import static com.subhub.app.tasks.TaskAction.Kind.*;
import static com.subhub.app.tasks.TaskEngine.Status.*;
import static org.junit.Assert.*;

public class TaskEngineTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);

    private static TaskDefinition definition(boolean refusal, boolean tribute) {
        return new TaskDefinition("task-a", "Task", "Instructions", DATE.atTime(9, 0),
                ZoneId.of("Europe/Berlin"), 3_600_000, EnumSet.allOf(DayOfWeek.class),
                refusal, tribute, null);
    }

    private static TaskAction create(TaskDefinition task) {
        return new TaskAction("create-a", task.id, CREATE, null,
                task.start(DATE).toEpochMilli() - 60_000, "", "", Collections.emptyList(), task);
    }

    private static TaskEngine engine(TaskDefinition task) throws Exception {
        TaskEngine engine = new TaskEngine();
        assertTrue(engine.apply(create(task), true));
        return engine;
    }

    private static TaskAction action(String id, TaskDefinition task, TaskAction.Kind kind,
            LocalDate date, long at, String submission) {
        return new TaskAction(id, task.id, kind, date, at, kind == SUBMIT ? "Done" : "",
                submission, Collections.emptyList(), null);
    }

    private static TaskAction action(String id, TaskDefinition task, TaskAction.Kind kind, long at) {
        return action(id, task, kind, DATE, at, "");
    }

    private static TaskEngine replay(TaskEngine engine) throws Exception {
        return TaskEngine.parse(new JSONObject(engine.json().toString()));
    }

    @Test public void scheduleBoundariesRequireAnExplicitSubTimeout() throws Exception {
        TaskDefinition task = definition(true, true);
        TaskEngine engine = engine(task);
        long start = task.start(DATE).toEpochMilli();
        long end = task.end(DATE).toEpochMilli();
        assertEquals(SCHEDULED, engine.occurrence(task.id, DATE, start - 1).status);
        assertEquals(ACTIVE, engine.occurrence(task.id, DATE, start).status);
        assertEquals(ACTIVE, engine.occurrence(task.id, DATE, end + 86_400_000).status);
        TaskAction early = action("early", task, TIMEOUT, end - 1);
        assertThrows(IllegalStateException.class, () -> engine.apply(early, false));
        TaskAction timeout = action("timeout", task, TIMEOUT, end);
        assertThrows(SecurityException.class, () -> engine.apply(timeout, true));
        assertEquals(ACTIVE, engine.occurrence(task.id, DATE, end).status);
        assertTrue(engine.apply(timeout, false));
        assertEquals(TIMED_OUT, engine.occurrence(task.id, DATE, end).status);
        assertTrue(engine.occurrence(task.id, DATE, end).failed());
        assertFalse(engine.apply(TaskAction.parse(new JSONObject(timeout.json().toString())), false));
        assertEquals(TIMED_OUT, replay(engine).occurrence(task.id, DATE, end).status);
    }

    @Test public void proofAndOutsideRequireExplicitReviewOfTheExactPendingRequest() throws Exception {
        for (TaskAction.Kind request : Arrays.asList(SUBMIT, OUTSIDE)) {
            for (TaskAction.Kind review : Arrays.asList(ACCEPT, DENY)) {
                TaskDefinition task = definition(true, true);
                TaskEngine engine = engine(task);
                long start = task.start(DATE).toEpochMilli();
                TaskAction submission = action("request", task, request, start);
                assertTrue(engine.apply(submission, false));
                TaskEngine.Occurrence pending = engine.occurrence(task.id, DATE, start);
                assertEquals(PENDING, pending.status);
                assertFalse(pending.failed());
                assertEquals(submission.id, pending.submission.id);
                TaskAction wrongRequest = action("wrong-review", task, review, DATE, start + 1, "other-request");
                assertThrows(IllegalStateException.class, () -> engine.apply(wrongRequest, true));
                assertEquals(PENDING, engine.occurrence(task.id, DATE, start).status);
                TaskAction decision = action("review", task, review, DATE, start + 2, submission.id);
                assertTrue(engine.apply(decision, true));
                TaskEngine.Status expected = review == ACCEPT ? ACCEPTED : DENIED;
                assertEquals(expected, engine.occurrence(task.id, DATE, start).status);
                assertEquals(review == DENY, engine.occurrence(task.id, DATE, start).failed());
                assertFalse(engine.apply(TaskAction.parse(new JSONObject(decision.json().toString())), true));
                TaskAction opposite = action("second-review", task, review == ACCEPT ? DENY : ACCEPT,
                        DATE, start + 3, submission.id);
                assertThrows(IllegalStateException.class, () -> engine.apply(opposite, true));
                TaskEngine restored = replay(engine);
                assertEquals(expected, restored.occurrence(task.id, DATE, start).status);
                assertEquals(submission.id, restored.occurrence(task.id, DATE, start).submission.id);
            }
        }
    }

    @Test public void proofWithMediaIsPendingRatherThanAutomaticallyAccepted() throws Exception {
        TaskDefinition task = definition(true, true);
        TaskEngine engine = engine(task);
        long start = task.start(DATE).toEpochMilli();
        TaskAttachment photo = new TaskAttachment(String.join("", Collections.nCopies(64, "a")),
                "image/jpeg", 123);
        TaskAction proof = new TaskAction("photo-proof", task.id, SUBMIT, DATE, start, "", "",
                Collections.singletonList(photo), null);
        assertTrue(engine.apply(proof, false));
        TaskEngine.Occurrence result = replay(engine).occurrence(task.id, DATE, start);
        assertEquals(PENDING, result.status);
        assertEquals(1, result.submission.attachments.size());
        assertEquals(photo.hash, result.submission.attachments.get(0).hash);
    }

    @Test public void conflictingProofMetadataLeavesSeenActionsAndOccurrenceUnchanged() throws Exception {
        String hash = String.join("", Collections.nCopies(64, "a"));
        TaskAttachment assignment = new TaskAttachment(hash, "image/png", 100);
        for (TaskAttachment conflicting : Arrays.asList(new TaskAttachment(hash, "image/jpeg", 100),
                new TaskAttachment(hash, "image/png", 101))) {
            TaskDefinition base = definition(true, true);
            TaskDefinition task = new TaskDefinition(base.id, base.title, base.text, base.firstStart,
                    base.zone, base.durationMillis, base.weekdays, base.allowRefusal, base.failureTribute, assignment);
            TaskEngine engine = engine(task);
            long at = task.start(DATE).toEpochMilli();
            String before = RemoteProtocol.canonical(engine.json());
            TaskAction invalid = new TaskAction("proof", task.id, SUBMIT, DATE, at,
                    "", "", Collections.singletonList(conflicting), null);
            assertThrows(SecurityException.class, () -> engine.apply(invalid, false));
            assertEquals(before, RemoteProtocol.canonical(engine.json()));
            assertEquals(before, RemoteProtocol.canonical(replay(engine).json()));
            assertEquals(ACTIVE, engine.occurrence(task.id, DATE, at).status);
            assertNull(engine.occurrence(task.id, DATE, at).submission);
            TaskAction corrected = new TaskAction("proof", task.id, SUBMIT, DATE, at,
                    "", "", Collections.singletonList(assignment), null);
            assertTrue(engine.apply(corrected, false));
            assertEquals(PENDING, replay(engine).occurrence(task.id, DATE, at).status);
            assertFalse(engine.apply(corrected, false));
        }
    }

    @Test public void rejectedTransitionDoesNotReserveItsMediaMetadata() throws Exception {
        TaskDefinition task = definition(true, true);
        TaskEngine engine = engine(task);
        long start = task.start(DATE).toEpochMilli();
        String hash = String.join("", Collections.nCopies(64, "b"));
        String before = RemoteProtocol.canonical(engine.json());
        TaskAction early = new TaskAction("proof", task.id, SUBMIT, DATE, start - 1,
                "", "", Collections.singletonList(new TaskAttachment(hash, "image/png", 100)), null);
        assertThrows(IllegalStateException.class, () -> engine.apply(early, false));
        assertEquals(before, RemoteProtocol.canonical(engine.json()));
        TaskAction valid = new TaskAction("proof", task.id, SUBMIT, DATE, start,
                "", "", Collections.singletonList(new TaskAttachment(hash, "image/jpeg", 200)), null);
        assertTrue(engine.apply(valid, false));
        TaskEngine restored = replay(engine);
        assertEquals(PENDING, restored.occurrence(task.id, DATE, start).status);
        assertEquals("image/jpeg", restored.occurrence(task.id, DATE, start).submission.attachments.get(0).mime);
        assertEquals(200, restored.occurrence(task.id, DATE, start).submission.attachments.get(0).length);
    }

    @Test public void timelyOfflineRequestsRemainPendingAfterDeadlineAndReplay() throws Exception {
        for (TaskAction.Kind request : Arrays.asList(SUBMIT, OUTSIDE)) {
            TaskDefinition task = definition(true, true);
            TaskEngine engine = engine(task);
            long end = task.end(DATE).toEpochMilli();
            TaskAction proof = action("offline-proof", task, request, end - 1);
            assertTrue(engine.apply(TaskAction.parse(new JSONObject(proof.json().toString())), false));
            long later = end + 7 * 86_400_000L;
            assertEquals(PENDING, engine.occurrence(task.id, DATE, later).status);
            engine.apply(action("sweep", task, TIMEOUT, later), false);
            TaskEngine restored = replay(engine);
            assertEquals(PENDING, restored.occurrence(task.id, DATE, later).status);
            assertFalse(restored.occurrence(task.id, DATE, later).failed());
            assertEquals(proof.id, restored.occurrence(task.id, DATE, later).submission.id);
            restored.apply(action("accept-offline", task, ACCEPT, DATE, later + 1, proof.id), true);
            assertEquals(ACCEPTED, restored.occurrence(task.id, DATE, later).status);
        }
    }

    @Test public void completionBeforeStartAndAtOrAfterDeadlineIsRejected() throws Exception {
        for (TaskAction.Kind request : Arrays.asList(SUBMIT, OUTSIDE)) {
            TaskDefinition task = definition(true, true);
            TaskEngine engine = engine(task);
            for (long at : new long[] {task.start(DATE).toEpochMilli() - 1,
                    task.end(DATE).toEpochMilli(), task.end(DATE).toEpochMilli() + 1}) {
                TaskAction invalid = action("invalid-request", task, request, at);
                assertThrows(IllegalStateException.class, () -> engine.apply(invalid, false));
            }
            assertEquals(1, engine.json().getJSONArray("actions").length());
        }
    }

    @Test public void wrongWriterIsRejectedForEveryActionIncludingExactRetries() throws Exception {
        for (TaskAction.Kind kind : TaskAction.Kind.values()) {
            TaskDefinition task = definition(true, true);
            TaskEngine engine = engine(task);
            long at = task.start(DATE).toEpochMilli();
            TaskAction attempt = kind == CREATE ? create(task)
                    : action("wrong-writer", task, kind, DATE, at,
                            kind == ACCEPT || kind == DENY ? "request" : "");
            String before = RemoteProtocol.canonical(engine.json());
            assertThrows(kind.name(), SecurityException.class,
                    () -> engine.apply(attempt, !attempt.domAction()));
            assertEquals(before, RemoteProtocol.canonical(engine.json()));
        }
    }

    @Test public void disabledRefusalRejectsBothScopesWithoutRecordingAnAction() throws Exception {
        TaskDefinition task = definition(false, true);
        TaskEngine engine = engine(task);
        for (TaskAction.Kind kind : Arrays.asList(REFUSE_ONCE, REFUSE_SERIES)) {
            TaskAction refusal = action("refusal", task, kind, task.start(DATE).toEpochMilli());
            assertThrows(SecurityException.class, () -> engine.apply(refusal, false));
        }
        assertEquals(1, engine.json().getJSONArray("actions").length());
        assertFalse(engine.seriesRefused(task.id));
        assertEquals(ACTIVE, engine.occurrence(task.id, DATE, task.start(DATE).toEpochMilli()).status);
    }

    @Test public void refusalOnceLeavesLaterOccurrencesActive() throws Exception {
        TaskDefinition task = definition(true, true);
        TaskEngine engine = engine(task);
        long at = task.start(DATE).toEpochMilli() - 1;
        engine.apply(action("refuse-once", task, REFUSE_ONCE, at), false);
        engine.apply(action("sweep", task, TIMEOUT, task.end(DATE).toEpochMilli()), false);
        TaskEngine restored = replay(engine);
        assertEquals(REFUSED, restored.occurrence(task.id, DATE, at).status);
        assertFalse(restored.occurrence(task.id, DATE, at).failed());
        assertFalse(restored.seriesRefused(task.id));
        LocalDate tomorrow = DATE.plusDays(1);
        assertEquals(ACTIVE, restored.occurrence(task.id, tomorrow,
                task.start(tomorrow).toEpochMilli()).status);
    }

    @Test public void refusalOfWholeSeriesSurvivesReplayAndPreventsFutureTimeouts() throws Exception {
        TaskDefinition task = definition(true, true);
        TaskEngine engine = engine(task);
        engine.apply(action("refuse-series", task, REFUSE_SERIES, task.start(DATE).toEpochMilli()), false);
        TaskEngine restored = replay(engine);
        assertTrue(restored.seriesRefused(task.id));
        for (LocalDate date : Arrays.asList(DATE, DATE.plusDays(1), DATE.plusMonths(6))) {
            long deadline = task.end(date).toEpochMilli();
            assertEquals(REFUSED, restored.occurrence(task.id, date, deadline).status);
            restored.apply(action("sweep-" + date, task, TIMEOUT, date, deadline, ""), false);
            assertEquals(REFUSED, restored.occurrence(task.id, date, deadline).status);
            assertFalse(restored.occurrence(task.id, date, deadline).failed());
            TaskAction proof = action("proof-" + date, task, SUBMIT, date,
                    task.start(date).toEpochMilli(), "");
            assertThrows(IllegalStateException.class, () -> restored.apply(proof, false));
        }
        TaskEngine again = replay(restored);
        assertTrue(again.seriesRefused(task.id));
        assertEquals(REFUSED, again.occurrence(task.id, DATE.plusYears(1),
                task.start(DATE.plusYears(1)).toEpochMilli()).status);
    }

    @Test public void seriesRefusalPreservesEarlierPendingWorkForExplicitReview() throws Exception {
        TaskDefinition task = definition(true, true);
        TaskEngine engine = engine(task);
        engine.apply(action("pending", task, SUBMIT, task.start(DATE).toEpochMilli()), false);
        LocalDate tomorrow = DATE.plusDays(1);
        long at = task.start(tomorrow).toEpochMilli();
        engine.apply(action("refuse-series", task, REFUSE_SERIES, tomorrow, at, ""), false);
        TaskEngine restored = replay(engine);
        assertEquals(PENDING, restored.occurrence(task.id, DATE, at).status);
        restored.apply(action("review", task, ACCEPT, DATE, at, "pending"), true);
        assertEquals(ACCEPTED, restored.occurrence(task.id, DATE, at).status);
        assertEquals(REFUSED, restored.occurrence(task.id, tomorrow.plusDays(1), at).status);
    }

    @Test public void offlineSeriesRefusalAndDomReviewCommuteForExistingPendingRequests() throws Exception {
        for (TaskAction.Kind request : Arrays.asList(SUBMIT, OUTSIDE)) {
            for (TaskAction.Kind decision : Arrays.asList(ACCEPT, DENY)) {
                for (boolean refusalFirst : new boolean[] {false, true}) {
                    TaskDefinition task = definition(true, true);
                    TaskEngine engine = engine(task);
                    long start = task.start(DATE).toEpochMilli();
                    engine.apply(action("pending", task, request, start), false);
                    LocalDate tomorrow = DATE.plusDays(1);
                    long nextStart = task.start(tomorrow).toEpochMilli();
                    TaskAction refusal = action("refuse-series", task, REFUSE_SERIES,
                            tomorrow, nextStart, "");
                    TaskAction review = action("dom-review", task, decision, DATE, nextStart, "pending");
                    engine.apply(refusalFirst ? refusal : review, !refusalFirst);
                    engine = replay(engine);
                    if (refusalFirst) {
                        assertEquals(PENDING, engine.occurrence(task.id, DATE, nextStart).status);
                        assertEquals("pending", engine.occurrence(task.id, DATE, nextStart).submission.id);
                    }
                    engine.apply(refusalFirst ? review : refusal, refusalFirst);
                    TaskEngine restored = replay(engine);
                    assertEquals(decision == ACCEPT ? ACCEPTED : DENIED,
                            restored.occurrence(task.id, DATE, nextStart).status);
                    assertEquals("pending", restored.occurrence(task.id, DATE, nextStart).submission.id);
                    assertTrue(restored.seriesRefused(task.id));
                    assertEquals(REFUSED, restored.occurrence(task.id, tomorrow, nextStart).status);
                    assertEquals(REFUSED, restored.occurrence(task.id, tomorrow.plusDays(1), nextStart).status);
                }
            }
        }
    }

    @Test public void timeoutAfterAnyRecordedNonactiveStateIsADurableIdempotentNoop() throws Exception {
        for (TaskEngine.Status status : Arrays.asList(PENDING, ACCEPTED, DENIED, TIMED_OUT, REFUSED, SKIPPED)) {
            TaskDefinition task = definition(true, true);
            TaskEngine engine = engine(task);
            long start = task.start(DATE).toEpochMilli();
            long end = task.end(DATE).toEpochMilli();
            if (status == REFUSED) {
                engine.apply(action("refusal", task, REFUSE_ONCE, start), false);
            } else if (status == TIMED_OUT) {
                engine.apply(action("original-timeout", task, TIMEOUT, end), false);
            } else if (status == SKIPPED) {
                engine.apply(action("skip-disabled", task, SKIP, end), false);
            } else {
                engine.apply(action("pending", task, SUBMIT, start), false);
                if (status != PENDING) engine.apply(action("review", task,
                        status == ACCEPTED ? ACCEPT : DENY, DATE, start + 1, "pending"), true);
            }
            int before = engine.json().getJSONArray("actions").length();
            TaskAction timeout = action("late-timeout", task, TIMEOUT, end + 1);
            assertTrue(engine.apply(timeout, false));
            assertEquals(status, engine.occurrence(task.id, DATE, end + 1).status);
            TaskEngine restored = replay(engine);
            assertEquals(before + 1, restored.json().getJSONArray("actions").length());
            assertEquals(status, restored.occurrence(task.id, DATE, end + 1).status);
            assertFalse(restored.apply(TaskAction.parse(new JSONObject(timeout.json().toString())), false));
            assertEquals(before + 1, restored.json().getJSONArray("actions").length());
            TaskAction conflict = action("late-timeout", task, TIMEOUT, end + 2);
            assertThrows(SecurityException.class, () -> restored.apply(conflict, false));
            assertEquals(status, restored.occurrence(task.id, DATE, end + 2).status);
            if (status == PENDING || status == ACCEPTED || status == DENIED)
                assertEquals("pending", restored.occurrence(task.id, DATE, end + 2).submission.id);
        }
    }

    @Test public void skippedDisabledOccurrenceSurvivesReplayWithoutFailureOrLaterTimeoutPenalty() throws Exception {
        TaskDefinition task = definition(true, true);
        TaskEngine engine = engine(task);
        long end = task.end(DATE).toEpochMilli();
        TaskAction early = action("skip-early", task, SKIP, end - 1);
        assertThrows(IllegalStateException.class, () -> engine.apply(early, false));
        TaskAction skip = action("skip-disabled", task, SKIP, end);
        assertFalse(skip.domAction());
        assertThrows(SecurityException.class, () -> engine.apply(skip, true));
        assertTrue(engine.apply(TaskAction.parse(new JSONObject(skip.json().toString())), false));
        TaskEngine restored = replay(engine);
        assertEquals(SKIPPED, restored.occurrence(task.id, DATE, end).status);
        assertFalse(restored.occurrence(task.id, DATE, end).failed());
        assertFalse(restored.occurrence(task.id, DATE, end).task.failureTribute
                && restored.occurrence(task.id, DATE, end).failed());
        assertFalse(restored.apply(TaskAction.parse(new JSONObject(skip.json().toString())), false));
        assertTrue(restored.apply(action("later-timeout", task, TIMEOUT, end + 86_400_000), false));
        TaskEngine afterTimeout = replay(restored);
        assertEquals(SKIPPED, afterTimeout.occurrence(task.id, DATE, end + 86_400_000).status);
        assertFalse(afterTimeout.occurrence(task.id, DATE, end + 86_400_000).failed());
        LocalDate next = DATE.plusDays(1);
        assertEquals(ACTIVE, afterTimeout.occurrence(task.id, next, task.start(next).toEpochMilli()).status);
        assertFalse(afterTimeout.seriesRefused(task.id));
    }

    @Test public void lateOrPendingRefusalCannotReplaceAnExistingOutcome() throws Exception {
        TaskDefinition task = definition(true, true);
        TaskEngine engine = engine(task);
        long start = task.start(DATE).toEpochMilli();
        long end = task.end(DATE).toEpochMilli();
        TaskAction late = action("late", task, REFUSE_SERIES, end);
        assertThrows(IllegalStateException.class, () -> engine.apply(late, false));
        engine.apply(action("proof", task, SUBMIT, start), false);
        TaskAction pending = action("pending-refusal", task, REFUSE_ONCE, start + 1);
        assertThrows(IllegalStateException.class, () -> engine.apply(pending, false));
        assertEquals(PENDING, engine.occurrence(task.id, DATE, end).status);
        assertFalse(engine.seriesRefused(task.id));
    }

    @Test public void exactRetryAfterReplayIsIdempotentButReusedIdentityCannotChangePayload() throws Exception {
        TaskDefinition task = definition(true, true);
        TaskEngine engine = engine(task);
        long at = task.start(DATE).toEpochMilli();
        TaskAction proof = action("proof", task, SUBMIT, at);
        engine.apply(proof, false);
        TaskEngine restored = replay(engine);
        String before = RemoteProtocol.canonical(restored.json());
        assertFalse(restored.apply(TaskAction.parse(new JSONObject(proof.json().toString())), false));
        assertEquals(before, RemoteProtocol.canonical(restored.json()));
        for (String field : Arrays.asList("text", "at", "date", "kind")) {
            JSONObject changed = new JSONObject(proof.json().toString());
            if (field.equals("text")) changed.put(field, "Different proof");
            if (field.equals("at")) changed.put(field, at + 1);
            if (field.equals("date")) changed.put(field, DATE.plusDays(1).toString());
            if (field.equals("kind")) { changed.put(field, OUTSIDE.name()); changed.put("text", ""); }
            TaskAction conflict = TaskAction.parse(changed);
            assertThrows(field, SecurityException.class, () -> restored.apply(conflict, false));
            assertEquals(before, RemoteProtocol.canonical(restored.json()));
        }
        TaskAction duplicateTask = new TaskAction("another-create", task.id, CREATE, null,
                at, "", "", Collections.emptyList(), task);
        assertThrows(SecurityException.class, () -> restored.apply(duplicateTask, true));
        assertEquals(before, RemoteProtocol.canonical(restored.json()));
    }

    @Test public void onlyDeniedAndTimeoutAreFailuresAndTributeStillRequiresTheFlag() throws Exception {
        for (boolean tribute : new boolean[] {false, true}) {
            for (TaskEngine.Status expected : TaskEngine.Status.values()) {
                TaskDefinition task = definition(true, tribute);
                TaskEngine engine = engine(task);
                long start = task.start(DATE).toEpochMilli();
                long now = expected == SCHEDULED ? start - 1 : start;
                if (expected == PENDING || expected == ACCEPTED || expected == DENIED) {
                    engine.apply(action("proof", task, SUBMIT, start), false);
                    if (expected != PENDING) engine.apply(action("review", task,
                            expected == ACCEPTED ? ACCEPT : DENY, DATE, start + 1, "proof"), true);
                } else if (expected == REFUSED) {
                    engine.apply(action("refused", task, REFUSE_ONCE, start), false);
                } else if (expected == TIMED_OUT) {
                    engine.apply(action("timeout", task, TIMEOUT, task.end(DATE).toEpochMilli()), false);
                } else if (expected == SKIPPED) {
                    engine.apply(action("skip-disabled", task, SKIP, task.end(DATE).toEpochMilli()), false);
                } else if (expected == CANCELLED) {
                    engine.apply(action("cancelled", task, CANCEL_ONCE, start), true);
                }
                TaskEngine.Occurrence occurrence = replay(engine).occurrence(task.id, DATE, now);
                boolean failed = expected == DENIED || expected == TIMED_OUT;
                assertEquals(expected, occurrence.status);
                assertEquals(failed, occurrence.failed());
                assertEquals(tribute && failed, occurrence.task.failureTribute && occurrence.failed());
            }
        }
    }

    @Test public void replayRejectsUnknownTasksBadReviewReferencesAndConflictingRetries() throws Exception {
        TaskDefinition task = definition(true, true);
        long at = task.start(DATE).toEpochMilli();
        JSONObject create = create(task).json();
        JSONObject submit = action("proof", task, SUBMIT, at).json();
        JSONObject wrongReview = action("review", task, ACCEPT, DATE, at + 1, "other-proof").json();
        JSONObject conflict = new JSONObject(submit.toString()).put("text", "changed");
        for (JSONArray actions : Arrays.asList(new JSONArray().put(submit),
                new JSONArray().put(create).put(submit).put(wrongReview),
                new JSONArray().put(create).put(submit).put(conflict))) {
            assertThrows(RuntimeException.class, () -> TaskEngine.parse(new JSONObject().put("actions", actions)));
        }
        TaskEngine replayed = TaskEngine.parse(new JSONObject().put("actions",
                new JSONArray().put(create).put(submit).put(new JSONObject(submit.toString()))));
        assertEquals(2, replayed.json().getJSONArray("actions").length());
        assertEquals(PENDING, replayed.occurrence(task.id, DATE, at).status);
    }
    @Test public void activeCountUsesCurrentProjectionAndDistinctOccurrenceIdentity() throws Exception {
        TaskEngine engine = new TaskEngine();
        java.util.List<TaskEngine.Occurrence> rows = new java.util.ArrayList<>();
        long at = definition(true, false).start(DATE).toEpochMilli() + 1000;
        for (String name : java.util.List.of("active", "pending", "accepted", "denied", "refused", "failed", "skipped", "cancelled", "scheduled")) {
            TaskDefinition base = definition(true, false);
            TaskDefinition task = new TaskDefinition(name, name, "", "scheduled".equals(name)
                    ? DATE.plusDays(1).atTime(9, 0) : base.firstStart, base.zone, base.durationMillis, base.weekdays, true, false, null);
            TaskAction first = new TaskAction("create-" + name, task.id, CREATE, null,
                    task.start(task.firstStart.toLocalDate()).toEpochMilli() - 1000, "", "", java.util.List.of(), task);
            assertTrue(engine.apply(first, true)); assertFalse(engine.apply(first, true));
            if (java.util.Set.of("pending", "accepted", "denied").contains(name)) {
                TaskAction request = action(name + "-proof", task, OUTSIDE, at);
                assertTrue(engine.apply(request, false));
                if (!"pending".equals(name)) engine.apply(action(name + "-review", task,
                        "accepted".equals(name) ? ACCEPT : DENY, DATE, at + 1, request.id), true);
            } else if ("refused".equals(name)) engine.apply(action(name + "-refusal", task, REFUSE_ONCE, at), false);
            else if ("failed".equals(name) || "skipped".equals(name)) engine.apply(action(name + "-expiry", task,
                    "failed".equals(name) ? TIMEOUT : SKIP, task.end(DATE).toEpochMilli()), false);
            else if ("cancelled".equals(name)) engine.apply(action(name + "-cancel", task, CANCEL_ONCE, at), true);
            rows.add(engine.occurrence(task.id, task.firstStart.toLocalDate(), at));
        }
        rows.add(rows.get(0));
        assertEquals(1, TaskEngine.activeCount(rows));
        TaskEngine restored = replay(engine);
        rows.clear();
        for (TaskDefinition task : restored.definitions()) rows.add(restored.occurrence(task.id, task.firstStart.toLocalDate(), at));
        assertEquals(1, TaskEngine.activeCount(rows));
    }

    @Test public void occurrenceAndSeriesCancellationRetainAuditAndSurviveReplay() throws Exception {
        TaskDefinition task = definition(true, true); TaskEngine engine = engine(task);
        TaskAction one = action("cancel-one", task, CANCEL_ONCE, DATE, task.start(DATE).toEpochMilli() + 1, "");
        assertTrue(engine.apply(one, true)); assertFalse(engine.apply(one, true));
        assertEquals(CANCELLED, engine.occurrence(task.id, DATE, one.at).status);
        assertFalse(engine.occurrence(task.id, DATE, one.at).failed());
        LocalDate next = DATE.plusDays(1);
        assertEquals(ACTIVE, engine.occurrence(task.id, next, task.start(next).toEpochMilli()).status);
        assertTrue(engine.apply(action("cancel-series", task, CANCEL_SERIES, next, task.start(next).toEpochMilli(), ""), true));
        TaskEngine restored = replay(engine);
        assertTrue(restored.seriesCancelled(task.id));
        assertEquals(CANCELLED, restored.occurrence(task.id, next.plusDays(4), task.start(next.plusDays(4)).toEpochMilli()).status);
        assertEquals(3, restored.json().getJSONArray("actions").length());
    }

    @Test public void cancellationDominatesOfflineProofExpiryAndStaleReviewInEitherArrivalOrder() throws Exception {
        TaskDefinition task = definition(true, true); long at = task.start(DATE).toEpochMilli() + 1000;
        TaskAction proof = action("offline-proof", task, OUTSIDE, at);
        TaskAction cancel = action("dom-cancel", task, CANCEL_SERIES, at + 1);
        for (boolean proofFirst : new boolean[]{false, true}) {
            TaskEngine engine = engine(task);
            if (proofFirst) engine.apply(proof, false);
            engine.apply(cancel, true);
            if (!proofFirst) engine.apply(proof, false);
            engine.apply(action("offline-timeout", task, TIMEOUT, task.end(DATE).toEpochMilli()), false);
            engine.apply(action("stale-review", task, DENY, DATE, at + 2, proof.id), true);
            TaskEngine restored = replay(engine); TaskEngine.Occurrence row = restored.occurrence(task.id, DATE, at + 3);
            assertEquals(CANCELLED, row.status); assertNull(row.submission); assertFalse(row.failed());
            assertEquals(0, TaskEngine.activeCount(java.util.List.of(row)));
            assertEquals(5, restored.json().getJSONArray("actions").length());
        }
    }

    @Test public void cancellationRequiresDomAndConflictingRetryCannotRewriteIt() throws Exception {
        TaskDefinition task = definition(true, false); TaskEngine engine = engine(task);
        TaskAction cancel = action("cancel", task, CANCEL_ONCE, task.start(DATE).toEpochMilli() + 1);
        assertThrows(SecurityException.class, () -> engine.apply(cancel, false));
        assertTrue(engine.apply(cancel, true)); assertFalse(engine.apply(cancel, true));
        assertThrows(SecurityException.class, () -> engine.apply(action("cancel", task, CANCEL_ONCE, cancel.at + 1), true));
        assertEquals(CANCELLED, replay(engine).occurrence(task.id, DATE, cancel.at).status);
    }

}
