package com.subhub.app.people;

import android.widget.LinearLayout;
import com.subhub.app.R;
import com.subhub.app.commitment.ServiceDurationView;
import com.subhub.app.configuration.FeatureEditorSession;

/** Configures the selected local contract's duration without starting a session. */
public final class ServiceDurationActivity extends ContractEditorPage {
    private ServiceDurationView duration;

    public ServiceDurationActivity() {
        super("duration", R.string.person_duration_title);
    }

    @Override protected void createEditor() {
        duration = new ServiceDurationView(this, null, FeatureEditorSession.context(this), this::mayEdit);
        duration.setId(R.id.person_duration_control);
        duration.setTag("person_duration:" + personId().value());
        duration.setHeadingVisible(false);
        duration.setEditable(mayEdit());
        body.addView(duration, new LinearLayout.LayoutParams(-1, -2));
        bindEditor(() -> duration.validateSelection());
    }

    @Override protected void refresh() {
        if (duration == null) return;
        boolean editable = mayEdit();
        duration.setEditable(editable);
        if (!mayView()) { finish(); return; }
        duration.reload();
    }
}
