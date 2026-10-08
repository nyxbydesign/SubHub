package com.subhub.app.update;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps user-facing changes separate from release download instructions. */
final class ReleaseNotesFormatter {
    private static final int DISPLAY_LIMIT = 4_000;
    static final String BULLET = "\u2022\u00a0\u00a0";
    private static final Pattern STRONG_TITLE = Pattern.compile("^\\*\\*([^*]+)\\*\\*");

    static final class DisplayLine {
        final String text;
        final boolean heading;
        final boolean bullet;
        final int boldTitleLength;

        DisplayLine(String text, boolean heading, boolean bullet, int boldTitleLength) {
            this.text = text;
            this.heading = heading;
            this.bullet = bullet;
            this.boldTitleLength = boldTitleLength;
        }

        String displayText() { return (bullet ? BULLET : "") + text; }
    }

    private ReleaseNotesFormatter() { }

    static String changelogOnly(String releaseBody) {
        if (releaseBody == null || releaseBody.trim().isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        boolean reading = false;
        for (String line : releaseBody.replace("\r", "").split("\n", -1)) {
            String heading = heading(line);
            if (!reading) {
                if (heading.equals("what's new") || heading.equals("what’s new")
                        || heading.equals("whats new")
                        || heading.startsWith("what's new in subhub ")
                        || heading.startsWith("what’s new in subhub ")
                        || heading.startsWith("whats new in subhub ")
                        || heading.equals("changes") || heading.equals("release notes")) {
                    reading = true;
                }
                continue;
            }
            if (heading.equals("choose your apk") || heading.equals("download options")
                    || heading.equals("downloads") || heading.equals("installation")) {
                break;
            }
            if (line.toLowerCase(Locale.ROOT).contains("full changelog")) continue;
            result.append(line).append('\n');
        }
        return result.toString().trim();
    }

    static String forDisplay(String notes, String fallback) {
        StringBuilder clean = new StringBuilder();
        for (DisplayLine line : displayLines(notes, fallback)) {
            if (clean.length() > 0) clean.append('\n');
            clean.append(line.displayText());
        }
        String text = clean.toString().trim();
        return text.length() > DISPLAY_LIMIT ? text.substring(0, DISPLAY_LIMIT) + "…" : text;
    }

    /** Native hanging indents keep wrapped bullets aligned, including at larger font scales. */
    static CharSequence forView(TextView view, String notes, String fallback) {
        SpannableString text = new SpannableString(forDisplay(notes, fallback));
        int indent = (int) Math.ceil(view.getPaint().measureText(BULLET));
        int start = 0;
        for (DisplayLine line : displayLines(notes, fallback)) {
            if (start >= text.length()) break;
            int end = Math.min(text.length(), start + line.displayText().length());
            if (end > start) {
                if (line.bullet) text.setSpan(new LeadingMarginSpan.Standard(0, indent),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int boldStart = start + (line.bullet ? BULLET.length() : 0);
                int boldEnd = line.heading ? end : Math.min(end, boldStart + line.boldTitleLength);
                if (boldEnd > boldStart) text.setSpan(new StyleSpan(Typeface.BOLD),
                        boldStart, boldEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start += line.displayText().length() + 1;
        }
        return text;
    }

    static List<DisplayLine> displayLines(String notes, String fallback) {
        String source = notes == null ? "" : notes.trim();
        source = source.replace("\r", "")
                .replaceFirst("(?i)^#{1,6}\\s*what(?:'|’)?s new(?: in subhub [^\\n]+)?\\n+", "").trim();
        List<DisplayLine> result = new ArrayList<>();
        for (String raw : source.split("\n", -1)) {
            String line = raw.trim();
            boolean heading = line.startsWith("#");
            boolean bullet = line.matches("^[-*+]\\s+.+");
            line = heading ? line.replaceFirst("^#{1,6}\\s*", "")
                    : bullet ? line.replaceFirst("^[-*+]\\s+", "") : line;
            Matcher title = STRONG_TITLE.matcher(line);
            int boldLength = title.find() ? cleanInline(title.group(1)).length() : 0;
            result.add(new DisplayLine(cleanInline(line), heading, bullet, boldLength));
        }
        while (result.size() > 1 && result.get(0).text.isEmpty()) result.remove(0);
        while (result.size() > 1 && result.get(result.size() - 1).text.isEmpty()) result.remove(result.size() - 1);
        if (result.get(0).text.isEmpty()) {
            result.set(0, new DisplayLine(fallback == null ? "" : fallback, false, false, 0));
        }
        return result;
    }

    private static String cleanInline(String text) {
        return text.replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")
                .replace("**", "").replace("`", "").trim();
    }

    private static String heading(String line) {
        String value = line.trim();
        if (!value.startsWith("#")) return "";
        return value.replaceFirst("^#{1,6}\\s*", "").trim().toLowerCase(Locale.ROOT);
    }
}
