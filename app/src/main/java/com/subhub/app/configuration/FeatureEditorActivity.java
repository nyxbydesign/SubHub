package com.subhub.app.configuration;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/** Saves editor drafts and restores widgets only in the current editable workspace. */
public abstract class FeatureEditorActivity extends AppCompatActivity {
    @Override protected void onSaveInstanceState(Bundle state) {
        FeatureEditorSession.saveState(this, state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onRestoreInstanceState(Bundle state) {
        // Retain raw input only for the same current internal draft; stale widget bundles cannot write newer state.
        if ((!FeatureEditorSession.internal(this) || FeatureEditorSession.restoreInternalWidgets(this, state))
                && !FeatureEditorSession.preview(this)) super.onRestoreInstanceState(state);
    }
}
