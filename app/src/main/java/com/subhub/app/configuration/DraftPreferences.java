package com.subhub.app.configuration;

import android.content.SharedPreferences;
import java.util.*;

/** An editor's in-memory preferences never delegate reads or writes to device stores. */
public final class DraftPreferences implements SharedPreferences {
    private final Map<String,Object> values = new LinkedHashMap<>();
    private final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();
    @Override public synchronized Map<String,?> getAll() {
        Map<String,Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry : values.entrySet()) copy.put(entry.getKey(), copy(entry.getValue()));
        return copy;
    }
    private static Object copy(Object value) { return value instanceof Set ? new LinkedHashSet<>((Set<?>)value) : value; }
    @Override public synchronized String getString(String key,String fallback) { return (String)values.getOrDefault(key,fallback); }
    @Override @SuppressWarnings("unchecked") public synchronized Set<String> getStringSet(String key,Set<String> fallback) {
        Set<String> value=(Set<String>)values.getOrDefault(key,fallback);return value==null?null:new LinkedHashSet<>(value);
    }
    @Override public synchronized int getInt(String key,int fallback) { return (Integer)values.getOrDefault(key,fallback); }
    @Override public synchronized long getLong(String key,long fallback) { return (Long)values.getOrDefault(key,fallback); }
    @Override public synchronized float getFloat(String key,float fallback) { return (Float)values.getOrDefault(key,fallback); }
    @Override public synchronized boolean getBoolean(String key,boolean fallback) { return (Boolean)values.getOrDefault(key,fallback); }
    @Override public synchronized boolean contains(String key) { return values.containsKey(key); }
    @Override public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.add(listener); }
    @Override public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.remove(listener); }
    @Override public Editor edit() { return new Changes(); }
    private final class Changes implements Editor {
        final Map<String,Object> pending=new LinkedHashMap<>();boolean clear;
        public Editor putString(String key,String value){pending.put(key,value);return this;}
        public Editor putStringSet(String key,Set<String> value){pending.put(key,copy(value));return this;}
        public Editor putInt(String key,int value){pending.put(key,value);return this;}
        public Editor putLong(String key,long value){pending.put(key,value);return this;}
        public Editor putFloat(String key,float value){pending.put(key,value);return this;}
        public Editor putBoolean(String key,boolean value){pending.put(key,value);return this;}
        public Editor remove(String key){pending.put(key,null);return this;}
        public Editor clear(){clear=true;return this;}
        public boolean commit(){
            Set<String> changed=new LinkedHashSet<>();Set<OnSharedPreferenceChangeListener> observers;
            synchronized(DraftPreferences.this){
                if(clear){changed.addAll(values.keySet());values.clear();}
                for(Map.Entry<String,Object> entry:pending.entrySet()){
                    if(!Objects.equals(values.get(entry.getKey()),entry.getValue()))changed.add(entry.getKey());
                    if(entry.getValue()==null)values.remove(entry.getKey());else values.put(entry.getKey(),copy(entry.getValue()));
                }
                observers=new HashSet<>(listeners);
            }
            for(String key:changed)for(OnSharedPreferenceChangeListener observer:observers)observer.onSharedPreferenceChanged(DraftPreferences.this,key);
            return true;
        }
        public void apply(){commit();}
    }
}
