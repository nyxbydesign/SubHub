package com.subhub.app.privacy;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.*;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.subhub.app.R;
import java.util.*;

/** App-entry lock is independent of Dom state and never touches background services. */
public final class PrivacyLifecycle implements Application.ActivityLifecycleCallbacks {
    private static PrivacyLifecycle instance;
    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Activity, View> covers = new WeakHashMap<>();
    private final Map<View, Integer> accessibility = new WeakHashMap<>();
    private final Set<Activity> started = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean unlocked, gateShowing;
    private final Runnable relock = () -> { unlocked = false; refreshWindows(); };
    private PrivacyLifecycle(Application app) { this.app = app; }
    public static void install(Application app) {
        if (instance != null) return;
        instance = new PrivacyLifecycle(app); app.registerActivityLifecycleCallbacks(instance);
        new PrivacyManager(app).reconcileLauncher();
    }
    public static void unlock() {
        if (instance == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { instance.main.post(PrivacyLifecycle::unlock); return; }
        instance.main.removeCallbacks(instance.relock); instance.unlocked = true; instance.gateShowing = false;
        instance.refreshWindows();
    }
    public static void refresh() { if (instance != null) instance.main.post(instance::refreshWindows); }
    static boolean locked() { return instance != null && new PrivacyManager(instance.app).isAppLockEnabled() && !instance.unlocked; }
    private void secure(Activity activity) {
        PrivacyManager privacy = new PrivacyManager(app);
        boolean sensitive = activity instanceof com.subhub.app.security.AuthenticatorActivity || activity instanceof AppUnlockActivity;
        if (privacy.isDiscreet() || privacy.isAppLockEnabled() || sensitive) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!privacy.isDiscreet() && !privacy.isAppLockEnabled() && !sensitive);
    }
    private void cover(Activity activity) {
        secure(activity);
        if (activity instanceof AppUnlockActivity) return;
        boolean needsCover = locked(); View previous = covers.get(activity);
        if (previous != null && previous.getParent() == null) previous = null;
        if (!needsCover) {
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content != null) for (int i = 0; i < content.getChildCount(); i++) {
                View child = content.getChildAt(i);
                Integer original = accessibility.remove(child);
                if (original != null) child.setImportantForAccessibility(original);
            }
            if (previous != null && previous.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) previous.getParent()).removeView(previous);
            covers.put(activity, null); return;
        }
        if (previous == null) {
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            for (int i = 0; i < content.getChildCount(); i++) {
                View child = content.getChildAt(i);
                accessibility.put(child, child.getImportantForAccessibility());
                child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            View shield = new View(activity); shield.setBackgroundColor(activity.getColor(R.color.background));
            shield.setClickable(true); shield.setFocusableInTouchMode(true); shield.setContentDescription(activity.getString(R.string.privacy_locked));
            shield.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            content.addView(shield, new FrameLayout.LayoutParams(-1, -1)); shield.requestFocus(); covers.put(activity, shield);
        }
    }
    private void refreshWindows() { for (Activity activity : new ArrayList<>(covers.keySet())) if (activity != null && !activity.isDestroyed()) cover(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { covers.put(activity, null); cover(activity); }
    @Override public void onActivityStarted(Activity activity) {
        main.removeCallbacks(relock); started.add(activity); cover(activity);
        if (!(activity instanceof AppUnlockActivity) && locked()) {
            gateShowing = true;
            activity.startActivity(new Intent(activity, AppUnlockActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
    }
    @Override public void onActivityResumed(Activity activity) { cover(activity); }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) {
        started.remove(activity);
        if (started.isEmpty() && !activity.isChangingConfigurations()) {
            // A completed credential gate hands straight back to the covered Activity.
            // Every other exit locks immediately; a quick return must not cancel relocking.
            if (activity instanceof AppUnlockActivity && activity.isFinishing() && unlocked)
                main.postDelayed(relock, 300);
            else relock.run();
        }
    }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) {
        covers.remove(activity); started.remove(activity);
        if (activity instanceof AppUnlockActivity) gateShowing = false;
    }
}
