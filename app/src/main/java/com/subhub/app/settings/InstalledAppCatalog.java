package com.subhub.app.settings;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.*;

/** Application-only app discovery; never builds rows or retains a settings page. */
final class InstalledAppCatalog {
    static Set<String> packageNames(Context app) {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> names = new LinkedHashSet<>();
        for (ResolveInfo info : app.getPackageManager().queryIntentActivities(launcher, PackageManager.MATCH_ALL))
            if (info.activityInfo != null && !app.getPackageName().equals(info.activityInfo.packageName))
                names.add(info.activityInfo.packageName);
        return names;
    }
    static List<Entry> load(Context app) throws InterruptedException {
        PackageManager packages = app.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        @SuppressWarnings("deprecation")
        List<ResolveInfo> resolved =
                packages.queryIntentActivities(launcher, PackageManager.MATCH_ALL);
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (info.activityInfo == null
                    || app.getPackageName().equals(info.activityInfo.packageName)) continue;
            String packageName = info.activityInfo.packageName;
            if (unique.containsKey(packageName)) continue;
            CharSequence label = info.loadLabel(packages);
            Drawable icon;
            try {
                icon = info.loadIcon(packages);
            } catch (RuntimeException removed) {
                icon = packages.getDefaultActivityIcon();
            }
            unique.put(
                    packageName,
                    new Entry(label == null ? packageName : label.toString(), packageName, icon));
        }
        List<Entry> entries = new ArrayList<>(unique.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        entries.sort((left, right) -> collator.compare(left.label, right.label));
        return Collections.unmodifiableList(entries);
    }

    static final class Entry {
        final String label, packageName;
        final Drawable icon;

        Entry(String label, String packageName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }
}
