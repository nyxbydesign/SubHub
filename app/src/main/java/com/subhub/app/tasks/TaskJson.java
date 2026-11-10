package com.subhub.app.tasks;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Set;

final class TaskJson {
    private TaskJson() { }
    static void keys(JSONObject value, Set<String> expected) throws JSONException {
        if (value.length() != expected.size()) throw new JSONException("Invalid task fields");
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!expected.contains(keys.next())) throw new JSONException("Unknown task field");
    }
    static String text(JSONObject value, String key, int max) throws JSONException {
        Object raw = value.get(key);
        if (!(raw instanceof String) || ((String) raw).length() > max)
            throw new JSONException("Invalid task field: " + key);
        return (String) raw;
    }
    static boolean bool(JSONObject value, String key) throws JSONException {
        Object raw = value.get(key);
        if (!(raw instanceof Boolean)) throw new JSONException("Invalid task field: " + key);
        return (Boolean) raw;
    }
}
