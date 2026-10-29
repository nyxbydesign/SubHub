package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.ContractActivity;
import com.subhub.app.people.PeopleActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.DeviceRolePreferences.Mode;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.tasks.TasksActivity;
import com.subhub.app.tasks.TasksOverviewActivity;
import com.subhub.app.util.PrimaryHeader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class PrimaryPageHeaderAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Map<String, ?> achievements;

    @Before public void prepare() {
        assertFalse(new AppModeManager(context).isArmed());
        assertFalse(com.subhub.app.people.LocalSessionControls.isActive(context));
        achievements = new LinkedHashMap<>(context.getSharedPreferences("betablocker_achievements", 0).getAll());
        OnboardingState.complete(context);
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        new AchievementManager(context).takePendingNotifications();
    }
    @After public void restore() {
        SharedPreferenceTestRestore.restore(context.getSharedPreferences("betablocker_achievements", 0), achievements);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subPrimaryHeadersMatchSettingsAndSurviveScrolling() throws Exception { inspect(Mode.SUB_ONLY, false, "sub"); }
    @Test @SdkSuppress(minSdkVersion = 29)
    public void domPrimaryHeadersMatchSettingsAndRetainSectionHosts() throws Exception { inspect(Mode.DOM_ONLY, true, "dom"); }
    @Test @SdkSuppress(minSdkVersion = 29)
    public void bothSubHeadersMatchSettingsWithoutChangingRole() throws Exception { inspect(Mode.BOTH, false, "both-sub"); }
    @Test @SdkSuppress(minSdkVersion = 29)
    public void bothDomHeadersMatchSettingsWithoutChangingRole() throws Exception { inspect(Mode.BOTH, true, "both-dom"); }

    @SdkSuppress(minSdkVersion = 29)
    private void inspect(Mode mode, boolean dom, String name) throws Exception {
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, mode.name()).commit();
        if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        int reference;
        try (ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            reference = inspectPage(page, name+"-settings", R.drawable.ic_tab_settings, R.string.global_settings_title, true);
        }
        try (ActivityScenario<MainActivity> page = ActivityScenario.launch(new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true))) {
            assertEquals(reference, inspectPage(page, name+"-home", R.drawable.ic_tab_home, R.string.role_overview, true));
        }
        if (dom) {
            try (ActivityScenario<PeopleActivity> page = ActivityScenario.launch(PeopleActivity.class)) {
                assertEquals(reference, inspectPage(page, name+"-subs", R.drawable.ic_nav_people, R.string.role_people, false));
                onView(withId(R.id.people_section_activity)).perform(click());
                assertEquals(reference, inspectPage(page, name+"-activity", R.drawable.ic_shell_activity, R.string.dom_activity_title, false));
                page.recreate();
                assertEquals(reference, inspectPage(page, name+"-activity-recreated", R.drawable.ic_shell_activity, R.string.dom_activity_title, false));
                onView(withId(R.id.people_section_people)).perform(click());
                assertEquals(reference, inspectPage(page, name+"-subs-return", R.drawable.ic_nav_people, R.string.role_people, false));
            }
        } else {
            try (ActivityScenario<ContractActivity> page = ActivityScenario.launch(ContractActivity.class)) {
                assertEquals(reference, inspectPage(page, name+"-contract", R.drawable.ic_nav_contract, R.string.role_contract, true));
            }
        }
        Class<? extends Activity> tasks = dom ? TasksOverviewActivity.class : TasksActivity.class;
        try (ActivityScenario<? extends Activity> page = ActivityScenario.launch(new Intent(context, tasks))) {
            assertEquals(reference, inspectPage(page, name+"-tasks", R.drawable.ic_nav_tasks, R.string.tasks_title, true));
        }
        try (ActivityScenario<StatsActivity> page = ActivityScenario.launch(StatsActivity.class)) {
            assertEquals(reference, inspectPage(page, name+"-stats", R.drawable.ic_nav_stats, R.string.role_stats, true));
            onView(withId(R.id.stats_section_achievements)).perform(click());
            assertEquals(reference, inspectPage(page, name+"-achievements", R.drawable.ic_nav_stats, R.string.achievements_title, true));
            page.recreate();
            assertEquals(reference, inspectPage(page, name+"-achievements-recreated", R.drawable.ic_nav_stats, R.string.achievements_title, false));
            onView(withId(R.id.stats_section_statistics)).perform(click());
            assertEquals(reference, inspectPage(page, name+"-stats-return", R.drawable.ic_nav_stats, R.string.role_stats, false));
        }
        assertEquals(mode, DeviceRolePreferences.get(context));
        assertEquals(dom, ControllerPinManager.isDomModeActive());
    }

    @Test public void primaryIconsAndCompleteTitlesFitNarrowPhonesAtLargeText() {
        int[] titles={R.string.role_overview,R.string.role_people,R.string.dom_activity_title,R.string.role_contract,
                R.string.tasks_title,R.string.role_stats,R.string.achievements_title,R.string.global_settings_title};
        for (int width : new int[]{320,360,411,600}) for (float scale : new float[]{1f,1.3f,1.6f,2f}) {
            Configuration config=new Configuration(context.getResources().getConfiguration());
            config.screenWidthDp=width;config.fontScale=scale;
            Context themed=new ContextThemeWrapper(context.createConfigurationContext(config),R.style.Theme_SubHub);
            for (int title : titles) {
                ViewGroup root=(ViewGroup) LayoutInflater.from(themed).inflate(R.layout.view_primary_header,null,false);
                ((TextView) PrimaryHeader.editLockButton(root)).setText(R.string.role_dom);
                PrimaryHeader.bindPrimary(root,R.drawable.ic_nav_stats,title,0);
                int pixels=Math.round(width*themed.getResources().getDisplayMetrics().density)
                        -2*themed.getResources().getDimensionPixelSize(R.dimen.page_margin);
                root.measure(View.MeasureSpec.makeMeasureSpec(pixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                root.layout(0,0,pixels,root.getMeasuredHeight());
                assertEquals(View.VISIBLE,root.findViewById(R.id.primary_header_icon).getVisibility());
                textFits(root.findViewById(R.id.primary_header_title));textFits(root.findViewById(R.id.primary_header_brand));
                textFits(PrimaryHeader.editLockButton(root));
            }
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    private int inspectPage(ActivityScenario<? extends Activity> page,String name,int icon,int title,boolean scroll) throws Exception {
        AtomicReference<Activity> activity=new AtomicReference<>();page.onActivity(activity::set);
        long end=SystemClock.uptimeMillis()+5000;
        AtomicReference<View> content=new AtomicReference<>();
        do {
            page.onActivity(a -> content.set(firstContent(a)));
            if(content.get()!=null && content.get().getHeight()>0)break;
            SystemClock.sleep(25);
        } while(SystemClock.uptimeMillis()<end);
        if(scroll) {
            page.onActivity(a -> {
                ScrollView host;
                try { host=UiRefreshWitness.scroll(PrimaryHeader.view(a.findViewById(android.R.id.content))); }
                catch(AssertionError fixedHeader) { return; }
                int max=Math.max(0,host.getChildAt(0).getHeight()-host.getHeight()+host.getPaddingTop()+host.getPaddingBottom());
                host.scrollTo(0,max);assertEquals(max,host.getScrollY());host.scrollTo(0,0);assertEquals(0,host.getScrollY());
            });
        }
        String run=InstrumentationRegistry.getArguments().getString("evidence_run","headers");
        PrimaryHeaderFrameWitness.capture(activity.get(),run,name);
        AtomicReference<Integer> gap=new AtomicReference<>();
        page.onActivity(a -> {
            View header=PrimaryHeader.view(a.findViewById(android.R.id.content)), body=firstContent(a);
            assertNotNull(body);Rect h=new Rect(),b=new Rect();assertTrue(header.getGlobalVisibleRect(h));assertTrue(body.getGlobalVisibleRect(b));
            ImageView symbol=a.findViewById(R.id.primary_header_icon);TextView label=a.findViewById(R.id.primary_header_title);
            int actual=b.top-h.bottom;gap.set(actual);
            boolean painted=sameIcon(a,symbol,icon);
            JSONObject record=new JSONObject();
            try {
                record.put("title",label.getText().toString()).put("header",h.flattenToString()).put("content",b.flattenToString())
                        .put("gapPixels",actual).put("expectedGapPixels",a.getResources().getDimensionPixelSize(R.dimen.primary_header_content_gap))
                        .put("iconVisible",symbol.getVisibility()==View.VISIBLE).put("iconMatchesEstablishedAsset",painted)
                        .put("fontScale",a.getResources().getConfiguration().fontScale)
                        .put("roleControlVisible",PrimaryHeader.editLockButton(a.findViewById(android.R.id.content)).getVisibility()==View.VISIBLE);
                org.json.JSONArray lines=new org.json.JSONArray();
                if(label.getLayout()!=null)for(int line=0;line<label.getLineCount();line++)lines.put(new JSONObject()
                        .put("fullWidth",label.getLayout().getLineWidth(line)).put("visibleWidth",label.getLayout().getLineMax(line))
                        .put("allocatedWidth",label.getWidth()-label.getCompoundPaddingLeft()-label.getCompoundPaddingRight()));
                record.put("titleLines",lines);
                Files.write(new File(a.getFilesDir(),"ui-v3/"+run+"/"+name+".geometry.json").toPath(),record.toString(2).getBytes(StandardCharsets.UTF_8));
            } catch(Exception failure) {throw new AssertionError(failure);}
            assertEquals(record.toString(),a.getResources().getDimensionPixelSize(R.dimen.primary_header_content_gap),actual);
            assertTrue(record.toString(),actual>0);assertEquals(View.VISIBLE,symbol.getVisibility());assertTrue(record.toString(),painted);
            assertEquals(a.getString(title),label.getText().toString());textFits(label);textFits(a.findViewById(R.id.primary_header_brand));
            TextView control=PrimaryHeader.editLockButton(a.findViewById(android.R.id.content));
            if(control.getVisibility()==View.VISIBLE)textFits(control);
            else textFits(a.findViewById(R.id.header_role_badge));
        });
        return gap.get();
    }

    private View firstContent(Activity a) {
        View root=a.findViewById(android.R.id.content);
        if(a instanceof GlobalSettingsActivity)return root.findViewWithTag("settings_profile_names");
        if(a instanceof PeopleActivity)return a.findViewById(R.id.people_section_container);
        if(a instanceof StatsActivity)return root.findViewWithTag("stats_sections");
        if(a instanceof MainActivity) {
            View header=PrimaryHeader.view(root);ViewGroup parent=(ViewGroup)header.getParent();
            for(int i=parent.indexOfChild(header)+1;i<parent.getChildCount();i++) {
                View child=parent.getChildAt(i);if(child.getVisibility()==View.VISIBLE && child.getHeight()>0)return child;
            }
            return null;
        }
        return a.findViewById(R.id.role_page_body);
    }
    private static boolean sameIcon(Activity a,ImageView view,int expected) {
        if(view.getDrawable()==null || view.getDrawable().getConstantState()==null)return false;
        Drawable actual=view.getDrawable().getConstantState().newDrawable(a.getResources()).mutate();
        Drawable wanted=androidx.appcompat.content.res.AppCompatResources.getDrawable(a,expected).mutate();
        actual.setTint(a.getColor(R.color.text_primary));wanted.setTint(a.getColor(R.color.text_primary));
        Bitmap left=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888),right=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);
        try {actual.setBounds(0,0,64,64);wanted.setBounds(0,0,64,64);actual.draw(new Canvas(left));wanted.draw(new Canvas(right));return left.sameAs(right);}
        finally {left.recycle();right.recycle();}
    }
    private static void textFits(TextView text) {
        assertNotNull(text.getLayout());int width=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight();
        for(int i=0;i<text.getLayout().getLineCount();i++) {
            assertEquals(text.getText().toString(),0,text.getLayout().getEllipsisCount(i));
            assertTrue(text.getText().toString(),text.getLayout().getLineMax(i)<=width+2);
        }
        assertTrue(text.getText().toString(),text.getLayout().getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+2);
    }
}
