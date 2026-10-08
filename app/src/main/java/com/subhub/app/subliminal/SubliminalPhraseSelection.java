package com.subhub.app.subliminal;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Uniformly selects among messages other than the last displayed text. */
final class SubliminalPhraseSelection {
    private SubliminalPhraseSelection() { }

    static int nextIndex(List<String> phrases, String previousText, Random random) {
        if (phrases == null || phrases.isEmpty()) return -1;
        int eligible = 0;
        for (String phrase : phrases) {
            if (!Objects.equals(phrase, previousText)) eligible++;
        }
        // A single distinct message can repeat; never stall an otherwise valid custom-only pool.
        if (eligible == 0) return random.nextInt(phrases.size());
        int selected = random.nextInt(eligible);
        for (int index = 0; index < phrases.size(); index++) {
            if (!Objects.equals(phrases.get(index), previousText) && selected-- == 0) return index;
        }
        throw new IllegalStateException("Phrase pool changed during selection");
    }
}
