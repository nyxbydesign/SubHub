package com.subhub.app.stats;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import java.util.concurrent.*;

/** Cumulative daily Limits checkpoints retain observed dates and survive duplicate publication. */
public final class DailyAppUsageStore extends SQLiteOpenHelper {
    private static DailyAppUsageStore instance;
    private final Map<String,Entry> pending=new LinkedHashMap<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"subhub-daily-app-usage"));
    private boolean scheduled;
    public static synchronized DailyAppUsageStore get(Context context) {
        if(instance==null)instance=new DailyAppUsageStore(context,"daily_app_usage.db");return instance;
    }
    public DailyAppUsageStore(Context context,String name){super(context.getApplicationContext(),name,null,1);setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE usage(epoch TEXT NOT NULL,day TEXT NOT NULL,zone TEXT NOT NULL,package TEXT NOT NULL,value INTEGER NOT NULL,PRIMARY KEY(epoch,package))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Unsupported app usage schema");}
    public static final class Entry {
        public final String epoch,day,zone,packageName;public final long millis;
        public Entry(String epoch,String day,String zone,String packageName,long millis) {
            if(!UUID.fromString(epoch).toString().equals(epoch) || !java.time.LocalDate.parse(day).toString().equals(day) || millis<0)throw new IllegalArgumentException("Invalid usage checkpoint");
            if(!zone.isEmpty())java.time.ZoneId.of(zone);
            if(!packageName.isEmpty() && (packageName.length()>255 || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")))throw new IllegalArgumentException("Invalid app identity");
            this.epoch=epoch;this.day=day;this.zone=zone;this.packageName=packageName;this.millis=millis;
        }
    }
    public synchronized void queue(Entry entry) {
        String key=entry.epoch+":"+entry.packageName;Entry old=pending.get(key);
        if(old==null || old.millis<entry.millis)pending.put(key,entry);
        if(!scheduled){scheduled=true;worker.schedule(this::drain,5,TimeUnit.SECONDS);}
    }
    private void drain() {
        Map<String,Entry> batch;
        synchronized(this){batch=new LinkedHashMap<>(pending);pending.clear();scheduled=false;}
        try{checkpoint(batch.values());}
        catch(RuntimeException unavailable){synchronized(this){for(Entry entry:batch.values())queue(entry);}android.util.Log.w("DailyAppUsage","Usage checkpoint pending");}
    }
    public void flush() throws Exception {worker.submit(this::drain).get(10,TimeUnit.SECONDS);}
    /** At day rollover this completes before the source preferences discard their old counters. */
    public synchronized void checkpoint(Collection<Entry> entries) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            for(Entry entry:entries) {
                try(Cursor c=db.rawQuery("SELECT day,zone FROM usage WHERE epoch=? LIMIT 1",new String[]{entry.epoch})) {
                    if(c.moveToFirst() && (!c.getString(0).equals(entry.day) || !c.getString(1).equals(entry.zone)))throw new IllegalArgumentException("Usage epoch changed date");
                }
                db.execSQL("INSERT OR IGNORE INTO usage VALUES(?,?,?,?,0)",new Object[]{entry.epoch,entry.day,entry.zone,entry.packageName});
                db.execSQL("UPDATE usage SET value=MAX(value,?) WHERE epoch=? AND package=?",new Object[]{entry.millis,entry.epoch,entry.packageName});
            }
            db.setTransactionSuccessful();
        } finally{db.endTransaction();}
    }
    public synchronized List<Entry> entries() {
        List<Entry> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT epoch,day,zone,package,value FROM usage ORDER BY day,epoch,package",null)) {
            while(c.moveToNext())result.add(new Entry(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4)));
        }
        return result;
    }
    @Override public synchronized void close(){worker.shutdownNow();super.close();}
}
