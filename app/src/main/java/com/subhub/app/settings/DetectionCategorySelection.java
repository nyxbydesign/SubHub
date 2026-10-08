package com.subhub.app.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** User-facing category grouping; detector and pack identifiers stay unchanged. */
public final class DetectionCategorySelection {
    private DetectionCategorySelection() { }

    public static List<String> choicesForDisplay(List<String> categories) {
        Set<String> choices = new LinkedHashSet<>();
        for (String category : categories) choices.add(representative(category));
        return new ArrayList<>(choices);
    }

    public static boolean isSelected(Set<String> categories, String category) {
        String visible = representative(category);
        String paired = paired(visible);
        return categories.contains(visible) || (paired != null && categories.contains(paired));
    }

    public static void setSelected(Set<String> categories, String category, boolean selected) {
        String visible = representative(category);
        String paired = paired(visible);
        if (selected) {
            categories.add(visible);
            if (paired != null) categories.add(paired);
        } else {
            categories.remove(visible);
            if (paired != null) categories.remove(paired);
        }
    }

    private static String representative(String category) {
        if ("anus".equals(category)) return "buttocks";
        if ("anus_covered".equals(category)) return "buttocks_covered";
        return category;
    }

    private static String paired(String category) {
        if ("buttocks".equals(category)) return "anus";
        if ("buttocks_covered".equals(category)) return "anus_covered";
        return null;
    }
}
