package com.subhub.app.people;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

/** Role-aware primary page; asynchronous requests belong to the page lifecycle. */
public abstract class RolePage extends AppCompatActivity {
    protected LinearLayout body;
    protected AsyncUiScope data;
    private int pageTitle;
    private SubHubNavigation.Screen screen;
    private boolean domOnly;

    protected final void page(int title, SubHubNavigation.Screen destination, boolean requiresDom) {
        pageTitle = title; screen = destination; domOnly = requiresDom;
        setContentView(R.layout.activity_role_page);
        body = findViewById(R.id.role_page_body);
        data = AsyncUiScope.forPage(this);
        PrimaryHeader.bind(findViewById(android.R.id.content), 0, title, 0);
    }

    @Override protected void onResume() {
        super.onResume();
        if (body == null) return;
        if (domOnly && !ControllerPinManager.isDomModeActive()) {
            ControllerEditMode.enterSubMode(this);
            return;
        }
        PrimaryHeader.bind(findViewById(android.R.id.content), 0, pageTitle, 0);
        TextView role = PrimaryHeader.editLockButton(findViewById(android.R.id.content));
        ControllerEditMode.renderButton(this, role);
        role.setOnClickListener(v -> {
            if (ControllerPinManager.isDomModeActive()) ControllerEditMode.enterSubMode(this);
            else ControllerPinGate.unlock(this, () -> {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            }, false);
        });
        SubHubNavigation.bind(this, findViewById(android.R.id.content), screen);
        refresh();
    }

    protected final void title(int title) {
        pageTitle = title;
        PrimaryHeader.bind(findViewById(android.R.id.content), 0, title, 0);
    }

    protected abstract void refresh();

    protected final void loading() {
        body.removeAllViews();
        PersonUi.text(body, getString(R.string.role_loading), false);
    }

    protected final void unavailable() {
        body.removeAllViews();
        PersonUi.text(body, getString(R.string.role_person_unavailable), false);
        PersonUi.action(body, R.string.role_retry, this::refresh, false);
    }

    protected final void secondaryBack(Runnable action) {
        TextView back = PrimaryHeader.backButton(findViewById(android.R.id.content));
        back.setVisibility(View.VISIBLE);
        back.setOnClickListener(v -> action.run());
        PrimaryHeader.editLockButton(findViewById(android.R.id.content)).setVisibility(View.GONE);
    }
}
