package com.subhub.app.people;

import android.widget.LinearLayout;
import com.subhub.app.util.AsyncUiScope;

/** Current profile configuration identity. */
public final class ContractHeader {
    private ContractHeader() { }
    public static void add(LinearLayout parent, PersonSnapshot person, String label, AsyncUiScope data) {
        PersonUi.text(parent, label, false);
        PersonUi.text(parent, person.contractName, true);
    }
}
