package com.subhub.app.atmosphere;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.capture.CensorImageEditor;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.people.PersonUi;
import com.subhub.app.popup.PopupStormEditorPanel;
import com.subhub.app.remote.GagContractFields;
import com.subhub.app.subliminal.WhispersEditorPanel;

/** Collects the three target-scoped effect editors in the Contract's Atmosphere card. */
public final class AtmosphereEditorPanels implements AutoCloseable {
    private enum Effect {
        WHISPERS("subliminal", R.string.sub_feature_whispers, R.layout.panel_whispers),
        POPUP("popup", R.string.popup_title, R.layout.panel_popup_storm),
        GAG(GagContractFields.SECTION, R.string.gag_title, R.layout.panel_gag);

        final String section;
        @StringRes final int title;
        @LayoutRes final int layout;

        Effect(String section, int title, int layout) {
            this.section = section;
            this.title = title;
            this.layout = layout;
        }
    }

    private final AppCompatActivity activity;
    private final CensorImageEditor.Picker images;
    private final LinearLayout root;
    private final TextView[] headers = new TextView[Effect.values().length];
    private final LinearLayout[] contents = new LinearLayout[Effect.values().length];
    private final View[] forms = new View[Effect.values().length];
    private final TextView[] unavailable = new TextView[Effect.values().length];
    private final Bundle restored;
    private Effect open;
    private WhispersEditorPanel whispers;
    private PopupStormEditorPanel popup;
    private GagRulesEditor gag;

    public AtmosphereEditorPanels(AppCompatActivity activity, LinearLayout parent,
            CensorImageEditor.Picker images, Bundle state) {
        this.activity = activity;
        this.images = images;
        restored = state == null ? new Bundle() : state;
        root = PersonUi.card(parent);
        root.setTag("person_section:atmosphere");
        PersonUi.text(root, activity.getString(R.string.atmosphere_title), true);
        open = Effect.WHISPERS;
        for (Effect effect : Effect.values()) {
            if (effect.section.equals(restored.getString("open"))) open = effect;
            int index = effect.ordinal();
            TextView header = (TextView) activity.getLayoutInflater()
                    .inflate(R.layout.view_atmosphere_disclosure, root, false);
            header.setText(effect.title);
            header.setTag("atmosphere_header:" + effect.section);
            root.addView(header);
            headers[index] = header;
            LinearLayout content = PersonUi.column(activity);
            content.setTag("atmosphere_panel:" + effect.section);
            root.addView(content, new LinearLayout.LayoutParams(-1, -2));
            contents[index] = content;
            unavailable[index] = PersonUi.text(content, activity.getString(effect == Effect.GAG
                    ? R.string.atmosphere_gag_unavailable : R.string.sub_feature_disabled), false);
            AtmosphereDisclosure.bind(header, content, false);
            header.setOnClickListener(view -> select(effect));
        }
        select(open);
    }

    public LinearLayout root() { return root; }

    public void refreshAccess() {
        for (Effect effect : Effect.values()) refreshAccess(effect);
    }

    private void refreshAccess(Effect effect) {
        boolean editable = effect == Effect.GAG ? FeatureEditorSession.canEditGag(activity)
                : FeatureEditorSession.canEdit(activity, effect.section);
        int index = effect.ordinal();
        if (editable && forms[index] == null && effect == open) createForm(effect);
        if (forms[index] != null) forms[index].setVisibility(editable ? View.VISIBLE : View.GONE);
        unavailable[index].setVisibility(editable && forms[index] != null ? View.GONE : View.VISIBLE);
    }

    private void select(Effect selected) {
        boolean switched = selected != open;
        if (switched) {
            if (whispers != null) { whispers.flush(); whispers.stopPreview(); }
            if (popup != null) popup.flush();
        }
        open = selected;
        refreshAccess(selected);
        for (Effect effect : Effect.values()) {
            if (effect != selected) show(effect, false);
        }
        show(selected, true);
        if (switched) {
            TextView header = headers[selected.ordinal()];
            header.post(() -> {
                if (open == selected) header.requestRectangleOnScreen(
                        new Rect(0, 0, header.getWidth(), header.getHeight()));
            });
        }
    }

    private void show(Effect effect, boolean expanded) {
        int index = effect.ordinal();
        AtmosphereDisclosure.show(headers[index], contents[index], expanded);
    }

    private void createForm(Effect effect) {
        int index = effect.ordinal();
        LinearLayout content = contents[index];
        View form = LayoutInflater.from(activity).inflate(effect.layout, content, false);
        switch (effect) {
            case WHISPERS -> whispers = new WhispersEditorPanel(activity, form, restored.getBoolean("whispers_advanced"));
            case POPUP -> popup = new PopupStormEditorPanel(activity, form, restored.getBoolean("popup_advanced"), images);
            case GAG -> {
                try { gag = new GagRulesEditor(activity, form, restored.getBundle("gag")); }
                catch (Exception unavailable) { return; }
            }
        }
        content.addView(form);
        forms[index] = form;
    }

    public Bundle saveState() {
        Bundle state = new Bundle(restored);
        state.putString("open", open.section);
        if (whispers != null) state.putBoolean("whispers_advanced", whispers.advancedExpanded());
        if (popup != null) state.putBoolean("popup_advanced", popup.advancedExpanded());
        if (gag != null) state.putBundle("gag", gag.saveState());
        return state;
    }

    public boolean flush() {
        flushDeferredInputs();
        // Keep invalid Gagging input available for correction without delaying valid changes to the other effects.
        if (gag != null) gag.flush();
        return true;
    }

    public void flushDeferredInputs() {
        if (whispers != null) whispers.flush();
        if (popup != null) popup.flush();
    }

    @Override public void close() {
        if (whispers != null) whispers.close();
        if (popup != null) popup.close();
    }
}
