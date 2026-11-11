package com.subhub.app.stats;

import android.content.Context;
import com.subhub.app.people.*;
import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.security.ControllerPinManager;
import java.util.*;

/** Only immutable, same-owner observations. No LOCAL repository, remote endpoint or effect replay. */
final class InternalStatsSource implements DailyStatsPanel.Source {
    static final class Loaded {
        final InternalRuntimePresentationSnapshot snapshot;
        final PersonSnapshot person;
        Loaded(InternalRuntimePresentationSnapshot snapshot, PersonSnapshot person) { this.snapshot=snapshot; this.person=person; }
    }
    private final Context physical;
    private final InternalSubData data;
    private final String generation;
    private final long controller;
    private volatile Loaded published;
    InternalStatsSource(Context context) {
        physical=context.getApplicationContext(); data=Objects.requireNonNull(InternalSubData.peek(physical), "Internal data unavailable");
        generation=data.acknowledged().generation; controller=ControllerPinManager.domWorkspaceRevision(); requireAccess();
    }
    void requireAccess() {
        if (data!=InternalSubData.peek(physical)||!DummySubPreview.current(physical,generation,controller))
            throw new SecurityException("Internal statistics authority changed");
    }
    Loaded read() {
        requireAccess(); InternalSubRecord record=data.acknowledged();
        InternalRuntimePresentationSnapshot snapshot=data.loadRuntimePresentation(physical,record);
        PersonSnapshot person=InternalPersonPresentation.snapshot(physical,snapshot);
        requireAccess(); return new Loaded(snapshot,person);
    }
    boolean publish(Loaded value) {
        requireAccess();
        if (!matches(value)) return false;
        published=value; return true;
    }
    Loaded peek() { Loaded value=published; return current(value)?value:null; }
    void clear() { published=null; }
    private boolean current(Loaded value) {
        try {
            requireAccess();
            return value!=null&&value==published&&matches(value);
        } catch (RuntimeException unavailable) { return false; }
    }
    @Override public boolean accessible() { return current(published); }
    @Override public Object observationKey() { Loaded value=peek(); return value==null?null:value.snapshot; }
    private boolean matches(Loaded value) {
        return value.snapshot.matches(data.acknowledged(),data.presentationRevisionHint())
                &&InternalPresentationCalendar.current(value.snapshot.observed,OwnerObservation.capture(System.currentTimeMillis()));
    }
    @Override public java.time.LocalDate observationDay(Object key) {
        Loaded value=peek();
        if(value==null||key!=value.snapshot)throw new IllegalStateException("Internal calendar observation unavailable");
        return InternalPresentationCalendar.day(value.snapshot.observed);
    }
    @Override public DailyStatsPanel.DailyData load(String first,String last) throws Exception {
        requireAccess(); Loaded value=peek();
        if(value==null)throw new java.io.IOException("Internal observation unavailable");
        Map<String,Map<String,Long>> days=new TreeMap<>(); String since=null;
        for(Map.Entry<String,Map<String,Long>> day:value.snapshot.accounting.days().entrySet()) {
            if(since==null||day.getKey().compareTo(since)<0)since=day.getKey();
            if(day.getKey().compareTo(first)>=0&&day.getKey().compareTo(last)<=0)days.put(day.getKey(),day.getValue());
        }
        if(since==null)throw new java.io.IOException("No recorded internal daily history");
        requireAccess();
        if(published!=value||!accessible())throw new java.io.IOException("Internal history observation changed");
        return new DailyStatsPanel.DailyData(since,Collections.unmodifiableMap(days));
    }
}
