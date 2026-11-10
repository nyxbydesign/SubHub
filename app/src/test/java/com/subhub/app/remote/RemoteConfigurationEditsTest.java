package com.subhub.app.remote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteConfigurationEditsTest {
    @Test public void staleEditorPreservesUnrelatedNestedChanges()throws Exception {
        JSONObject base=new JSONObject("{service:{duration:1,hidden:false},apps:{all:false}}");
        JSONObject edited=new JSONObject(base.toString());edited.getJSONObject("service").put("duration",2);
        JSONObject current=new JSONObject(base.toString());current.getJSONObject("service").put("hidden",true);
        JSONObject result=RemoteConfigurationEdits.merge(base,edited,current);
        assertEquals(2,result.getJSONObject("service").getInt("duration"));
        assertTrue(result.getJSONObject("service").getBoolean("hidden"));
        assertEquals(1,base.getJSONObject("service").getInt("duration"));
    }
    @Test public void sameFieldDivergenceIsRejected()throws Exception {
        assertThrows(IllegalStateException.class,()->RemoteConfigurationEdits.merge(new JSONObject("{value:1}"),new JSONObject("{value:2}"),new JSONObject("{value:3}")));
    }
    @Test public void repeatedEditIsIdempotent()throws Exception {
        JSONObject changed=new JSONObject("{value:2}");
        assertTrue(RemoteConfigurationEdits.same(changed,RemoteConfigurationEdits.merge(new JSONObject("{value:1}"),changed,changed)));
    }
    @Test public void deletionsPreserveConcurrentAdditions()throws Exception {
        JSONObject result=RemoteConfigurationEdits.merge(new JSONObject("{allowances:{a:10}}"),new JSONObject("{allowances:{}}"),new JSONObject("{allowances:{a:10,b:20}}"));
        assertFalse(result.getJSONObject("allowances").has("a"));assertEquals(20,result.getJSONObject("allowances").getInt("b"));
    }
    @Test public void arraysCannotSilentlyOverwriteConcurrentSelections()throws Exception {
        assertThrows(IllegalStateException.class,()->RemoteConfigurationEdits.merge(new JSONObject("{packages:['a']}"),new JSONObject("{packages:['a','b']}"),new JSONObject("{packages:['a','c']}")));
    }
    @Test public void unchangedEditorDoesNotUndoNewerSnapshot()throws Exception {
        JSONObject base=new JSONObject("{value:1}");JSONObject current=new JSONObject("{value:2,extra:true}");
        assertTrue(RemoteConfigurationEdits.same(current,RemoteConfigurationEdits.merge(base,base,current)));
    }
}
