package com.subhub.app.storage;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import org.junit.Test;

public class PayloadCodecTest {
    private static final String FACT = "{\"schema\":1,\"kind\":\"APP_OPENED\",\"packageName\":\"com.example.selected\",\"zone\":\"Europe/Berlin\",\"day\":\"2026-01-01\",\"configuration\":\"\",\"id\":\"app_11111111-2222-3333-4444-555555555555\",\"observedAt\":1767225600000,\"amountCents\":0,\"currency\":\"\",\"reason\":\"\",\"limitMinutes\":0}";
    private static final String V1 = "U0haAQAAAQh4P6HWks9VksEKgzAQRP9lz1lJtjVCfkZKyaGgKHgplP67L43FOOSQzC7sZmY+Uj8mKTgpjkiSUzxx0rhC6bnMXX6z3JS7LU8/JWgqLlC9KAaNL7DmLaoPHKiLTRShXmXmY13HcEAN6A3oHWjfgP4zMiw9xMGsj77ASRMbSbz/0amDan7qvQ0Rrd8dxOtNrA==";
    @Test public void existingVersionOneFixtureDecodesWithoutWriterAssumptions() {
        assertEquals(FACT,PayloadCodec.decompress(Base64.getDecoder().decode(V1)));
    }
    @Test public void ordinaryActivityAndUnicodeRoundTripExactly() {
        for(String value:new String[]{FACT,FACT+repeat(" \u00e4\u65e5\ud83d\ude00",100)}) {
            byte[] packed=PayloadCodec.compressIfUseful(value);assertNotNull(packed);
            assertEquals(value,PayloadCodec.decompress(packed));
            assertTrue(packed.length<value.getBytes(StandardCharsets.UTF_8).length);
        }
    }
    @Test public void tinyAndIncompressibleValuesKeepTheirOriginalText() {
        assertNull(PayloadCodec.compressIfUseful("{}"));
        Random random=new Random(44);StringBuilder raw=new StringBuilder();
        for(int i=0;i<200;i++)raw.append((char)(33+random.nextInt(94)));
        assertNull(PayloadCodec.compressIfUseful(raw.toString()));
    }
    @Test public void oversizedLegacyTextIsNotRewritten() {
        char[] raw=new char[PayloadCodec.MAX_DECODED_BYTES+1];Arrays.fill(raw,'a');
        assertNull(PayloadCodec.compressIfUseful(new String(raw)));
    }
    @Test public void malformedLegacySurrogatesStayPlainInsteadOfBeingReplaced() {
        assertNull(PayloadCodec.compressIfUseful(FACT + '\ud800'));
        assertNull(PayloadCodec.compressIfUseful(FACT + '\udc00'));
    }
    @Test public void everyTruncatedEncodingFailsInsteadOfProducingPartialHistory() {
        byte[] bytes=Base64.getDecoder().decode(V1);
        for(int n=0;n<bytes.length;n++){byte[] truncated=Arrays.copyOf(bytes,n);assertThrows(IllegalStateException.class,()->PayloadCodec.decompress(truncated));}
    }
    @Test public void corruptDictionaryOrChecksumCannotBecomeEmptyFacts() {
        byte[] dict=Base64.getDecoder().decode(V1);dict[11]^=1;
        assertThrows(IllegalStateException.class,()->PayloadCodec.decompress(dict));
        byte[] checksum=Base64.getDecoder().decode(V1);checksum[checksum.length-1]^=1;
        assertThrows(IllegalStateException.class,()->PayloadCodec.decompress(checksum));
    }
    @Test public void unknownFormatsAndTrailingBytesAreRejected() {
        byte[] newer=Base64.getDecoder().decode(V1);newer[3]=2;
        assertThrows(IllegalStateException.class,()->PayloadCodec.decompress(newer));
        byte[] trailing=Arrays.copyOf(Base64.getDecoder().decode(V1),Base64.getDecoder().decode(V1).length+1);
        assertThrows(IllegalStateException.class,()->PayloadCodec.decompress(trailing));
    }
    @Test public void declaredLengthCannotAllocateUnboundedMemoryOrHideOverflow() {
        for(int length:new int[]{-1,Integer.MAX_VALUE,0,FACT.length()-1,FACT.length()+1}) {
            byte[] bytes=Base64.getDecoder().decode(V1);ByteBuffer.wrap(bytes,4,4).putInt(length);
            assertThrows(IllegalStateException.class,()->PayloadCodec.decompress(bytes));
        }
    }
    private static String repeat(String value,int count){StringBuilder text=new StringBuilder();for(int i=0;i<count;i++)text.append(value);return text.toString();}
}
