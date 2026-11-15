package com.subhub.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.util.CompactChoiceGroup;
import com.subhub.app.util.SelectionGrid;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CompactChoiceGroupAndroidTest {
    @Test public void detectionCardsShareRowHeightWhenMediumSubtitleWraps() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = ApplicationProvider.getApplicationContext();
            for (int width : new int[]{320, 360, 411, 480}) {
                for (float scale : new float[]{1f, 1.3f, 1.7f, 2f}) {
                    Configuration config = new Configuration(base.getResources().getConfiguration());
                    config.fontScale = scale;
                    Context themed = new android.view.ContextThemeWrapper(
                            base.createConfigurationContext(config), R.style.Theme_SubHub);
                    View page = LayoutInflater.from(themed).inflate(R.layout.activity_settings, null, false);
                    CompactChoiceGroup group = page.findViewById(R.id.preset_group);
                    String[] text = {"Low\nBalanced Coverage", "Medium\nMore Coverage", "High\nMaximum Coverage"};
                    for (int index = 0; index < 3; index++) {
                        TextView choice = (TextView) group.getChildAt(index);
                        assertEquals("Shared taller cards retain the same content top alignment",
                                android.view.Gravity.TOP, choice.getGravity() & android.view.Gravity.VERTICAL_GRAVITY_MASK);
                        android.text.SpannableString label = new android.text.SpannableString(text[index]);
                        label.setSpan(new android.text.style.RelativeSizeSpan(.8f),
                                text[index].indexOf('\n') + 1, text[index].length(), 0);
                        choice.setTextSize(14);
                        choice.setText(label);
                    }
                    for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                        group.setLayoutDirection(direction);
                        int pixels = Math.round((width - 40) * themed.getResources().getDisplayMetrics().density);
                        group.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        group.layout(0, 0, pixels, group.getMeasuredHeight());
                        assertEquals(1, check(group));
                        for (int i = 0; i < 3; i++) for (int j = i + 1; j < 3; j++) {
                            View a = group.getChildAt(i), b = group.getChildAt(j);
                            if (a.getTop() == b.getTop()) {
                                assertEquals("Card bottoms in the same row must align", a.getBottom(), b.getBottom());
                                assertEquals(a.getHeight(), b.getHeight());
                            }
                        }
                    }
                }
            }
        });
    }
    @Test public void choicesWrapWithoutClippingAtNarrowWideAndLargeTextSizes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(this::checkChoiceScreens);
    }

    @Test public void reusedRowsShrinkAndReflowWhenChildrenChange() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = ApplicationProvider.getApplicationContext();
            CompactChoiceGroup radio = new CompactChoiceGroup(context, null);
            radio.setPreferredColumns(2, 88);
            SelectionGrid independent = new SelectionGrid(context, null);
            int width = Math.round(900 * context.getResources().getDisplayMetrics().density);
            for (LinearLayout group : new LinearLayout[]{radio, independent}) {
                RadioButton tall = new RadioButton(context), second = new RadioButton(context);
                RadioButton third = new RadioButton(context);
                group.addView(tall, new RadioGroup.LayoutParams(0, 160));
                group.addView(second, new RadioGroup.LayoutParams(0, 32));
                group.addView(third, new RadioGroup.LayoutParams(0, 44));
                assertEquals(204, measureAndLayout(group, width));
                assertEquals(160, second.getHeight());
                ViewGroup.LayoutParams params = tall.getLayoutParams();
                params.height = 20;
                tall.setLayoutParams(params);
                assertEquals(76, measureAndLayout(group, width));
                assertEquals(32, third.getTop());
                second.setVisibility(View.GONE);
                assertEquals(44, measureAndLayout(group, width));
                assertEquals(0, third.getTop());
                second.setVisibility(View.VISIBLE);
                assertEquals(96, measureAndLayout(group, width / 10));
                assertEquals(76, measureAndLayout(group, width));
            }
        });
    }

    private static int measureAndLayout(ViewGroup group, int width) {
        group.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = group.getMeasuredHeight();
        group.layout(0, 0, width, height);
        return height;
    }

    private void checkChoiceScreens() {
        Context base = ApplicationProvider.getApplicationContext();
        for (int width : new int[]{320, 480}) for (float scale : new float[]{1f, 1.7f}) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.fontScale = scale;
            config.screenWidthDp = width;
            Context themed = new android.view.ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_SubHub);
            for (int layout : new int[]{R.layout.activity_settings, R.layout.activity_export_workspace}) {
                View page = LayoutInflater.from(themed).inflate(layout, null, false);
                int pixels = Math.round(width * themed.getResources().getDisplayMetrics().density);
                page.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY));
                page.layout(0, 0, pixels, 1280);
                int groups = check(page);
                if (layout == R.layout.activity_export_workspace) assertEquals(2, groups);
                else assertTrue("Censor settings has visible choice groups", groups > 0);
            }
        }
    }

    private static int check(View view) {
        int count = 0;
        if (view instanceof CompactChoiceGroup) {
            CompactChoiceGroup group = (CompactChoiceGroup) view;
            if (group.getVisibility() == View.GONE) return 0;
            count++;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView choice = (TextView) group.getChildAt(i);
                if (choice.getVisibility() == View.GONE) continue;
                assertTrue(choice.getWidth() > 0);
                assertNotNull(choice.getLayout());
                assertTrue(choice.getLayout().getHeight() <= choice.getHeight()
                        - choice.getCompoundPaddingTop() - choice.getCompoundPaddingBottom());
                for (int line = 0; line < choice.getLineCount(); line++) {
                    assertEquals(0, choice.getLayout().getEllipsisCount(line));
                }
                for (int j = i + 1; j < group.getChildCount(); j++) {
                    View other = group.getChildAt(j);
                    if (other.getVisibility() == View.GONE) continue;
                    if (choice.getTop() == other.getTop()) assertEquals(choice.getBottom(), other.getBottom());
                    assertFalse(android.graphics.Rect.intersects(new android.graphics.Rect(choice.getLeft(), choice.getTop(), choice.getRight(), choice.getBottom()),
                            new android.graphics.Rect(other.getLeft(), other.getTop(), other.getRight(), other.getBottom())));
                }
            }
            if (group.getChildCount() > 1) {
                group.check(group.getChildAt(0).getId());
                group.check(group.getChildAt(1).getId());
                assertFalse(((RadioButton) group.getChildAt(0)).isChecked());
                assertTrue(((RadioButton) group.getChildAt(1)).isChecked());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += check(group.getChildAt(i));
        }
        return count;
    }
}
