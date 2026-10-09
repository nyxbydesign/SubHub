package com.subhub.app.stats;
import android.content.*;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.time.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
@RunWith(AndroidJUnit4.class)
public class StatisticsQualityAndroidTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void legacyTotalsDoNotInventDailyRecordsAndViewsStayExclusive(){
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        DailyStatsStore store=DailyStatsStore.get(context);store.getWritableDatabase().execSQL("DELETE FROM daily");store.getWritableDatabase().execSQL("UPDATE metadata SET value=? WHERE name='since'",new Object[]{LocalDate.now().toString()});
        context.getSharedPreferences(StatsRepository.PREFS_NAME,0).edit().putLong("total_blocks_all_time",6031).commit();
        try(ActivityScenario<StatsActivity> stats=ActivityScenario.launch(StatsActivity.class)){
            onView(withId(R.id.stats_empty_activity)).check(matches(isDisplayed()));
            stats.onActivity(a->capture(a,"stats-empty.png"));
            onView(withId(R.id.stats_tab_totals)).perform(scrollTo(),click());
            onView(withId(R.id.daily_statistics_panel)).check(doesNotExist());
            stats.onActivity(a->{assertTrue(allText(a.findViewById(android.R.id.content)).contains(java.text.NumberFormat.getIntegerInstance().format(new StatsRepository(context).load().getTotalBlocks())));capture(a,"stats-totals.png");});
            onView(withId(R.id.stats_tab_sessions)).perform(scrollTo(),click());
            stats.onActivity(a->{assertTrue(countSessions(a.findViewById(android.R.id.content))<=5);capture(a,"stats-sessions.png");});
            stats.recreate();onView(withId(R.id.stats_tab_sessions)).check(matches(isSelected()));
        }
    }
    @Test public void arrangementBelongsToServiceAndActivityFollowsTheCompleteWidget(){
        ControllerPinManager.enterSubMode();
        try(ActivityScenario<MainActivity> home=ActivityScenario.launch(new Intent(context,MainActivity.class).setAction(Intent.ACTION_MAIN))){
            home.onActivity(a->{LinearLayout service=a.findViewById(R.id.home_service_widget),content=a.findViewById(R.id.page_content);assertEquals(service,a.findViewById(R.id.sub_dashboard).getParent());assertEquals(content.indexOfChild(service)+1,content.indexOfChild(a.findViewById(R.id.daily_statistics_panel)));capture(a,"home-service-arrangement.png");});
        }
    }
    private static int countSessions(View view){int count="stats:session".equals(view.getTag())?1:0;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)count+=countSessions(group.getChildAt(i));}return count;}
    private static String allText(View view){String text=view instanceof TextView?((TextView)view).getText().toString():"";if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)text+="\n"+allText(group.getChildAt(i));}return text;}
    private static void capture(android.app.Activity activity,String name){View root=activity.getWindow().getDecorView();root.post(()->{Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException error){throw new AssertionError(error);}finally{image.recycle();}});}
}
