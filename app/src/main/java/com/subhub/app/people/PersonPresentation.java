package com.subhub.app.people;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Visible contract changes only; transport confirmations and usage counters are not content. */
final class PersonPresentation {
    private PersonPresentation() { }
    static List<Object> contract(PersonSnapshot person) {
        List<Object> result = new ArrayList<>(Arrays.asList(person.name, person.capabilities,
                person.featureConsent, person.consentHardcoreOverride, person.sync.runtime,
                person.sync.contract, person.sync.hasPendingConfiguration()));
        for (PersonSnapshot.Section section : person.sections) {
            result.addAll(Arrays.asList(section.key, section.title, section.summary, section.details));
            for (PersonSnapshot.App app : section.apps)
                result.addAll(Arrays.asList(app.packageName, app.label, app.appIcon));
        }
        return result;
    }
}
