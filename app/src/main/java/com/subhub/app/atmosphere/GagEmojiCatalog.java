package com.subhub.app.atmosphere;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The pinned Unicode Emoji keyboard catalog in CLDR order. */
final class GagEmojiCatalog {
    // Android's asset merger unwraps the source .gz when packaging the APK.
    static final String ASSET = "gag_emoji/catalog.tsv";

    record Entry(String sequence, String name) {}
    record Group(String name, List<Entry> entries) {}

    final List<Group> groups;
    private final Map<String, Entry> entries;

    private GagEmojiCatalog(List<Group> groups, Map<String, Entry> entries) {
        this.groups = List.copyOf(groups);
        this.entries = Map.copyOf(entries);
    }

    Entry entry(String sequence) {
        return entries.get(sequence);
    }

    static GagEmojiCatalog load(Context context) throws IOException {
        try (InputStream source = context.getAssets().open(ASSET);
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     source, StandardCharsets.UTF_8))) {
            List<Group> groups = new ArrayList<>();
            Map<String, Entry> entries = new LinkedHashMap<>();
            String group = null;
            List<Entry> rows = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null;) {
                int tab = line.indexOf('\t');
                if (tab == -1 && line.startsWith("#")) {
                    if (group != null) groups.add(new Group(group, List.copyOf(rows)));
                    group = line.substring(1);
                    rows.clear();
                    continue;
                }
                if (group == null || tab < 1 || tab == line.length() - 1)
                    throw new IOException("Invalid emoji catalog row");
                Entry entry = new Entry(line.substring(0, tab), line.substring(tab + 1));
                if (entries.putIfAbsent(entry.sequence(), entry) != null)
                    throw new IOException("Duplicate emoji catalog entry");
                rows.add(entry);
            }
            if (group != null) groups.add(new Group(group, List.copyOf(rows)));
            if (groups.isEmpty() || entries.isEmpty()) throw new IOException("Empty emoji catalog");
            return new GagEmojiCatalog(groups, entries);
        }
    }
}
