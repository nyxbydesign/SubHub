package com.subhub.app.people;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Stable presentation order; connection and session updates never move a person. */
public final class PersonDisplayOrder {
    private PersonDisplayOrder() {}

    public static List<PersonSnapshot> sorted(List<PersonSnapshot> people, Locale locale) {
        List<PersonSnapshot> result = new ArrayList<>(people);
        Collator names = Collator.getInstance(locale);
        names.setStrength(Collator.SECONDARY);
        result.sort((left, right) -> {
            if (left.id.isLocal() != right.id.isLocal()) return left.id.isLocal() ? -1 : 1;
            int name = names.compare(left.name.trim(), right.name.trim());
            return name != 0 ? name : left.id.value().compareTo(right.id.value());
        });
        return result;
    }
}
