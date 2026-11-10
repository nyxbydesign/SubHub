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
public abstract class RolePage extends AppCompatActivity implements SubHubNavigation.SectionHome {
    protected LinearLayout body;
    protected AsyncUiScope data;
    private int pageTitle;
    private SubHubNavigation.Screen screen;
    private boolean domOnly;
    private Integer restoredPosition;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) restoredPosition = state.getInt("role_page_position");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        com.subhub.app.util.PageScrollView scroll = findViewById(R.id.role_page_scroll);
        if (scroll != null) state.putInt("role_page_position",
                restoredPosition == null ? scroll.position() : restoredPosition);
        super.onSaveInstanceState(state);
    }

    protected final void renderPage(Runnable render) {
        com.subhub.app.util.PageScrollView scroll = findViewById(R.id.role_page_scroll);
        scroll.preserve(render);
        if (restoredPosition != null) {
            scroll.restorePosition(restoredPosition);
            restoredPosition = null;
        }
    }

    protected final void restorePagePosition(int position) { restoredPosition = Math.max(0, position); }

    protected final void rememberPagePosition() {
        com.subhub.app.util.PageScrollView scroll = findViewById(R.id.role_page_scroll);
        if (scroll != null) restoredPosition = scroll.position();
    }

    protected final void resetPagePosition() {
        restoredPosition = null;
        com.subhub.app.util.PageScrollView scroll = findViewById(R.id.role_page_scroll);
        if (scroll != null) scroll.scrollTo(0, 0);
    }

    protected final void page(int title, SubHubNavigation.Screen destination, boolean requiresDom) {
        pageTitle = title; screen = destination; domOnly = requiresDom;
        setContentView(R.layout.activity_role_page);
        body = findViewById(R.id.role_page_body);
        data = AsyncUiScope.forPage(this);
        PrimaryHeader.bindPrimary(findViewById(android.R.id.content), headerIcon(), title, 0);
    }

    @Override protected void onResume() {
        super.onResume();
        if (DummySubPreview.isRequested(getIntent()) && !DummySubPreview.isAllowed(this)) {
            if (body != null) body.removeAllViews();
            finish();
            return;
        }
        if (body == null) return;
        if (domOnly && !ControllerPinManager.isDomWorkspaceUnlocked()) {
            ControllerEditMode.enterSubMode(this);
            return;
        }
        PrimaryHeader.bindPrimary(findViewById(android.R.id.content), headerIcon(), pageTitle, 0);
        TextView role = PrimaryHeader.editLockButton(findViewById(android.R.id.content));
        ControllerEditMode.renderButton(this, role);
        role.setOnClickListener(v -> {
            if (ControllerPinManager.isDomWorkspaceUnlocked()) ControllerEditMode.enterSubMode(this);
            else ControllerPinGate.unlockDomWorkspace(this, () -> {
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
        PrimaryHeader.bindPrimary(findViewById(android.R.id.content), headerIcon(), title, 0);
    }

    private int headerIcon() {
        return PrimaryHeader.backButton(findViewById(android.R.id.content)).getVisibility() == View.VISIBLE
                ? 0 : PrimaryHeader.icon(screen);
    }

    protected abstract void refresh();

    @Override public void showSectionHome() {
        resetPagePosition();
        SubHubNavigation.scrollToTop(findViewById(android.R.id.content));
    }

    protected final void loading() {
        // A background refresh must not collapse a populated page while its read is pending.
        if (body.getChildCount() != 0) return;
        PersonUi.text(body, getString(R.string.role_data_unknown), false);
    }

    protected final void unavailable() {
        resetPagePosition();
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
