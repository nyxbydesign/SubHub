package com.subhub.app.people;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native measurement uses actual translated text and font size, not a screen-size breakpoint. */
@RunWith(AndroidJUnit4.class)
public final class ProfileActionRowAndroidTest {
    @Test public void actionPairsReverseOnlyWhenWideAndRetainTheirListeners() {
        main(() -> {
            Context context = context(1f);
            for (int[] labels : new int[][]{{R.string.profile_change_avatar, R.string.profile_remove_avatar},
                    {R.string.profile_save, android.R.string.cancel}}) {
                ProfileActionRow row = new ProfileActionRow(context);
                AtomicInteger firstClicks = new AtomicInteger(), secondClicks = new AtomicInteger();
                TextView top = PersonUi.action(row, labels[0], firstClicks::incrementAndGet, false);
                TextView bottom = PersonUi.action(row, labels[1], secondClicks::incrementAndGet, false);
                measure(row, 480);
                assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
                assertSame(bottom, row.getChildAt(0)); assertSame(top, row.getChildAt(1));
                assertTrue(bottom.getRight() < top.getLeft());
                assertTrue(Math.abs(top.getWidth() - bottom.getWidth()) <= 1);
                fits(top); fits(bottom);
                measure(row, 140);
                assertEquals(LinearLayout.VERTICAL, row.getOrientation());
                assertSame(top, row.getChildAt(0)); assertSame(bottom, row.getChildAt(1));
                assertTrue(top.getBottom() < bottom.getTop());
                fits(top); fits(bottom);
                measure(row, 480);
                assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
                row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                measure(row, 480);
                assertSame(bottom, row.getChildAt(0)); assertSame(top, row.getChildAt(1));
                assertTrue("RTL keeps the secondary action first in reading order", top.getRight() < bottom.getLeft());
                fits(top); fits(bottom);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                measure(row, 480);
                top.performClick(); bottom.performClick();
                assertEquals(1, firstClicks.get()); assertEquals(1, secondClicks.get());
                bottom.setVisibility(View.GONE);
                measure(row, 480);
                assertEquals(row.getWidth(), top.getWidth());
                bottom.setVisibility(View.VISIBLE);
                measure(row, 480);
                assertSame(bottom, row.getChildAt(0));
                fits(top); fits(bottom);
            }
        });
    }

    @Test public void actionPairsStackForLargeTextAndLongLabelsWithoutTruncation() {
        main(() -> {
            Context context = context(2f);
            ProfileActionRow row = new ProfileActionRow(context);
            TextView choose = PersonUi.action(row, R.string.profile_change_avatar, () -> {}, false);
            TextView remove = PersonUi.action(row, R.string.profile_remove_avatar, () -> {}, false);
            measure(row, 300);
            assertEquals(LinearLayout.VERTICAL, row.getOrientation());
            assertSame(choose, row.getChildAt(0)); fits(choose); fits(remove);
            measure(row, 700);
            assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
            fits(choose); fits(remove);
            choose.setText("Choose a different profile picture");
            remove.setText("Remove the current profile picture");
            measure(row, 400);
            assertEquals(LinearLayout.VERTICAL, row.getOrientation());
            fits(choose); fits(remove);
        });
    }

    private static Context context(float scale) {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale = scale; config.setLocale(Locale.ENGLISH);
        return new ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_SubHub);
    }

    private static void measure(ProfileActionRow row, int widthDp) {
        row.measure(View.MeasureSpec.makeMeasureSpec(PersonUi.dp(row.getContext(), widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
    }

    private static void fits(TextView button) {
        assertTrue(button.getHeight() >= PersonUi.dp(button.getContext(), 48));
        android.text.Layout text = button.getLayout();
        assertNotNull(text);
        for (int line = 0; line < text.getLineCount(); line++) {
            assertEquals(0, text.getEllipsisCount(line));
            assertTrue(text.getLineMax(line) <= button.getWidth()
                    - button.getCompoundPaddingLeft() - button.getCompoundPaddingRight() + 1);
        }
        assertTrue(text.getHeight() <= button.getHeight()
                - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom());
    }

    private static void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
