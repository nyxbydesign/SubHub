package com.subhub.app.remote;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class LinkingStructuralReservationTest {
    @Test public void fixedCanonicalVectorsBindTheCompleteBeforeImageAndNullScope() {
        LinkingStructuralReservation scoped = reservation(repeat('f',64));
        assertEquals(470,scoped.signingBytes().length);
        // Independently serialized big-endian, length-prefixed reference vectors.
        assertEquals("5cca68b0b55cbb387de0acf2448b706202bc904ea40c0b40baeeb277da344aa1",scoped.command);
        LinkingStructuralReservation restored = LinkingStructuralReservation.decode(scoped.signingBytes());
        assertArrayEquals(scoped.signingBytes(),restored.signingBytes());
        assertEquals(scoped.command,restored.command);assertEquals(7,restored.oldRevision);assertEquals(8,restored.nextRevision);
        assertEquals(2,restored.readyRevision);assertEquals(repeat('f',64),restored.snapshotScope);
        assertTrue(restored.forDatabase(repeat('a',64)));assertFalse(restored.forDatabase(repeat('0',64)));
        LinkingStructuralReservation unscoped = reservation(null);
        assertEquals(406,unscoped.signingBytes().length);assertNull(LinkingStructuralReservation.decode(unscoped.signingBytes()).snapshotScope);
        assertEquals("4f7ac385da0ef308632b7986395f62633083042fd40c425dd1eb56043d832de9",unscoped.command);
        byte[] detached=scoped.signingBytes();detached[0]^=1;
        assertEquals("5cca68b0b55cbb387de0acf2448b706202bc904ea40c0b40baeeb277da344aa1",scoped.command);
        assertArrayEquals(restored.signingBytes(),scoped.signingBytes());
        String zero=repeat('0',64),scope=repeat('f',64);
        for(LinkingStructuralReservation different:new LinkingStructuralReservation[]{
                new LinkingStructuralReservation(zero,repeat('b',32),7,repeat('c',64),repeat('d',64),repeat('e',64),true,2,scope,1),
                new LinkingStructuralReservation(repeat('a',64),repeat('0',32),7,repeat('c',64),repeat('d',64),repeat('e',64),true,2,scope,1),
                new LinkingStructuralReservation(repeat('a',64),repeat('b',32),8,repeat('c',64),repeat('d',64),repeat('e',64),true,2,scope,1),
                new LinkingStructuralReservation(repeat('a',64),repeat('b',32),7,zero,repeat('d',64),repeat('e',64),true,2,scope,1),
                new LinkingStructuralReservation(repeat('a',64),repeat('b',32),7,repeat('c',64),zero,repeat('e',64),true,2,scope,1),
                new LinkingStructuralReservation(repeat('a',64),repeat('b',32),7,repeat('c',64),repeat('d',64),zero,true,2,scope,1),
                new LinkingStructuralReservation(repeat('a',64),repeat('b',32),7,repeat('c',64),repeat('d',64),repeat('e',64),true,3,scope,1),
                unscoped})assertNotEquals(scoped.command,different.command);
    }

    @Test public void malformedVersionsTypesBoundsAndTrailingDataCannotBecomeReservations() {
        byte[] bytes=reservation(repeat('f',64)).signingBytes();
        assertThrows(IllegalArgumentException.class,()->LinkingStructuralReservation.decode(null));
        assertThrows(IllegalArgumentException.class,()->LinkingStructuralReservation.decode(new byte[LinkingStructuralReservation.MAX_BYTES+1]));
        assertThrows(IllegalArgumentException.class,()->LinkingStructuralReservation.decode(Arrays.copyOf(bytes,bytes.length-1)));
        assertThrows(IllegalArgumentException.class,()->LinkingStructuralReservation.decode(Arrays.copyOf(bytes,bytes.length+1)));
        int versions=4+"subhub.linking.structural-coverage.v1".getBytes(StandardCharsets.US_ASCII).length;
        for(int offset:new int[]{versions,versions+4,versions+8,versions+12,versions+16}){
            byte[] unknown=bytes.clone();ByteBuffer.wrap(unknown).putInt(offset,99);
            assertThrows(IllegalArgumentException.class,()->LinkingStructuralReservation.decode(unknown));
        }
        byte[] wrongNext=bytes.clone();ByteBuffer.wrap(wrongNext).putLong(bytes.length-8,9);
        assertThrows(IllegalArgumentException.class,()->LinkingStructuralReservation.decode(wrongNext));
        byte[] incomplete=bytes.clone();ByteBuffer.wrap(incomplete).putLong(bytes.length-16,0);
        assertThrows(IllegalArgumentException.class,()->LinkingStructuralReservation.decode(incomplete));
        int initialized=versions+20+68+36+8+3*68;
        byte[] notBoolean=bytes.clone();notBoolean[initialized]=2;
        assertThrows(IllegalArgumentException.class,()->LinkingStructuralReservation.decode(notBoolean));
        byte[] emptyScope=reservation(null).signingBytes();ByteBuffer.wrap(emptyScope).putInt(initialized+1+8,0);
        assertThrows(IllegalArgumentException.class,()->LinkingStructuralReservation.decode(emptyScope));
        assertThrows(IllegalArgumentException.class,()->new LinkingStructuralReservation(repeat('a',64),repeat('b',32),7,
                repeat('c',64),repeat('d',64),repeat('e',64),false,2,null,1));
        assertThrows(IllegalArgumentException.class,()->new LinkingStructuralReservation(repeat('a',64),repeat('b',32),7,
                repeat('c',64),repeat('d',64),repeat('e',64),true,0,null,1));
        assertThrows(IllegalArgumentException.class,()->new LinkingStructuralReservation(repeat('a',64),repeat('b',32),7,
                repeat('c',64),repeat('d',64),repeat('e',64),true,8,null,1));
        assertThrows(IllegalArgumentException.class,()->new LinkingStructuralReservation(repeat('a',64),repeat('b',32),7,
                repeat('c',64),repeat('d',64),repeat('e',64),true,2,"",1));
        assertThrows(IllegalArgumentException.class,()->new LinkingStructuralReservation(repeat('A',64),repeat('b',32),7,
                repeat('c',64),repeat('d',64),repeat('e',64),true,2,null,1));
        assertThrows(ArithmeticException.class,()->new LinkingStructuralReservation(repeat('a',64),repeat('b',32),Long.MAX_VALUE,
                repeat('c',64),repeat('d',64),repeat('e',64),true,2,null,1));
    }

    private static LinkingStructuralReservation reservation(String scope) {
        return new LinkingStructuralReservation(repeat('a',64),repeat('b',32),7,
                repeat('c',64),repeat('d',64),repeat('e',64),true,2,scope,1);
    }
    private static String repeat(char value,int count){return new String(new char[count]).replace('\0',value);}
}
