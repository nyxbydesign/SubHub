package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.R;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only section details from validated contract values. */
public final class ContractFeatureDetails {
    private ContractFeatureDetails() { }

    public static PersonSnapshot.Section section(Context context, String key, JSONObject fields) throws JSONException {
        int title = switch (key) {
            case "censor" -> R.string.sub_censor_title;
            case "limits" -> R.string.sub_limits_title;
            case "wallet" -> R.string.sub_wallet_title;
            default -> throw new IllegalArgumentException("Unsupported contract section");
        };
        List<String> lines = new ArrayList<>();
        for (ConfigurationSettingCatalog.Field field : ConfigurationSettingCatalog.fields(key)) {
            lines.add(context.getString(field.label) + ": " + display(context, field, fields.get(field.key)));
        }
        return new PersonSnapshot.Section(key, context.getString(title), "", String.join("\n", lines));
    }

    static PersonSnapshot.Section section(Context context, String key, Map<String, Object> fields) {
        try {
            return section(context, key, new JSONObject(fields));
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Incomplete internal contract section", invalid);
        }
    }

    private static String display(Context context, ConfigurationSettingCatalog.Field field, Object value) {
        if (value instanceof Boolean enabled)
            return context.getString(enabled ? R.string.role_enabled : R.string.role_disabled);
        if (value instanceof JSONArray array) {
            List<String> items = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) items.add(choice(context, field, array.optString(index)));
            return String.join(", ", items);
        }
        if (field.kind == ConfigurationSettingCatalog.Kind.CHOICE) return choice(context, field, String.valueOf(value));
        return field.displayText(value);
    }

    private static String choice(Context context, ConfigurationSettingCatalog.Field field, String value) {
        return field.choices.contains(value) ? context.getString(ConfigurationSettingCatalog.choiceLabel(field, value)) : value;
    }
}
