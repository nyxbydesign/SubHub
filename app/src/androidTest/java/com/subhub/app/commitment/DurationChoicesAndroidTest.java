package com.subhub.app.commitment;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Stateless rendering must not turn binding, restoration or repeated taps into commands. */
public class DurationChoicesAndroidTest {
    private static final long[] VALUES = {3_600_000, 86_400_000, 604_800_000, 2_592_000_000L, -1, -2};

    @Test public void sixOriginalChoicesBindSilentlyAndDispatchOnlyChangedEditableSelections() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new androidx.appcompat.view.ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.Theme_SubHub);
            DurationChoicesView picker = new DurationChoicesView(context);
            AtomicInteger changes = new AtomicInteger();
            picker.setOnDurationChanged(value -> changes.incrementAndGet());
            assertEquals(3, picker.getChildCount());
            for (long value : VALUES) {
                picker.setDuration(value);
                assertEquals(value, picker.duration());
                assertTrue(choice(picker, value).isSelected());
            }
            assertEquals(0, changes.get());
            for (long value : VALUES) {
                choice(picker, value).performClick();
                assertEquals(value, picker.duration());
            }
            assertEquals(6, changes.get());
            choice(picker, -2).performClick();
            assertEquals(6, changes.get());
            picker.setEditable(false);
            for (long value : VALUES) {
                assertFalse(choice(picker, value).isEnabled());
                choice(picker, value).performClick();
            }
            assertEquals(-2, picker.duration()); assertEquals(6, changes.get());
            picker.setEditable(true); picker.setEnabled(false);
            choice(picker, -1).performClick();
            assertEquals(-2, picker.duration()); assertEquals(6, changes.get());
            picker.setEnabled(true);
            choice(picker, -1).performClick();
            assertEquals(-1, picker.duration()); assertEquals(7, changes.get());
        });
    }

    @Test public void nativeChoiceLabelsFitNarrowAndLargeFontLayouts() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (float font : new float[]{1f, 1.5f, 2f}) {
                android.content.res.Configuration configuration = new android.content.res.Configuration(base.getResources().getConfiguration());
                configuration.fontScale = font;
                Context context = new androidx.appcompat.view.ContextThemeWrapper(
                        base.createConfigurationContext(configuration), R.style.Theme_SubHub);
                DurationChoicesView picker = new DurationChoicesView(context);
                int width = Math.round(240 * context.getResources().getDisplayMetrics().density);
                picker.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                picker.layout(0, 0, width, picker.getMeasuredHeight());
                for (long value : VALUES) {
                    TextView choice = choice(picker, value);
                    android.text.Layout text = choice.getLayout();
                    assertNotNull(text);
                    int available = choice.getWidth() - choice.getCompoundPaddingLeft() - choice.getCompoundPaddingRight();
                    for (int line = 0; line < text.getLineCount(); line++) {
                        assertEquals(0, text.getEllipsisCount(line));
                        assertTrue(text.getLineMax(line) <= available + 1f);
                    }
                    assertTrue(choice.getHeight() >= 48 * context.getResources().getDisplayMetrics().density - 1);
                }
            }
        });
    }

    private static TextView choice(DurationChoicesView picker, long duration) {
        return picker.findViewWithTag("duration_choice:" + duration);
    }
}
