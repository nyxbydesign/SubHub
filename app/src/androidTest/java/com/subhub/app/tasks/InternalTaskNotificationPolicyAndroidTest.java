package com.subhub.app.tasks;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.notifications.OptionalNotificationDispatch;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import java.util.*;
import org.junit.Test;
import static com.subhub.app.notifications.NotificationPreferences.*;
import static org.junit.Assert.*;

/** Owned preference stores and an absent OS notification service. Never posts a real notification. */
public final class InternalTaskNotificationPolicyAndroidTest {
    @Test public void domOnlyDeviceCanPrepareInternalSubNoticesWithoutChangingPhysicalPolicy() throws Exception {
        try (Fixture f=new Fixture()) {
            f.mode(DeviceRolePreferences.Mode.DOM_ONLY);
            assertFalse(enabled(f,Role.SUB,Event.TASKS));
            assertTrue(internalTaskEnabled(f,Role.SUB,Event.TASKS));
            assertEquals(InternalTaskEffectDrain.Notice.RETRY,new InternalTaskNotifications(f).prepare(TaskNoticeLedger.Audience.SUB_TASKS));
            assertEquals(1,f.serviceReads); // Eligible, but no OS service is available; not silently completed.
            f.mode(DeviceRolePreferences.Mode.SUB_ONLY);
            assertFalse(enabled(f,Role.DOM,Event.TASK_REVIEW));
            assertTrue(internalTaskEnabled(f,Role.DOM,Event.TASK_REVIEW));
            assertEquals(InternalTaskEffectDrain.Notice.RETRY,new InternalTaskNotifications(f).prepare(TaskNoticeLedger.Audience.DOM_REVIEW));
        }
    }
    @Test public void internalAudiencesKeepIndependentSavedOptOutsAndRejectUnrelatedEvents() throws Exception {
        try (Fixture f=new Fixture()) {
            f.mode(DeviceRolePreferences.Mode.DOM_ONLY);
            assertTrue(f.getSharedPreferences(STORE,0).edit().putBoolean(key(Role.SUB,Event.TASKS),false).commit());
            assertFalse(internalTaskEnabled(f,Role.SUB,Event.TASKS));assertTrue(internalTaskEnabled(f,Role.DOM,Event.TASK_REVIEW));
            assertEquals(InternalTaskEffectDrain.Notice.SKIP,new InternalTaskNotifications(f).prepare(TaskNoticeLedger.Audience.SUB_TASKS));
            assertFalse(OptionalNotificationDispatch.postInternalTask(f,Role.SUB,Event.TASKS,owner(true),"opted-out",null));
            assertEquals(0,f.serviceReads);
            assertTrue(f.getSharedPreferences(STORE,0).edit().putBoolean(key(Role.DOM,Event.TASK_REVIEW),false).commit());
            assertFalse(internalTaskEnabled(f,Role.DOM,Event.TASK_REVIEW));
            assertFalse(internalTaskEnabled(f,Role.DOM,Event.TASKS));assertFalse(internalTaskEnabled(f,Role.SUB,Event.SESSION_STARTED));
        }
    }
    @Test @SdkSuppress(minSdkVersion=33)
    public void deniedOsPermissionStopsBothPreparationAndPostBeforeServiceAccess() throws Exception {
        try (Fixture f=new Fixture()) {
            f.mode(DeviceRolePreferences.Mode.DOM_ONLY);f.permission=PackageManager.PERMISSION_DENIED;
            assertTrue(internalTaskEnabled(f,Role.SUB,Event.TASKS));
            assertEquals(InternalTaskEffectDrain.Notice.SKIP,new InternalTaskNotifications(f).prepare(TaskNoticeLedger.Audience.SUB_TASKS));
            assertFalse(OptionalNotificationDispatch.postInternalTask(f,Role.SUB,Event.TASKS,owner(true),"denied",null));
            assertEquals(0,f.serviceReads);
        }
    }
    @Test public void revokedGenerationCannotReadChoicesOrEnterPlatformDispatch() throws Exception {
        try (Fixture f=new Fixture()) {
            f.rejectReads=true;
            assertFalse(OptionalNotificationDispatch.postInternalTask(f,Role.SUB,Event.TASKS,owner(false),"retired",null));
            assertEquals(0,f.serviceReads);
        }
    }
    private static OptionalNotificationDispatch.InternalTaskOwner owner(boolean current) {
        return new OptionalNotificationDispatch.InternalTaskOwner(UUID.randomUUID().toString(),()->current);
    }
    private static final class Fixture extends ContextWrapper implements AutoCloseable {
        final String prefix="internal-task-notification-"+UUID.randomUUID()+":";
        final Set<String> stores=new LinkedHashSet<>();int serviceReads,permission=PackageManager.PERMISSION_GRANTED;boolean rejectReads;
        Fixture(){super(InstrumentationRegistry.getInstrumentation().getTargetContext());}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            if(rejectReads)throw new AssertionError("Revoked owner read preferences");
            if(!STORE.equals(name)&&!SettingsRepository.PREFERENCES_NAME.equals(name))throw new AssertionError("Unrelated preferences: "+name);
            stores.add(prefix+name);return getBaseContext().getSharedPreferences(prefix+name,mode);
        }
        @Override public Object getSystemService(String name){
            if(Context.NOTIFICATION_SERVICE.equals(name)){serviceReads++;return null;}
            throw new AssertionError("Unrelated service: "+name);
        }
        @Override public int checkSelfPermission(String name){
            if(!Manifest.permission.POST_NOTIFICATIONS.equals(name))throw new AssertionError("Unrelated permission");return permission;
        }
        void mode(DeviceRolePreferences.Mode mode){
            // Only an owned fixture's preference store; no physical role transition or permission change.
            assertTrue(getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit()
                    .putString(DeviceRolePreferences.KEY_MODE,mode.name()).commit());
        }
        @Override public void close(){for(String name:stores)assertTrue(getBaseContext().deleteSharedPreferences(name));}
    }
}
