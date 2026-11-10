package com.subhub.app.security;

import android.os.Bundle;
import android.view.View;
import com.subhub.app.R;
import com.subhub.app.util.PreferencePage;
import com.subhub.app.util.PrimaryHeader;

/** One setup destination selects local PIN control or a paired remote keyholder. */
public final class AuthenticatorActivity extends PreferencePage {
    private KeyholderSetupView setup;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.string.keyholder_home_title);
        PrimaryHeader.editLockButton(page).setVisibility(View.GONE);
        setup = new KeyholderSetupView(this);
        page.addView(setup, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }

    @Override protected void onResume() {
        super.onResume();
        if (setup != null) setup.refresh();
    }
}
