package com.subhub.app.atmosphere;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.view.ViewCompat;

import com.subhub.app.R;
import com.subhub.app.capture.CensorImageEditor;
import com.subhub.app.configuration.FeatureEditorSession;
import com.subhub.app.people.PersonUi;
import com.subhub.app.popup.PopupStormEditorPanel;
import com.subhub.app.remote.GagContractFields;
import com.subhub.app.subliminal.WhispersEditorPanel;

/** Collects the three target-scoped effect editors on the Atmosphere page. */
final class AtmosphereEditorPanels implements AutoCloseable {
    private enum Effect {
        WHISPERS("subliminal", R.string.sub_feature_whispers, R.layout.panel_whispers,
                R.id.whispers_card, R.drawable.ic_subliminal_whisper_2),
        POPUP("popup", R.string.popup_title, R.layout.panel_popup_storm,
                R.id.popup_storm_card, R.drawable.ic_popup_storm),
        GAG(GagContractFields.SECTION, R.string.gag_title, R.layout.panel_gag,
                R.id.gag_card, R.drawable.ic_atmosphere);

        final String section;
        @StringRes final int title;
        @LayoutRes final int layout;
        final int headerId;
        final int icon;

        Effect(String section, int title, int layout, int headerId, int icon) {
            this.section = section;
            this.title = title;
            this.layout = layout;
            this.headerId = headerId;
            this.icon = icon;
        }
    }

    private final AppCompatActivity activity;
    private final CensorImageEditor.Picker images;
    private final PopupStormEditorPanel.LocalRequests localRequests;
    private final TextView[] headers = new TextView[Effect.values().length];
    private final LinearLayout[] contents = new LinearLayout[Effect.values().length];
    private final View[] forms = new View[Effect.values().length];
    private final TextView[] unavailable = new TextView[Effect.values().length];
    private final Bundle restored;
    private Effect open;
    private WhispersEditorPanel whispers;
    private PopupStormEditorPanel popup;
    private GagRulesEditor gag;
    private boolean resumed;

    AtmosphereEditorPanels(AppCompatActivity activity, LinearLayout parent,
            CensorImageEditor.Picker images, PopupStormEditorPanel.LocalRequests localRequests, Bundle state) {
        this.activity = activity;
        this.images = images;
        this.localRequests = localRequests;
        restored = state == null ? new Bundle() : state;
        open = Effect.WHISPERS;
        for (Effect effect : Effect.values()) {
            if (effect.section.equals(restored.getString("open"))) open = effect;
            int index = effect.ordinal();
            LinearLayout card = PersonUi.column(activity);
            card.setBackgroundResource(R.drawable.bg_card);
            card.setClipToOutline(true);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            if (index > 0) cardParams.topMargin = spacing(R.dimen.ui_gap_control);
            parent.addView(card, cardParams);
            TextView header = (TextView) activity.getLayoutInflater()
                    .inflate(R.layout.view_atmosphere_disclosure, card, false);
            header.setText(effect.title);
            header.setId(effect.headerId);
            header.setTag("atmosphere_header:" + effect.section);
            Drawable icon = AppCompatResources.getDrawable(activity, effect.icon);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(activity.getColor(R.color.accent_text));
                int size = spacing(R.dimen.atmosphere_panel_icon);
                icon.setBounds(0, 0, size, size);
                header.setCompoundDrawablesRelative(icon, null, null, null);
            }
            ViewCompat.setAccessibilityHeading(header, true);
            card.addView(header);
            headers[index] = header;
            LinearLayout content = PersonUi.column(activity);
            int padding = spacing(R.dimen.ui_control_padding);
            content.setPaddingRelative(padding, 0, padding, padding);
            content.setTag("atmosphere_panel:" + effect.section);
            card.addView(content, new LinearLayout.LayoutParams(-1, -2));
            contents[index] = content;
            unavailable[index] = PersonUi.text(content, activity.getString(effect == Effect.GAG
                    ? R.string.atmosphere_gag_unavailable : R.string.sub_feature_disabled), false);
            AtmosphereDisclosure.bind(header, content, false);
            header.setOnClickListener(view -> select(effect));
        }
        select(open);
    }

    private int spacing(int size) { return activity.getResources().getDimensionPixelSize(size); }

    PopupStormEditorPanel popup() { return popup; }

    void resume() {
        resumed = true;
        for (Effect effect : Effect.values()) refreshAccess(effect);
        if (open == Effect.WHISPERS && whispers != null) whispers.resume();
        if (open == Effect.POPUP && popup != null) popup.resume();
    }

    void pause() {
        resumed = false;
        flushDeferredInputs();
        if (whispers != null) whispers.stopPreview();
        if (popup != null) popup.pause();
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
            if (popup != null) popup.pause();
        }
        open = selected;
        refreshAccess(selected);
        for (Effect effect : Effect.values()) {
            if (effect != selected) show(effect, false);
        }
        show(selected, true);
        if (switched) {
            if (resumed && selected == Effect.POPUP && popup != null) popup.resume();
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
            case WHISPERS -> {
                whispers = new WhispersEditorPanel(activity, form, false);
                whispers.showAllSettings();
            }
            case POPUP -> {
                popup = new PopupStormEditorPanel(activity, form, false, images, localRequests);
                popup.showAllSettings();
            }
            case GAG -> {
                try { gag = new GagRulesEditor(activity, form, restored.getBundle("gag")); }
                catch (Exception unavailable) { return; }
                gag.showAllSettings();
            }
        }
        content.addView(form);
        forms[index] = form;
    }

    Bundle saveState() {
        Bundle state = new Bundle(restored);
        state.putString("open", open.section);
        if (gag != null) state.putBundle("gag", gag.saveState());
        return state;
    }

    boolean flush() {
        flushDeferredInputs();
        // Keep invalid Gagging input available for correction without delaying valid changes to the other effects.
        if (gag != null) gag.flush();
        return true;
    }

    void flushDeferredInputs() {
        if (whispers != null) whispers.flush();
        if (popup != null) popup.flush();
    }

    @Override public void close() {
        if (whispers != null) whispers.close();
        if (popup != null) popup.close();
    }
}
