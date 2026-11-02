package com.subhub.app.remote;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Receipt of an already durable terminal result; never an executable command. */
final class RemoteSessionControlAck {
    final String id, requestDigest, resultDigest;
    final long sequence;
    RemoteSessionControlAck(String id, long sequence, String requestDigest, String resultDigest) {
        this.id = RemoteProtocol.identifier(id);
        if (sequence <= 0) throw new IllegalArgumentException("Invalid acknowledgment sequence");
        RemoteSessionCommands.digest(requestDigest); RemoteSessionCommands.digest(resultDigest);
        this.sequence = sequence; this.requestDigest = requestDigest; this.resultDigest = resultDigest;
    }
    static RemoteSessionControlAck of(RemoteSessionRuntime.Record record) throws JSONException {
        if (!record.result.state.terminal()) throw new IllegalArgumentException("Terminal receipt required");
        return new RemoteSessionControlAck(record.request.id, record.request.sequence, record.request.digest(), digest(record.result));
    }
    static String digest(RemoteSessionCommands.Result result) throws JSONException {
        return RemoteProtocol.sha256(RemoteProtocol.canonical(result.json()).getBytes(StandardCharsets.UTF_8));
    }
    JSONObject json() throws JSONException {
        return new JSONObject().put("id", id).put("sequence", sequence).put("requestDigest", requestDigest).put("resultDigest", resultDigest);
    }
    static RemoteSessionControlAck parse(JSONObject value) throws JSONException {
        RemoteSessionCommands.keys(value, "id", "sequence", "requestDigest", "resultDigest");
        return new RemoteSessionControlAck(RemoteSessionCommands.text(value, "id"), RemoteSessionCommands.integer(value, "sequence"),
                RemoteSessionCommands.text(value, "requestDigest"), RemoteSessionCommands.text(value, "resultDigest"));
    }
}
