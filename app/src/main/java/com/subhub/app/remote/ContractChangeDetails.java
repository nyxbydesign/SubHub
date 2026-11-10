package com.subhub.app.remote;

import android.content.Context;
import com.subhub.app.R;
import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import org.json.*;
import java.util.*;

/** Human-readable differences between the actual applied snapshots, excluding export bookkeeping. */
final class ContractChangeDetails {
    private ContractChangeDetails() { }
    static List<String> lines(Context context, JSONObject before, JSONObject after) throws JSONException {
        JSONObject a = RemoteAppliedNotifications.normalized(before), b = RemoteAppliedNotifications.normalized(after);
        List<String> lines = new ArrayList<>();
        JSONObject ac = a.getJSONObject("contract"), bc = b.getJSONObject("contract");
        if (!ac.getString("id").equals(bc.getString("id")) || !ac.getString("origin").equals(bc.getString("origin")))
            lines.add(context.getString(R.string.notification_contract_replaced, clean(bc.getString("name"))));
        for (String key : new String[]{"name", "author", "description", "version"}) {
            int label = switch (key) {
                case "name" -> R.string.notification_contract_name;
                case "author" -> R.string.notification_contract_author;
                case "description" -> R.string.notification_contract_description;
                default -> R.string.notification_contract_version;
            };
            change(context, lines, context.getString(label), ac.get(key), bc.get(key));
        }
        for (String section : ConfigurationSchema.SECTIONS) {
            JSONObject av = ac.getJSONObject("sections").getJSONObject(section), bv = bc.getJSONObject("sections").getJSONObject(section);
            for (ConfigurationSettingCatalog.Field field : ConfigurationSettingCatalog.fields(section)) {
                Object old = av.get(field.key), next = bv.get(field.key);
                if (!same(old, next)) lines.add(context.getString(R.string.notification_change_line,
                        context.getString(field.label), value(context, field, old), value(context, field, next)));
            }
        }
        if (!same(ac.get("assets"), bc.get("assets"))) lines.add(context.getString(R.string.notification_contract_artwork));
        JSONObject aa = a.getJSONObject("apps"), ba = b.getJSONObject("apps");
        change(context, lines, context.getString(R.string.notification_all_apps), aa.get("all"), ba.get("all"));
        if (!same(aa.get("packages"), ba.get("packages"))) lines.add(context.getString(R.string.notification_change_line,
                context.getString(R.string.notification_apps), apps(context, aa.getJSONArray("packages")), apps(context, ba.getJSONArray("packages"))));
        JSONObject allowancesA = aa.getJSONObject("allowances"), allowancesB = ba.getJSONObject("allowances");
        Set<String> packages = new TreeSet<>(); allowancesA.keys().forEachRemaining(packages::add); allowancesB.keys().forEachRemaining(packages::add);
        for (String name : packages) if (!same(allowancesA.opt(name), allowancesB.opt(name)))
            lines.add(context.getString(R.string.notification_change_line,
                    context.getString(R.string.notification_app_allowance, app(context, name)), allowance(context, allowancesA.opt(name)), allowance(context, allowancesB.opt(name))));
        JSONObject as = a.getJSONObject("service"), bs = b.getJSONObject("service");
        for (String key : new String[]{"duration", "minimum", "maximum", "hidden"}) if (!same(as.get(key), bs.get(key))) {
            int label = switch (key) {
                case "duration" -> R.string.notification_session_duration;
                case "minimum" -> R.string.notification_duration_minimum;
                case "maximum" -> R.string.notification_duration_maximum;
                default -> R.string.notification_duration_hidden;
            };
            lines.add(context.getString(R.string.notification_change_line, context.getString(label),
                    key.equals("hidden") ? text(context, as.get(key)) : duration(context, as.getLong(key)),
                    key.equals("hidden") ? text(context, bs.get(key)) : duration(context, bs.getLong(key))));
        }
        return lines;
    }
    private static boolean same(Object a, Object b) throws JSONException { return RemoteProtocol.canonical(a).equals(RemoteProtocol.canonical(b)); }
    private static void change(Context c, List<String> lines, String label, Object a, Object b) throws JSONException {
        if (!same(a, b)) lines.add(c.getString(R.string.notification_change_line, label, text(c, a), text(c, b)));
    }
    private static String value(Context c, ConfigurationSettingCatalog.Field field, Object value) throws JSONException {
        if (field.kind == ConfigurationSettingCatalog.Kind.CHOICE) return c.getString(ConfigurationSettingCatalog.choiceLabel(field, String.valueOf(value)));
        if (field.kind == ConfigurationSettingCatalog.Kind.SELECTION) {
            List<String> labels = new ArrayList<>(); JSONArray values = (JSONArray) value;
            for (int i = 0; i < values.length(); i++) labels.add(c.getString(ConfigurationSettingCatalog.choiceLabel(field, values.getString(i))));
            return labels.isEmpty() ? c.getString(R.string.notification_value_empty) : clean(String.join(", ", labels));
        }
        if (field.kind == ConfigurationSettingCatalog.Kind.COLOR) return text(c, value);
        if (field.kind == ConfigurationSettingCatalog.Kind.MONEY) return c.getString(R.string.notification_cents, text(c, value));
        if (field.kind == ConfigurationSettingCatalog.Kind.RATIO) return c.getString(R.string.notification_percent,
                new java.math.BigDecimal(value.toString()).multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString());
        String result = value instanceof Number ? new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString() : text(c, value);
        if (value instanceof Number) {
            if (field.key.endsWith("_minutes")) return c.getString(R.string.notification_minutes, result);
            if (field.key.equals("popup_storm_display_duration") || field.key.equals("popup_storm_burst_frequency") || field.key.equals("popup_storm_burst_duration")) return c.getString(R.string.notification_seconds, result);
            if (field.key.equals("popup_storm_bouncing_speed_pps")) return c.getString(R.string.notification_pixels_per_second, result);
            if (field.key.endsWith("_ms")) return c.getString(R.string.notification_milliseconds, result);
            if (field.key.endsWith("_px")) return c.getString(R.string.notification_pixels, result);
            if (field.key.contains("percent")) return c.getString(R.string.notification_percent, result);
        }
        return result;
    }
    private static String allowance(Context c, Object value) { return value == null ? c.getString(R.string.notification_default_allowance) : c.getString(R.string.notification_minutes, text(c, value)); }
    private static String duration(Context c, long value) {
        if (value == -1) return c.getString(R.string.notification_duration_permanent);
        if (value == -2) return c.getString(R.string.notification_duration_random);
        return c.getString(R.string.notification_minutes, Long.toString(value / 60_000));
    }
    private static String apps(Context c, JSONArray values) throws JSONException {
        List<String> names = new ArrayList<>(); for (int i = 0; i < values.length(); i++) names.add(app(c, values.getString(i)));
        return names.isEmpty() ? c.getString(R.string.notification_value_empty) : clean(String.join(", ", names));
    }
    private static String app(Context c, String name) {
        try { return clean(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(name, 0)).toString()); }
        catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return name; }
    }
    private static String text(Context c, Object value) {
        if (value instanceof Boolean) return c.getString((Boolean) value ? R.string.notification_on : R.string.notification_off);
        String text = value == null || value == JSONObject.NULL ? "" : value.toString();
        return text.isEmpty() ? c.getString(R.string.notification_value_empty) : clean(text);
    }
    private static String clean(String value) { return com.subhub.app.notifications.NotificationText.clean(value); }
}
