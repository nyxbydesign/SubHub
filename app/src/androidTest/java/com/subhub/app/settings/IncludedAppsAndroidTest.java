package com.subhub.app.settings;

import static org.junit.Assert.*;

import android.content.*;
import android.content.res.Configuration;
import android.view.*;
import android.widget.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.ControllerPinManager;

import org.junit.*;

import java.util.*;

public class IncludedAppsAndroidTest {
    @Before
    public void fixture() {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ControllerPinManager.setPin(c, "2468");
        ControllerPinManager.enterDomMode();
        new AppModeManager(c).save(false, Set.of());
    }

    @After
    public void restore() {
        ControllerPinManager.enterSubMode();
    }

    @Test
    public void recycledRowsKeepOnlyOneChoiceAndRespectRoleAndPackageBinding() {
        try (ActivityScenario<SetupAppsActivity> scenario =
                ActivityScenario.launch(SetupAppsActivity.class)) {
            scenario.onActivity(
                    a -> {
                        Set<String> chosen = new LinkedHashSet<>();
                        List<InstalledAppCatalog.Entry> apps =
                                Arrays.asList(
                                        new InstalledAppCatalog.Entry(
                                                "First app", "first.app", null),
                                        new InstalledAppCatalog.Entry(
                                                "Second app", "second.app", null));
                        IncludedAppsAdapter adapter =
                                new IncludedAppsAdapter(
                                        a,
                                        apps,
                                        chosen,
                                        (pkg, selected) -> {
                                            if (selected) chosen.add(pkg);
                                            else chosen.remove(pkg);
                                        });
                        adapter.setEditing(true);
                        IncludedAppRow first =
                                (IncludedAppRow) adapter.getView(0, null, new LinearLayout(a));
                        first.choice().performClick();
                        assertEquals(Set.of("first.app"), chosen);
                        IncludedAppRow second =
                                (IncludedAppRow) adapter.getView(1, first, new LinearLayout(a));
                        assertSame(first, second);
                        assertFalse(second.choice().isChecked());
                        second.choice().performClick();
                        assertEquals(Set.of("first.app", "second.app"), chosen);
                        adapter.setEditing(false);
                        adapter.getView(1, second, new LinearLayout(a));
                        assertFalse(second.choice().isEnabled());
                        second.performClick();
                        assertEquals(2, chosen.size());
                        assertEquals(1, countChoices(second));
                    });
        }
    }

    @Test
    public void oneChoiceFitsNarrowLargeTextAndRtlAtFullTouchSize() {
        try (ActivityScenario<SetupAppsActivity> scenario =
                ActivityScenario.launch(SetupAppsActivity.class)) {
            scenario.onActivity(
                    a -> {
                        for (float font : new float[] {1f, 1.7f, 2f})
                            for (int width : new int[] {200, 320})
                                for (int direction :
                                        new int[] {
                                            View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL
                                        }) {
                                    Configuration config =
                                            new Configuration(a.getResources().getConfiguration());
                                    config.fontScale = font;
                                    Context c = a.createConfigurationContext(config);
                                    IncludedAppRow row = new IncludedAppRow(c);
                                    row.bind(
                                            "Long application name for selection",
                                            "synthetic.app",
                                            null,
                                            true,
                                            true);
                                    row.setLayoutDirection(direction);
                                    int pixels =
                                            Math.round(
                                                    width
                                                            * c.getResources()
                                                                    .getDisplayMetrics()
                                                                    .density);
                                    row.measure(
                                            View.MeasureSpec.makeMeasureSpec(
                                                    pixels, View.MeasureSpec.EXACTLY),
                                            View.MeasureSpec.makeMeasureSpec(
                                                    0, View.MeasureSpec.UNSPECIFIED));
                                    row.layout(0, 0, pixels, row.getMeasuredHeight());
                                    CheckBox choice = row.choice();
                                    int min =
                                            Math.round(
                                                    48
                                                            * c.getResources()
                                                                    .getDisplayMetrics()
                                                                    .density);
                                    assertTrue(choice.getWidth() >= min);
                                    assertTrue(choice.getHeight() >= min);
                                    assertTrue(
                                            choice.getLeft() >= 0 && choice.getRight() <= pixels);
                                    assertEquals(1, countChoices(row));
                                }
                    });
        }
    }

    private static int countChoices(View v) {
        int count = v instanceof CompoundButton ? 1 : 0;
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                count += countChoices(((ViewGroup) v).getChildAt(i));
        return count;
    }
}
