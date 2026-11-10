package com.subhub.app.privacy;
import android.os.Bundle;
import com.subhub.app.R;
import com.subhub.app.util.PreferencePage;
public final class PrivacyActivity extends PreferencePage {
    @Override protected void onCreate(Bundle state) { super.onCreate(state); render(); }
    private void render() { page(R.string.privacy_title); PrivacyControls.bind(this,card(page),this::render); }
}
