package com.subhub.app.internal;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Explicit editor data adapter, NOT a security sandbox or a runtime effects Context. Bundled
 * assets/resources and PackageManager catalog reads remain available. Activity/service tokens,
 * URI imports, OS effects, singleton repositories, remote transport and payment managers require
 * separate audited adapters; passing this wrapper cannot make them safe. Real controller authority
 * must be observed on the physical context, never synthesized from these configuration preferences.
 */
public final class InternalSubContext extends ContextWrapper {
    private final InternalStorageScope scope;
    private final InternalConfigurationStore.EditorDraft draft;

    public InternalSubContext(Context resources, InternalStorageScope scope,
            InternalConfigurationStore.EditorDraft draft) {
        super(Objects.requireNonNull(resources).getApplicationContext());
        if (resources instanceof InternalSubContext) throw new IllegalArgumentException("Already scoped");
        this.scope = Objects.requireNonNull(scope); this.draft = Objects.requireNonNull(draft);
    }
    public String targetId() { return scope.targetId(); }
    public InternalStorageScope storageScope() { return scope; }
    @Override public Context getApplicationContext() { return this; }
    @Override public Context getBaseContext() { throw effects(); }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        if (mode != MODE_PRIVATE) throw new SecurityException("Internal preferences are private");
        return draft.preferences(name);
    }
    @Override public boolean deleteSharedPreferences(String name) { throw effects(); }
    @Override public boolean moveSharedPreferencesFrom(Context source, String name) { throw effects(); }
    @Override public boolean moveDatabaseFrom(Context source, String name) { throw effects(); }
    @Override public Context createDeviceProtectedStorageContext() { throw effects(); }
    @Override public Context createPackageContext(String name, int flags) { throw effects(); }
    @Override public Context createConfigurationContext(android.content.res.Configuration configuration) { throw effects(); }
    @Override public Context createDisplayContext(android.view.Display display) { throw effects(); }
    @Override public Context createContextForSplit(String name) { throw effects(); }

    private File directory(InternalStorageScope.Area area) {
        try { return scope.directory(area); }
        catch (IOException unavailable) { throw new IllegalStateException("Internal storage unavailable", unavailable); }
    }
    @Override public File getFilesDir() { return directory(InternalStorageScope.Area.FILES); }
    @Override public File getNoBackupFilesDir() { return getFilesDir(); }
    @Override public File getCacheDir() { return directory(InternalStorageScope.Area.CACHE); }
    @Override public File getCodeCacheDir() { throw effects(); }
    @Override public File getDataDir() { throw effects(); }
    @Override public File getDir(String name, int mode) {
        if (mode != MODE_PRIVATE) throw new SecurityException("Internal files are private");
        switch (name) {
            case "assets": return directory(InternalStorageScope.Area.ASSETS);
            case "tasks": return directory(InternalStorageScope.Area.TASKS);
            case "history": return directory(InternalStorageScope.Area.HISTORY);
            default: throw new SecurityException("Use an explicit internal storage area");
        }
    }
    @Override public File getFileStreamPath(String name) {
        try { return scope.file(InternalStorageScope.Area.FILES, name); }
        catch (IOException unavailable) { throw new IllegalStateException("Internal file unavailable", unavailable); }
    }
    @Override public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return new FileInputStream(getFileStreamPath(name));
    }
    @Override public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        if (mode != MODE_PRIVATE && mode != MODE_APPEND) throw new SecurityException("Internal files are private");
        return new FileOutputStream(getFileStreamPath(name), mode == MODE_APPEND);
    }
    @Override public boolean deleteFile(String name) { return getFileStreamPath(name).delete(); }
    @Override public String[] fileList() {
        String[] files = getFilesDir().list();
        if (files == null) throw new IllegalStateException("Internal files unavailable");
        return files;
    }

    // Database callers must choose a scoped backend explicitly. This also excludes the global owner journal.
    @Override public File getDatabasePath(String name) { throw effects(); }
    @Override public String[] databaseList() { throw effects(); }
    @Override public boolean deleteDatabase(String name) { throw effects(); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) { throw effects(); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
            DatabaseErrorHandler errorHandler) { throw effects(); }
    @Override public ContentResolver getContentResolver() { throw effects(); }
    @Override public Object getSystemService(String name) { throw effects(); }
    @Override public File getExternalFilesDir(String type) { throw effects(); }
    @Override public File[] getExternalFilesDirs(String type) { throw effects(); }
    @Override public File getExternalCacheDir() { throw effects(); }
    @Override public File[] getExternalCacheDirs() { throw effects(); }
    @Override public File[] getExternalMediaDirs() { throw effects(); }
    @Override public File getObbDir() { throw effects(); }
    @Override public File[] getObbDirs() { throw effects(); }
    @Override public ComponentName startService(Intent intent) { throw effects(); }
    @Override public ComponentName startForegroundService(Intent intent) { throw effects(); }
    @Override public boolean stopService(Intent intent) { throw effects(); }
    @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) { throw effects(); }
    @Override public void unbindService(ServiceConnection connection) { throw effects(); }
    @Override public void startActivity(Intent intent) { throw effects(); }
    @Override public void startActivity(Intent intent, Bundle options) { throw effects(); }
    @Override public void startActivities(Intent[] intents) { throw effects(); }
    @Override public void startActivities(Intent[] intents, Bundle options) { throw effects(); }
    @Override public void sendBroadcast(Intent intent) { throw effects(); }
    @Override public void sendBroadcast(Intent intent, String permission) { throw effects(); }

    private static SecurityException effects() {
        return new SecurityException("Internal storage requires a separate platform/effect adapter");
    }
}
