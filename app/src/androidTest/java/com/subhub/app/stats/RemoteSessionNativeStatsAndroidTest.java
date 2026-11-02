package com.subhub.app.stats;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.settings.SettingsRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RemoteSessionNativeStatsAndroidTest {
    private Context target,context;
    private String prefix;
    private StatsRepository stats;
    private final AtomicBoolean failBefore=new AtomicBoolean(),failAfter=new AtomicBoolean();
    @Before public void setup() {
        target=InstrumentationRegistry.getInstrumentation().getTargetContext();prefix="native-session-stats-"+UUID.randomUUID()+"-";
        context=new ContextWrapper(target) {
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                SharedPreferences saved=target.getSharedPreferences(prefix+name,mode);
                if(!StatsRepository.PREFS_NAME.equals(name))return saved;
                return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},
                        (proxy,method,args)->{
                    if(method.getName().equals("edit"))return editor(saved.edit());
                    try{return method.invoke(saved,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
            }
        };
        stats=new StatsRepository(context,false);
    }
    private SharedPreferences.Editor editor(SharedPreferences.Editor saved) {
        return (SharedPreferences.Editor)Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},
                (proxy,method,args)->{
            if(method.getName().equals("commit")&&failBefore.getAndSet(false))return false;
            Object value;
            try{value=method.invoke(saved,args);}catch(InvocationTargetException error){throw error.getCause();}
            if(method.getName().equals("commit")&&failAfter.getAndSet(false))return false;
            return value==saved?proxy:value;
        });
    }
    @After public void cleanup() {
        for(String name:new String[]{StatsRepository.PREFS_NAME,SettingsRepository.PREFERENCES_NAME})
            target.getSharedPreferences(prefix+name,0).edit().clear().commit();
        stats.sessionStamp();
    }
    private SharedPreferences saved() { return context.getSharedPreferences(StatsRepository.PREFS_NAME,0); }
    private StatsRepository.NativeSession session() {
        return new StatsRepository.NativeSession(UUID.randomUUID().toString(),System.currentTimeMillis()-60000,"Europe/Berlin");
    }
    private void committedMainOwner(StatsRepository.NativeSession session) {
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit()
                .putString(StatsRepository.NATIVE_OWNER_KEY,session.identity).putBoolean("app_mode_armed",true).commit());
    }

    @Test public void startAndEndReplaysCountAndAppendExactlyOnce() {
        StatsRepository.NativeSession session=session();committedMainOwner(session);
        assertTrue(stats.ensureNativeSession(session));assertTrue(new StatsRepository(context,false).ensureNativeSession(session));
        assertEquals(1,saved().getInt("sessions_count",0));assertEquals(session.identity,stats.activeSessionIdentity());
        assertTrue(stats.endSessionForIdentity(session.identity,System.currentTimeMillis(),60));
        assertTrue(stats.endSessionForIdentity(session.identity,System.currentTimeMillis()+999999,999));
        assertEquals(1,stats.getSessionHistory().size());assertEquals(60,saved().getLong("total_session_time",0));
        assertEquals(60,stats.getSessionHistory().get(0).getDurationSeconds());assertEquals("",stats.activeSessionIdentity());
        assertTrue(stats.ensureNativeSession(session));assertEquals("",stats.activeSessionIdentity());
    }

    @Test public void lateExactClosureCannotEndOrReplaceALaterSession() {
        StatsRepository.NativeSession first=session();
        StatsRepository.NativeSession later=new StatsRepository.NativeSession(UUID.randomUUID().toString(),first.startedAt,first.zone);
        committedMainOwner(first);assertTrue(stats.ensureNativeSession(first));StatsRepository.SessionStamp oldStamp=stats.sessionStamp();
        assertTrue(stats.endSessionForIdentity(first.identity,System.currentTimeMillis(),60));
        committedMainOwner(later);assertTrue(stats.ensureNativeSession(later));
        assertNotSame(oldStamp,stats.sessionStamp());
        assertEquals(StatsRepository.EvidenceStatus.REJECTED,stats.recordEvidence(oldStamp,
                ()->{throw new AssertionError("Stale session must not claim source evidence");},java.util.Collections.emptySet()).status);
        assertTrue(stats.endSessionForIdentity(first.identity));assertEquals(later.identity,stats.activeSessionIdentity());
        assertFalse(stats.ensureNativeSession(first));assertEquals(2,saved().getInt("sessions_count",0));
        assertTrue(stats.endSessionForIdentity(later.identity,System.currentTimeMillis(),10));
        assertFalse(stats.endSessionForIdentity(first.identity));assertEquals(70,saved().getLong("total_session_time",0));
        assertEquals(2,stats.getSessionHistory().size());
    }

    @Test public void unacknowledgedStartAndEndFlushWithoutRepeatingTheirCounters() {
        StatsRepository.NativeSession session=session();committedMainOwner(session);
        failAfter.set(true);assertFalse(stats.ensureNativeSession(session));
        assertTrue(stats.ensureNativeSession(session));assertEquals(1,saved().getInt("sessions_count",0));
        failAfter.set(true);assertFalse(stats.endSessionForIdentity(session.identity,System.currentTimeMillis(),60));
        assertTrue(stats.endSessionForIdentity(session.identity,System.currentTimeMillis(),60));
        assertEquals(1,stats.getSessionHistory().size());assertEquals(60,saved().getLong("total_session_time",0));
    }

    @Test public void failureBeforeWritingKeepsTheOwnedSessionForSafeRetry() {
        StatsRepository.NativeSession session=session();committedMainOwner(session);
        failBefore.set(true);assertFalse(stats.ensureNativeSession(session));assertEquals(0,saved().getInt("sessions_count",0));
        assertTrue(stats.ensureNativeSession(session));
        failBefore.set(true);assertFalse(stats.endSessionForIdentity(session.identity,System.currentTimeMillis(),60));
        assertEquals(session.identity,stats.activeSessionIdentity());assertTrue(stats.getSessionHistory().isEmpty());
        assertTrue(stats.endSessionForIdentity(session.identity,System.currentTimeMillis(),60));
        assertEquals(1,stats.getSessionHistory().size());
    }

    @Test public void mainOwnershipAndOriginalStartFactsCannotBeChangedByReplay() {
        StatsRepository.NativeSession session=session();assertFalse(stats.ensureNativeSession(session));
        committedMainOwner(session);assertTrue(stats.ensureNativeSession(session));
        assertFalse(stats.ensureNativeSession(new StatsRepository.NativeSession(session.identity,session.startedAt+1,session.zone)));
        assertFalse(stats.ensureNativeSession(new StatsRepository.NativeSession(session.identity,session.startedAt,"UTC")));
        assertEquals(1,saved().getInt("sessions_count",0));assertEquals(session.startedAt,saved().getLong("active_session_start_ms",0));
    }

    @Test public void missingReceiptDoesNotAllowAnOldSessionToBeRecreated() {
        StatsRepository.NativeSession first=session(),later=session();committedMainOwner(first);
        assertTrue(stats.ensureNativeSession(first));assertTrue(stats.endSessionForIdentity(first.identity,System.currentTimeMillis(),1));
        committedMainOwner(later);assertTrue(stats.ensureNativeSession(later));assertTrue(stats.endSessionForIdentity(later.identity,System.currentTimeMillis(),1));
        assertFalse(stats.ensureNativeSession(first));assertEquals(2,saved().getInt("sessions_count",0));
        assertEquals(2,stats.getSessionHistory().size());
    }

    @Test public void ordinaryLocalSessionsKeepTheirCountersAndVersionedHistory() {
        stats.startSession();String first=stats.activeSessionIdentity();
        assertEquals(2,stats.recordTrackIds(java.util.Arrays.asList(7,7,8),null));stats.endSession();
        assertEquals(1,stats.getSessionHistory().size());assertEquals(2,stats.getSessionHistory().get(0).getBlocks());
        stats.startSession();assertNotEquals(first,stats.activeSessionIdentity());stats.endSession();
        assertEquals(2,saved().getInt("sessions_count",0));assertEquals(2,stats.getSessionHistory().size());
        assertEquals(2,saved().getLong("total_blocks_all_time",0));
    }

    @Test public void mainCommittedThenEndedBeforeStatisticsCreatesOnlyClosedHistory() {
        StatsRepository.NativeSession session=session();committedMainOwner(session);
        failAfter.set(true);assertFalse(stats.finishNativeSession(session,System.currentTimeMillis(),60));
        assertEquals("",stats.activeSessionIdentity());assertTrue(stats.finishNativeSession(session,System.currentTimeMillis(),60));
        assertEquals(1,saved().getInt("sessions_count",0));assertEquals(1,stats.getSessionHistory().size());
        assertEquals(60,saved().getLong("total_session_time",0));assertNull(stats.sessionStamp());
        assertEquals(1,saved().getInt("current_streak",0));
        assertTrue(stats.ensureNativeSession(session));assertEquals("",stats.activeSessionIdentity());
    }
}
