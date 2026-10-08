package com.subhub.app.stats;

import android.content.*;
import android.graphics.*;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import java.time.*;
import java.util.*;
import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class DailyStatisticsUiAndroidTest {
    @Test public void homeAndDetailAgreeAndPeriodSelectionSurvivesRecreation() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        DailyStatsStore store=DailyStatsStore.get(context); LocalDate today=LocalDate.now(); String zone=ZoneId.systemDefault().getId();
        // Dedicated emulator fixture: no personal data, payment calls, or actual charge instructions.
        store.getWritableDatabase().execSQL("UPDATE metadata SET value=? WHERE name='since'",new Object[]{today.minusDays(8).toString()});
        String source="ui-"+UUID.randomUUID(); Map<String,Long> counts=new LinkedHashMap<>();
        counts.put("censors",0L);counts.put("assessed_EUR",0L);counts.put("assessed_USD",0L);counts.put("paid_EUR",0L);
        store.checkpoint(source,counts,System.currentTimeMillis(),zone);
        for(int i=6;i>=0;i--) {
            long wall=today.minusDays(i).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            counts.put("censors",counts.get("censors")+8+i*2); counts.put("assessed_EUR",counts.get("assessed_EUR")+125);counts.put("assessed_USD",counts.get("assessed_USD")+50);counts.put("paid_EUR",counts.get("paid_EUR")+75);
            store.checkpoint(source,counts,wall,zone);store.heartbeat(source+i,1,wall,0,zone);store.heartbeat(source+i,0,wall+(i+1)*60000,(i+1)*60000,zone);
            store.exported(source+":"+i,"photos",wall,zone);
        }
        final String[] homeText={null};
        try(ActivityScenario<MainActivity> home=ActivityScenario.launch(new Intent(context,MainActivity.class).setAction(Intent.ACTION_MAIN))) {
            home.onActivity(a->{View panel=a.findViewById(R.id.daily_statistics_panel); ((android.widget.ScrollView)a.findViewById(R.id.page_content).getParent()).scrollTo(0,panel.getTop());});
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            home.onActivity(a->{homeText[0]=((TextView)a.findViewById(R.id.daily_day_summary)).getText().toString();capture(a,"daily-home.png");});
        }
        try(ActivityScenario<StatsActivity> detail=ActivityScenario.launch(StatsActivity.class)) {
            detail.onActivity(a->{
                Map<String,Long> expected=store.days(today.toString(),today.toString()).get(today.toString());
                assertEquals(context.getString(R.string.daily_compact_summary,StatsSnapshot.formatDuration(expected.get("service_ms")/1000),expected.get("censors")),homeText[0]);
                assertTrue(((TextView)a.findViewById(R.id.daily_day_summary)).getText().toString().contains(today.toString()));
            });
            onView(withId(R.id.daily_range_7)).perform(scrollTo(),click());
            onView(withId(R.id.daily_range_year)).perform(scrollTo(),click());
            detail.recreate();
            onView(withId(R.id.daily_range_year)).check(matches(isSelected()));
            onView(withId(R.id.daily_range_30)).perform(scrollTo(),click());
            detail.onActivity(a->capture(a,"daily-detail.png"));
        }
    }
    private static void capture(android.app.Activity activity,String name) {
        View root=activity.getWindow().getDecorView(); root.post(()->{
            Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));
            try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException failure){throw new AssertionError(failure);}finally{image.recycle();}
        });
    }
}
