package com.subhub.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.test.runner.AndroidJUnitRunner;

import com.subhub.app.security.ControllerPinManager;

/** Keeps UI tests focused on their screen contracts while PIN behavior is tested separately. */
public final class SubHubTestRunner extends AndroidJUnitRunner {
    private static final String TEST_PIN = "2468";

    @Override public void onStart() {
        Application application = (Application) getTargetContext().getApplicationContext();
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPreCreated(Activity activity, Bundle state) {
                authorize(activity);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
        authorize(application);
        super.onStart();
    }

    private static void authorize(android.content.Context context) {
        if (!ControllerPinManager.hasCredentials(context) && !ControllerPinManager.allowsUnkeyedAccess(context)) {
            boolean intendedDom = ControllerPinManager.isDomModeActive();
            if (!ControllerPinManager.setPin(context, TEST_PIN)) {
                throw new IllegalStateException("Controller PIN test fixture could not be prepared");
            }
            if (!intendedDom) ControllerPinManager.enterSubMode();
        }
    }
}
