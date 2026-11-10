package com.subhub.app.remote;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.penance.PenanceEvent;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.stats.StatsRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ExtraTimeTributeAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void failedAcknowledgementRecoversOneDebitAndOneProjectionWithoutInventingPayment() throws Exception {
        try (RemoteExtraTimeUiFixture fixture = RemoteExtraTimeUiFixture.setupSub(context)) {
            SharedPreferences real = context.getSharedPreferences(PenanceManager.PREFS_NAME,0);
            AtomicInteger attempts = new AtomicInteger();
            SharedPreferences uncertain = (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                        if (!method.getName().equals("edit")) return method.invoke(real,args);
                        SharedPreferences.Editor editor = real.edit();
                        return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class},(editorProxy,operation,values)->{
                                    Object value = operation.invoke(editor,values);
                                    if (operation.getName().equals("commit")) return attempts.incrementAndGet()>2 && (Boolean)value;
                                    return value instanceof SharedPreferences.Editor ? editorProxy : value;
                                });
                    });
            Context fault = new ContextWrapper(context) {
                @Override public Context getApplicationContext() { return this; }
                @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                    return name.equals(PenanceManager.PREFS_NAME) ? uncertain : super.getSharedPreferences(name,mode);
                }
            };
            PenanceManager manager = new PenanceManager(fault); long now = System.currentTimeMillis();
            long previous = new StatsRepository(context).tributeCents("EUR",false);
            assertThrows(IllegalStateException.class,()->manager.recordExtraTimeRequest("stable_submission",100,"EUR",now));
            assertThrows(IllegalStateException.class,()->manager.recordExtraTimeRequest("stable_submission",100,"EUR",now));
            assertEquals(100,manager.recordExtraTimeRequest("stable_submission",100,"EUR",now));
            assertEquals(100,new PenanceManager(context).recordExtraTimeRequest("stable_submission",100,"EUR",now));
            assertEquals(1,manager.snapshot(now).getEvents().size());
            PenanceEvent event = manager.snapshot(now).getEvents().get(0);
            assertEquals(PenanceInfraction.EXTRA_TIME_REQUEST,event.getInfraction());
            assertEquals(PenanceEvent.Status.OPEN,event.getStatus()); assertTrue(event.isInMercy(now));
            assertEquals(previous+100,new StatsRepository(context).tributeCents("EUR",false));
            assertEquals(PenanceManager.CheckoutMode.NONE,manager.getActiveCheckoutMode());
            assertEquals("",manager.getActiveOrderId());
        }
    }
    @Test public void configuredPriceCapsAndWalletOptoutApplyAtSubmissionNotDecision() throws Exception {
        try (RemoteExtraTimeUiFixture fixture = RemoteExtraTimeUiFixture.setupSub(context)) {
            PenanceManager manager = new PenanceManager(context); long now = System.currentTimeMillis();
            assertTrue(context.getSharedPreferences(PenanceManager.PREFS_NAME,0).edit()
                    .putInt("daily_cap_cents",150).putInt("weekly_cap_cents",150).commit());
            assertEquals(100,manager.quoteExtraTimeRequest(now));
            assertEquals(100,manager.recordExtraTimeRequest("one",100,"EUR",now));
            assertEquals(50,manager.quoteExtraTimeRequest(now));
            assertThrows(IllegalArgumentException.class,()->manager.recordExtraTimeRequest("changed_quote",100,"EUR",now));
            assertEquals(50,manager.recordExtraTimeRequest("two",50,"EUR",now));
            assertEquals(0,manager.quoteExtraTimeRequest(now));
            assertEquals(0,manager.recordExtraTimeRequest("capped",0,"EUR",now));
            assertEquals(2,manager.snapshot(now).getEvents().size());
            assertTrue(context.getSharedPreferences(SubFeaturePolicy.STORE,0).edit().putBoolean("wallet",false).commit());
            assertEquals(0,manager.recordExtraTimeRequest("wallet_optout",0,"EUR",now));
            assertEquals(0,manager.recordInfraction(PenanceInfraction.EXTRA_TIME_REQUEST,1,now));
            assertEquals(2,manager.snapshot(now).getEvents().size());
        }
    }
}
