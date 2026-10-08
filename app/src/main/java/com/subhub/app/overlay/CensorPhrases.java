package com.subhub.app.overlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Phrase library compatible with the recovered category/custom-phrase preference model. */
public final class CensorPhrases {
    public static final Set<String> DEFAULT_ENABLED = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("short", "denial")));

    private static final Map<String, List<String>> CATEGORIES = categories();

    private CensorPhrases() {}

    public static List<String> build(Set<String> enabled, Set<String> custom) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Set<String> selected = enabled == null ? DEFAULT_ENABLED : enabled;
        for (String category : selected) {
            List<String> phrases = CATEGORIES.get(category);
            if (phrases != null) result.addAll(phrases);
        }
        if (custom != null) {
            for (String phrase : custom) {
                String normalized = normalize(phrase);
                if (!normalized.isEmpty()) result.add(normalized);
            }
        }
        if (result.isEmpty()) result.addAll(Arrays.asList("BLOCKED", "CENSORED", "DENIED"));
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    public static Set<String> categoryNames() {
        return Collections.unmodifiableSet(CATEGORIES.keySet());
    }

    private static Map<String, List<String>> categories() {
        Map<String, List<String>> value = new LinkedHashMap<>();
        value.put("short", Arrays.asList("BLOCKED", "CENSORED", "DENIED", "LOCKED"));
        value.put("denial", Arrays.asList(
                "NICE TRY", "STILL NO", "NO PEEKING", "DENIED, DARLING", "NOT TODAY",
                "DREAM ON", "EYES OFF", "LOOK ELSEWHERE", "WISHFUL THINKING", "RULES ARE RULES"));
        value.put("humiliation", Arrays.asList(
                "CUTE TRY, BETA", "BEHAVE, CUCK", "KNOW YOUR ROLE", "ASK NICELY",
                "EYES DOWN, BETA", "LESS BRAVADO", "GOOD LITTLE BETA", "STILL WAITING?",
                "BACK IN LINE", "KEEP DREAMING, CUCK"));
        value.put("edge", Arrays.asList(
                "NOT YET", "PATIENCE, TROUBLE", "WAIT FOR IT", "ENJOY THE WAIT",
                "SO CLOSE", "SLOW DOWN", "ANTICIPATION", "STILL WAITING",
                "HOLD THAT THOUGHT", "THE WAIT CONTINUES"));
        value.put("findom", Arrays.asList(
                "VIP VIEW", "LUXURY DENIED", "TRIBUTE THEATRE", "PREMIUM TEMPTATION",
                "EXCLUSIVE ACCESS", "VELVET ROPE", "NOT ON THE LIST", "PRIVATE COLLECTION",
                "LOOKS EXPENSIVE", "PRICELESS PATIENCE"));
        value.put("ntr", Arrays.asList(
                "NOT YOUR VIEW", "SIDE SEAT", "SPECTATOR ONLY", "WATCH THE RULES",
                "PRIVATE SHOW", "WRONG INVITATION", "OUTSIDE LOOKING IN", "JUST A SPECTATOR",
                "RESERVED ELSEWHERE", "GUEST LIST CLOSED"));
        value.put("gooner", Arrays.asList(
                "CAUGHT LOOKING", "THERE YOU GO AGAIN", "ONE MORE PEEK?", "EYES UP, TROUBLE",
                "NICE EXCUSE", "SCROLLING, HUH?", "THAT LOOK AGAIN", "OH, REALLY?",
                "CURIOUS LITTLE THING", "YOU KNOW THE RULE"));
        return value;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() > 80) trimmed = trimmed.substring(0, 80);
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
