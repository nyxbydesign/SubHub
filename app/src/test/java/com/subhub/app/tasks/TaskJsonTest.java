package com.subhub.app.tasks;

import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.remote.RemoteProtocol;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class TaskJsonTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);

    private static String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }

    private static Set<String> keys(JSONObject value) {
        Set<String> result = new HashSet<>();
        Iterator<?> iterator = value.keys();
        while (iterator.hasNext()) result.add((String) iterator.next());
        return result;
    }

    private static TaskAttachment attachment(char value) {
        return new TaskAttachment(hash(value), "image/png", 100);
    }

    private static TaskDefinition definition(TaskAttachment attachment) {
        return new TaskDefinition("task-a", "Task", "Instructions", DATE.atTime(9, 0),
                ZoneId.of("Europe/Berlin"), 60_000, EnumSet.of(DayOfWeek.MONDAY),
                true, false, attachment);
    }

    private static TaskAction submit(String text, List<TaskAttachment> attachments) {
        return new TaskAction("proof-a", "task-a", TaskAction.Kind.SUBMIT, DATE,
                1_792_393_200_000L, text, "", attachments, null);
    }

    @Test public void supportedMediaTypesRoundTripAtSizeBoundaries() throws Exception {
        for (String mime : Arrays.asList("image/jpeg", "image/png", "image/webp", "image/gif",
                "audio/mpeg", "audio/ogg", "audio/wav", "audio/mp4", "video/mp4", "video/webm")) {
            for (long length : new long[] {1, RemoteAssets.MAX_ASSET_BYTES}) {
                TaskAttachment original = new TaskAttachment(hash('a'), mime, length);
                TaskAttachment parsed = TaskAttachment.parse(new JSONObject(original.json().toString()));
                assertEquals(original.hash, parsed.hash);
                assertEquals(mime, parsed.mime);
                assertEquals(length, parsed.length);
                assertEquals(new HashSet<>(Arrays.asList("hash", "mime", "length")), keys(original.json()));
            }
        }
    }

    @Test public void unsupportedMimeSizesAndUriOrMalformedHashesAreRejected() {
        for (String mime : Arrays.asList("text/plain", "application/pdf", "image/svg+xml",
                "application/octet-stream", "image/*", "IMAGE/PNG", "image/png; charset=utf-8", "")) {
            assertThrows(mime, IllegalArgumentException.class, () -> new TaskAttachment(hash('a'), mime, 1));
        }
        for (long length : new long[] {-1, 0, RemoteAssets.MAX_ASSET_BYTES + 1, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new TaskAttachment(hash('a'), "image/png", length));
        }
        for (String invalid : Arrays.asList("content://media/external/1", "file:///sdcard/proof.jpg",
                "https://example.invalid/proof.jpg", hash('A'), hash('g'), "abc", "")) {
            assertThrows(invalid, IllegalArgumentException.class, () -> new TaskAttachment(invalid, "image/png", 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new TaskAttachment(null, "image/png", 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskAttachment(hash('a'), null, 1));
    }

    @Test public void createAndCompletionAcceptImmutableEmptyAndSingletonLists() throws Exception {
        TaskDefinition definition = definition(null);
        TaskAction create = new TaskAction("create", definition.id, TaskAction.Kind.CREATE, null,
                definition.start(DATE).toEpochMilli() - 1, "", "", List.of(), definition);
        assertTrue(create.attachments.isEmpty());
        TaskEngine engine = new TaskEngine();
        assertTrue(engine.apply(create, true));
        TaskAction text = submit("Done", List.of());
        assertTrue(text.attachments.isEmpty());
        assertEquals("Done", TaskAction.parse(new JSONObject(text.json().toString())).text);
        TaskAttachment photo = attachment('a');
        TaskAction media = submit("", List.of(photo));
        assertEquals(1, media.attachments.size());
        assertEquals(photo.hash, media.attachments.get(0).hash);
    }

    @Test public void assignmentHasOneOptionalAttachmentAndProofHasAtMostFive() throws Exception {
        TaskDefinition assigned = definition(attachment('a'));
        JSONObject serialized = new JSONObject(assigned.json().toString());
        assertTrue(serialized.get("attachment") instanceof JSONObject);
        assertEquals(hash('a'), TaskDefinition.parse(serialized).attachment.hash);
        assertNull(TaskDefinition.parse(new JSONObject(definition(null).json().toString())).attachment);
        JSONObject invalidAssignment = new JSONObject(serialized.toString()).put("attachment",
                new JSONArray().put(attachment('a').json()).put(attachment('b').json()));
        assertThrows(JSONException.class, () -> TaskDefinition.parse(invalidAssignment));

        List<TaskAttachment> proofs = new ArrayList<>();
        for (char value = 'a'; value <= 'e'; value++) proofs.add(attachment(value));
        TaskAction proof = submit("", proofs);
        assertEquals(5, TaskAction.parse(new JSONObject(proof.json().toString())).attachments.size());
        proofs.add(attachment('f'));
        assertThrows(IllegalArgumentException.class, () -> submit("", proofs));
        JSONObject sixProofs = proof.json();
        sixProofs.getJSONArray("attachments").put(attachment('f').json());
        assertThrows(JSONException.class, () -> TaskAction.parse(sixProofs));
    }

    @Test public void textOnlyCompletionIsValidButEmptyAndDuplicateProofsAreRejected() throws Exception {
        TaskAction textOnly = submit("Finished without a photo", Collections.emptyList());
        TaskAction parsed = TaskAction.parse(new JSONObject(textOnly.json().toString()));
        assertEquals(textOnly.text, parsed.text);
        assertTrue(parsed.attachments.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> submit("", Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> submit(" \n\t ", Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> submit("Proof",
                Arrays.asList(attachment('a'), attachment('a'))));
        assertThrows(IllegalArgumentException.class, () -> submit("Proof", Collections.singletonList(null)));
        List<TaskAttachment> input = new ArrayList<>(Collections.singletonList(attachment('a')));
        TaskAction copied = submit("", input);
        input.clear();
        assertEquals(1, copied.attachments.size());
        assertThrows(UnsupportedOperationException.class, () -> copied.attachments.clear());
    }

    @Test public void wireRepresentationContainsContentMetadataAndNoDeviceUri() throws Exception {
        TaskAction proof = submit("", Collections.singletonList(attachment('a')));
        JSONObject json = new JSONObject(proof.json().toString());
        assertEquals(new HashSet<>(Arrays.asList("id", "task", "kind", "date", "at", "text",
                "submission", "attachments", "definition")), keys(json));
        assertEquals(new HashSet<>(Arrays.asList("hash", "mime", "length")),
                keys(json.getJSONArray("attachments").getJSONObject(0)));
        assertFalse(json.toString().contains("content://"));
        assertFalse(json.toString().contains("file://"));
        assertFalse(json.toString().contains("uri"));
    }

    @Test public void modelParsersRejectUnknownUriAndPathFields() throws Exception {
        for (String field : Arrays.asList("uri", "path")) {
            String location = field.equals("uri") ? "content://media/external/1" : "/sdcard/proof.jpg";
            JSONObject media = attachment('a').json().put(field, location);
            JSONObject task = definition(attachment('a')).json().put(field, location);
            JSONObject action = submit("Done", Collections.emptyList()).json().put(field, location);
            assertThrows(field, JSONException.class, () -> TaskAttachment.parse(media));
            assertThrows(field, JSONException.class, () -> TaskDefinition.parse(task));
            assertThrows(field, JSONException.class, () -> TaskAction.parse(action));
        }
    }

    @Test public void textAndBooleanFieldsRejectJsonCoercionMissingAndNullValues() throws Exception {
        for (Object invalid : Arrays.asList(12, 12L, true, JSONObject.NULL,
                new JSONObject(), new JSONArray())) {
            JSONObject value = new JSONObject().put("field", invalid);
            assertThrows(JSONException.class, () -> TaskJson.text(value, "field", 20));
        }
        for (Object invalid : Arrays.asList("true", "false", 0, 1, JSONObject.NULL,
                new JSONObject(), new JSONArray())) {
            JSONObject value = new JSONObject().put("field", invalid);
            assertThrows(JSONException.class, () -> TaskJson.bool(value, "field"));
        }
        assertThrows(JSONException.class, () -> TaskJson.text(new JSONObject(), "missing", 20));
        assertThrows(JSONException.class, () -> TaskJson.bool(new JSONObject(), "missing"));
        assertThrows(JSONException.class, () -> TaskJson.text(new JSONObject().put("field", "abcd"), "field", 3));
        assertEquals("abc", TaskJson.text(new JSONObject().put("field", "abc"), "field", 3));
        assertFalse(TaskJson.bool(new JSONObject().put("field", false), "field"));
        assertTrue(TaskJson.bool(new JSONObject().put("field", true), "field"));
    }

    @Test public void definitionParserRequiresExactScalarTypes() throws Exception {
        JSONObject original = definition(null).json();
        for (String field : Arrays.asList("id", "title", "text", "start", "zone")) {
            JSONObject value = new JSONObject(original.toString()).put(field, 123);
            assertThrows(field, RuntimeException.class, () -> TaskDefinition.parse(value));
        }
        for (String field : Arrays.asList("allowRefusal", "failureTribute")) {
            JSONObject value = new JSONObject(original.toString()).put(field, "true");
            assertThrows(field, JSONException.class, () -> TaskDefinition.parse(value));
        }
        for (Object invalid : Arrays.asList("60000", 60000.0, new BigDecimal("60000"),
                true, JSONObject.NULL)) {
            JSONObject value = new JSONObject(original.toString()).put("duration", invalid);
            assertThrows(JSONException.class, () -> TaskDefinition.parse(value));
        }
        assertEquals(60_000, TaskDefinition.parse(new JSONObject(original.toString()).put("duration", 60_000)).durationMillis);
        assertEquals(60_000, TaskDefinition.parse(new JSONObject(original.toString()).put("duration", 60_000L)).durationMillis);
    }

    @Test public void actionAndAttachmentParsersRequireExactScalarTypes() throws Exception {
        JSONObject original = submit("Done", Collections.emptyList()).json();
        for (String field : Arrays.asList("id", "task", "kind", "date", "text", "submission")) {
            JSONObject value = new JSONObject(original.toString()).put(field, 123);
            assertThrows(field, RuntimeException.class, () -> TaskAction.parse(value));
        }
        for (Object invalid : Arrays.asList("1792393200000", 1.0, true, JSONObject.NULL)) {
            JSONObject value = new JSONObject(original.toString()).put("at", invalid);
            assertThrows(JSONException.class, () -> TaskAction.parse(value));
        }
        for (String field : Arrays.asList("hash", "mime")) {
            JSONObject value = attachment('a').json().put(field, 123);
            assertThrows(JSONException.class, () -> TaskAttachment.parse(value));
        }
        for (Object invalid : Arrays.asList("100", 100.0, true, JSONObject.NULL)) {
            JSONObject value = attachment('a').json().put("length", invalid);
            assertThrows(JSONException.class, () -> TaskAttachment.parse(value));
        }
    }

    @Test public void decodedWireScalarsRemainStrictWithoutStringOrDecimalCoercion() throws Exception {
        for (String literal : Arrays.asList("\"60000\"", "60000.0", "6e4", "true", "null")) {
            Object scalar = new JSONObject("{\"value\":" + literal + "}").get("value");
            JSONObject definition = definition(null).json().put("duration", scalar);
            assertThrows(literal, JSONException.class, () -> TaskDefinition.parse(definition));
            JSONObject attachment = attachment('a').json().put("length", scalar);
            assertThrows(literal, JSONException.class, () -> TaskAttachment.parse(attachment));
            JSONObject action = submit("Done", Collections.emptyList()).json().put("at", scalar);
            assertThrows(literal, JSONException.class, () -> TaskAction.parse(action));
        }
        JSONObject raw = new JSONObject("{\"text\":true,\"boolean\":\"false\"}");
        assertThrows(JSONException.class, () -> TaskJson.text(raw, "text", 20));
        assertThrows(JSONException.class, () -> TaskJson.bool(raw, "boolean"));
    }

    @Test public void replayAndModelParsersRejectMalformedContainerShapesAndMissingRequiredFields() throws Exception {
        for (String invalid : Arrays.asList("{}", "{\"actions\":null}", "{\"actions\":{}}",
                "{\"actions\":[null]}", "{\"actions\":[1]}")) {
            assertThrows(JSONException.class, () -> TaskEngine.parse(new JSONObject(invalid)));
        }
        for (String field : Arrays.asList("id", "title", "text", "start", "zone", "duration",
                "weekdays", "allowRefusal", "failureTribute")) {
            JSONObject value = definition(null).json();
            value.remove(field);
            assertThrows(field, RuntimeException.class, () -> TaskDefinition.parse(value));
        }
        for (String field : Arrays.asList("id", "task", "kind", "at", "text", "submission", "attachments")) {
            JSONObject value = submit("Done", Collections.emptyList()).json();
            value.remove(field);
            assertThrows(field, RuntimeException.class, () -> TaskAction.parse(value));
        }
        JSONObject wrongDays = definition(null).json().put("weekdays", new JSONObject());
        assertThrows(JSONException.class, () -> TaskDefinition.parse(wrongDays));
        JSONObject wrongProofs = submit("Done", Collections.emptyList()).json()
                .put("attachments", new JSONArray().put("content://media/1"));
        assertThrows(JSONException.class, () -> TaskAction.parse(wrongProofs));
    }

    @Test public void everyActionKindRoundTripsWithItsRequiredPayloadShape() throws Exception {
        TaskDefinition definition = definition(attachment('a'));
        for (TaskAction.Kind kind : TaskAction.Kind.values()) {
            boolean create = kind == TaskAction.Kind.CREATE;
            boolean review = kind == TaskAction.Kind.ACCEPT || kind == TaskAction.Kind.DENY;
            TaskAction original = new TaskAction("action-" + kind, definition.id, kind,
                    create ? null : DATE, definition.start(DATE).toEpochMilli(),
                    kind == TaskAction.Kind.SUBMIT ? "Done" : "", review ? "proof-a" : "",
                    Collections.emptyList(), create ? definition : null);
            TaskAction parsed = TaskAction.parse(new JSONObject(original.json().toString()));
            assertEquals(RemoteProtocol.canonical(original.json()), RemoteProtocol.canonical(parsed.json()));
            assertEquals(original.domAction(), parsed.domAction());
            JSONObject wrongShape = new JSONObject(original.json().toString());
            if (create) wrongShape.put("date", DATE.toString());
            else wrongShape.put("definition", definition.json());
            assertThrows(IllegalArgumentException.class, () -> TaskAction.parse(wrongShape));
        }
    }

    @Test public void reviewNeedsSubmissionIdentityAndNonProofActionsCannotCarryProof() {
        for (TaskAction.Kind kind : Arrays.asList(TaskAction.Kind.ACCEPT, TaskAction.Kind.DENY)) {
            assertThrows(IllegalArgumentException.class, () -> new TaskAction("review", "task-a",
                    kind, DATE, 1, "", "", Collections.emptyList(), null));
        }
        for (TaskAction.Kind kind : Arrays.asList(TaskAction.Kind.OUTSIDE, TaskAction.Kind.TIMEOUT,
                TaskAction.Kind.SKIP, TaskAction.Kind.REFUSE_ONCE, TaskAction.Kind.REFUSE_SERIES)) {
            assertThrows(IllegalArgumentException.class, () -> new TaskAction("action", "task-a",
                    kind, DATE, 1, "Unexpected text", "", Collections.emptyList(), null));
            assertThrows(IllegalArgumentException.class, () -> new TaskAction("action", "task-a",
                    kind, DATE, 1, "", "", Collections.singletonList(attachment('a')), null));
            assertThrows(IllegalArgumentException.class, () -> new TaskAction("action", "task-a",
                    kind, DATE, 1, "", "proof-a", Collections.emptyList(), null));
        }
    }
}
