package com.subhub.app.relationships;

import static org.junit.Assert.*;
import org.junit.Test;

public final class RelationshipPreparationKeyTest {
    private static final String EPOCH="0123456789abcdef0123456789abcdef";
    @Test public void retainsExactOriginalRevisionAndUsesDistinctNoncredentialNonces(){
        for(String number:new String[]{"0","1",Long.toString(Long.MAX_VALUE)}){
            String revision="fr1:"+EPOCH+":"+number;
            String first=RelationshipPreparationKey.create(revision),second=RelationshipPreparationKey.create(revision);
            assertTrue(RelationshipPreparationKey.matches(first,revision));assertNotEquals(first,second);
            assertEquals("fr1."+EPOCH+"."+number+".",first.substring(0,first.length()-32));assertTrue(first.length()<=89);
            assertFalse(RelationshipPreparationKey.matches(first,revision+"0"));
        }
    }
    @Test public void refusesNoncanonicalOverflowForeignAndControlInputs(){
        for(String revision:new String[]{null,"", "fr1:"+EPOCH+":01","fr1:"+EPOCH+":-1","fr1:"+EPOCH+":+1",
                "fr1:"+EPOCH+":9223372036854775808","fr1:"+EPOCH.toUpperCase()+":1","fr1:"+EPOCH+":1 ",
                "fr1:"+EPOCH+":1\n","fr1:"+EPOCH+":1:extra","internal:1"}){
            assertThrows(IllegalArgumentException.class,()->RelationshipPreparationKey.create(revision));
            assertFalse(RelationshipPreparationKey.matches("legacy",revision));
        }
        String revision="fr1:"+EPOCH+":1",key=RelationshipPreparationKey.create(revision);
        assertFalse(RelationshipPreparationKey.matches(null,revision));assertFalse(RelationshipPreparationKey.matches(key+"\n",revision));
        assertFalse(RelationshipPreparationKey.matches(key.toUpperCase(),revision));assertFalse(RelationshipPreparationKey.matches(key,"fr1:"+EPOCH+":2"));
    }
}
