package com.subhub.app.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import androidx.annotation.WorkerThread;

import com.subhub.app.MainActivity;
import com.subhub.app.R;

import java.util.Arrays;

/** Dynamic launcher shortcuts routed through the exported main activity. */
public final class AppShortcuts {
    public static final String ACTION_START_PROTECTION = "com.subhub.app.action.SHORTCUT_PROTECT";

    private AppShortcuts() {}

    public static void install(Context context) {
        Context application = context.getApplicationContext();
        new Thread(() -> installOnWorker(application), "SubHub-shortcuts").start();
    }

    @WorkerThread
    private static void installOnWorker(Context context) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        Intent protection = new Intent(context, MainActivity.class)
                .setAction(ACTION_START_PROTECTION);
        ShortcutInfo start = new ShortcutInfo.Builder(context, "start_protection")
                .setShortLabel(context.getString(R.string.shortcut_start_protection))
                .setLongLabel(context.getString(R.string.shortcut_start_protection_long))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher_foreground))
                .setIntent(protection)
                .build();
        manager.setDynamicShortcuts(Arrays.asList(start));
    }

    public static void reportUsed(Context context, String id) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager != null) manager.reportShortcutUsed(id);
    }
}
