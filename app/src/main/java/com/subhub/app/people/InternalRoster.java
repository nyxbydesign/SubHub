package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.internal.InternalSubRecord;
import java.util.*;

/** UI composition only. Internal membership never creates a relationship or a Dom aggregate subject. */
public final class InternalRoster {
    private InternalRoster() { }
    public static final class Rows extends ArrayList<PersonSnapshot> {
        public final String generation;
        private Rows(List<PersonSnapshot> relationships,String generation){super(relationships);this.generation=generation;}
    }
    public static Rows append(Context physical,List<PersonSnapshot> relationships,boolean summary){
        InternalSubData.loadIfProvisioned(physical); // Established corruption is unavailable, never an absent profile.
        InternalSubData data=InternalSubData.peek(physical);
        InternalSubRecord record=data==null?null:data.acknowledged();
        boolean visible=record!=null&&record.visible&&DummySubPreview.isEnabled(physical);
        Rows result=new Rows(relationships,visible?record.generation:"");
        if(visible){
            for(PersonSnapshot row:result)if(DummySubPreview.ID.equals(row.id))throw new IllegalStateException("Duplicate internal target");
            result.add(summary?InternalPersonPresentation.summary(physical,record):data.loadPresentation(physical,record));
        }
        return result;
    }
    public static String generation(Context physical){
        InternalSubData data=InternalSubData.peek(physical);
        return data!=null&&DummySubPreview.isEnabled(physical)?data.acknowledged().generation:"";
    }
    public static boolean current(Context physical,String expected){return Objects.equals(expected,generation(physical));}
}
