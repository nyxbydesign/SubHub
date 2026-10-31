package com.subhub.app.util;

import android.text.InputFilter;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.List;

/** Shared ceilings with explicit overrides for fields whose domain allows more text. */
public final class InputBounds {
    public static final int DEFAULT_TEXT = 4096;
    public static final int CREDENTIAL = 4096;
    public static final int PAIRING = 8192;
    public static final int SUBLIMINAL_PHRASES = 200 * 120 + 199;
    private InputBounds() { }

    public static void limit(EditText editor, int maximum) {
        if (maximum <= 0) throw new IllegalArgumentException("Invalid input limit");
        List<InputFilter> filters = withoutLength(editor);
        filters.add(new InputFilter.LengthFilter(maximum));
        editor.setFilters(filters.toArray(new InputFilter[0]));
    }

    /** Existing stored values must survive opening a form, especially opaque credentials. */
    public static void restore(EditText editor, String value, int maximum) {
        if (maximum <= 0) throw new IllegalArgumentException("Invalid input limit");
        editor.setFilters(withoutLength(editor).toArray(new InputFilter[0]));
        editor.setText(value);
        List<InputFilter> filters = withoutLength(editor);
        filters.add(value != null && value.length() > maximum
                ? new RetainedLengthFilter(maximum, value) : new InputFilter.LengthFilter(maximum));
        editor.setFilters(filters.toArray(new InputFilter[0]));
    }

    private static final class RetainedLengthFilter extends InputFilter.LengthFilter {
        private final String retained;
        RetainedLengthFilter(int maximum, String retained) { super(maximum); this.retained = retained; }
        @Override public CharSequence filter(CharSequence source, int start, int end,
                android.text.Spanned destination, int replaceStart, int replaceEnd) {
            // TextView restores saved state through its filters after the form has been created.
            if (replaceStart == 0 && replaceEnd == destination.length() && end - start == retained.length()
                    && android.text.TextUtils.regionMatches(source, start, retained, 0, retained.length())) return null;
            return super.filter(source, start, end, destination, replaceStart, replaceEnd);
        }
    }

    private static List<InputFilter> withoutLength(EditText editor) {
        List<InputFilter> result = new ArrayList<>();
        for (InputFilter filter : editor.getFilters()) {
            if (!(filter instanceof InputFilter.LengthFilter)) result.add(filter);
        }
        return result;
    }
}
