package com.subhub.app.commitment;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import java.util.function.LongConsumer;

/** The original six duration choices, with no preference or session side effects. */
public final class DurationChoicesView extends LinearLayout {
    private static final long HOUR = 3_600_000L;
    private static final long[] DURATIONS = {HOUR, 24 * HOUR, 7 * 24 * HOUR,
            30 * 24 * HOUR, ServiceDurationView.PERMANENT, ServiceDurationView.RANDOM};
    private final TextView[] choices = new TextView[6];
    private long duration = ServiceDurationView.PERMANENT;
    private boolean editable = true;
    private LongConsumer listener;

    public DurationChoicesView(Context context) { this(context, null); }

    public DurationChoicesView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setOrientation(VERTICAL);
        setSaveEnabled(false);
        LayoutInflater.from(context).inflate(R.layout.view_duration_choices, this, true);
        int[] ids = {R.id.commitment_timer_1h, R.id.commitment_timer_24h,
                R.id.commitment_timer_7d, R.id.commitment_timer_30d,
                R.id.commitment_timer_permanent, R.id.service_duration_random};
        for (int i = 0; i < ids.length; i++) {
            TextView choice = choices[i] = findViewById(ids[i]);
            final long value = DURATIONS[i];
            choice.setTag("duration_choice:" + value);
            choice.setSaveEnabled(false);
            choice.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(android.widget.RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(value == duration);
                }
            });
            choice.setOnClickListener(view -> {
                if (!editable || !isEnabled() || value == duration) return;
                setDuration(value);
                if (listener != null) listener.accept(value);
            });
        }
        render();
    }

    /** Binding/restoration is deliberately silent; only a changed user choice calls the listener. */
    public void setDuration(long value) {
        boolean known = false;
        for (long option : DURATIONS) if (option == value) known = true;
        if (!known) throw new IllegalArgumentException("Unsupported duration choice");
        duration = value;
        render();
    }

    public long duration() { return duration; }
    public void setOnDurationChanged(LongConsumer listener) { this.listener = listener; }
    public void setEditable(boolean value) { editable = value; render(); }

    @Override public void setEnabled(boolean value) { super.setEnabled(value); render(); }

    private void render() {
        if (choices == null) return; // View construction may call setEnabled before field initialization.
        for (int i = 0; i < choices.length; i++) {
            TextView choice = choices[i];
            if (choice == null) continue;
            boolean selected = duration == DURATIONS[i];
            choice.setSelected(selected);
            choice.setBackgroundResource(selected ? R.drawable.bg_home_duration_selected : R.drawable.bg_home_duration_idle);
            choice.setTextColor(getContext().getColor(selected ? R.color.control_checked_text : R.color.text_secondary));
            choice.setEnabled(editable && isEnabled());
        }
    }
}
