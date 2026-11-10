package com.subhub.app.remote;

import static org.junit.Assert.*;
import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class RemoteSectionTransferTest {
    @Test public void oneFeatureChangesAndBothStreamDirectionsReconstructExactReceiptedBatch() throws Exception {
        JSONObject before=configuration(),after=changed(before);
        for(RemoteProtocol.Stream stream:new RemoteProtocol.Stream[]{RemoteProtocol.Stream.CONFIGURATION,RemoteProtocol.Stream.BASELINE}) {
            RemoteProtocol.Batch batch=batch(stream,after);
            RemoteSectionTransfer.EncodedOffer encoded=RemoteSectionTransfer.encodeOffer(batch,before);
            JSONObject offer=RemoteProtocol.read(new ByteArrayInputStream(encoded.encoded));
            assertEquals(RemoteProtocol.canonical(encoded.frame),RemoteProtocol.canonical(offer));
            assertEquals(batch.digest(),RemoteProtocol.Batch.parse(RemoteProtocol.read(new ByteArrayInputStream(encoded.fullEncoded))).digest());
            assertEquals("section_batch",offer.getString("type"));
            JSONObject row=offer.getJSONArray("changes").getJSONObject(0);
            assertEquals(ConfigurationSchema.SECTIONS.size()-1,row.getJSONObject("sectionRefs").length());
            assertEquals(batch.digest(),RemoteSectionTransfer.restore(offer,"generation",stream.writer,before).digest());
            assertEquals(batch.digest(),new RemoteProtocol.Ack(RemoteSectionTransfer.restore(offer,"generation",stream.writer,before)).digest);
            assertEquals(RemoteProtocol.canonical(after),RemoteProtocol.canonical(configurationBody(stream,
                    new JSONObject(RemoteSectionTransfer.restore(offer,"generation",stream.writer,before).changes.get(0).body))));
            System.out.println("section-transfer "+stream+" fullWireBytes="+wire(batch.json())+" deltaWireBytes="+wire(offer)+" extraRoundTrips=0");
        }
    }

    @Test public void staleOrMissingBasisRequestsOnlyBoundCompleteBatch() throws Exception {
        JSONObject before=configuration();RemoteProtocol.Batch batch=batch(RemoteProtocol.Stream.CONFIGURATION,changed(before));
        JSONObject offer=RemoteSectionTransfer.offer(batch,before);
        assertThrows(RemoteSectionTransfer.MissingBasis.class,()->RemoteSectionTransfer.restore(offer,"generation",RemoteProtocol.Role.DOM,null));
        JSONObject stale=new JSONObject(before.toString());stale.getJSONObject("contract").getJSONObject("sections").getJSONObject("censor").put("changed",true);
        assertThrows(RemoteSectionTransfer.MissingBasis.class,()->RemoteSectionTransfer.restore(offer,"generation",RemoteProtocol.Role.DOM,stale));
        JSONObject request=RemoteSectionTransfer.fullRequest(offer);
        RemoteSectionTransfer.requireFullRequest(request,batch);
        assertEquals(batch.digest(),RemoteSectionTransfer.fullResponse(batch.json(),offer).digest());
        assertThrows(IOException.class,()->RemoteSectionTransfer.fullResponse(batch(RemoteProtocol.Stream.CONFIGURATION,before).json(),offer));
        assertThrows(IOException.class,()->RemoteSectionTransfer.requireFullRequest(new JSONObject(request.toString()).put("through",2),batch));
        assertThrows(IOException.class,()->RemoteSectionTransfer.requireFullRequest(new JSONObject(request.toString()).put("generation","different"),batch));
    }

    @Test public void malformedReferencesUnknownFieldsAndWrongScopeAreRejected() throws Exception {
        JSONObject before=configuration(),offer=RemoteSectionTransfer.offer(batch(RemoteProtocol.Stream.BASELINE,changed(before)),before);
        JSONObject bad=new JSONObject(offer.toString());bad.put("unknown",true);
        assertThrows(IllegalArgumentException.class,()->RemoteSectionTransfer.restore(bad,"generation",RemoteProtocol.Role.SUB,before));
        assertThrows(IOException.class,()->RemoteSectionTransfer.restore(offer,"wrong",RemoteProtocol.Role.SUB,before));
        assertThrows(IOException.class,()->RemoteSectionTransfer.restore(offer,"generation",RemoteProtocol.Role.DOM,before));
        JSONObject malformed=new JSONObject(offer.toString());refs(malformed).put("censor","bad-hash");
        assertThrows(IOException.class,()->RemoteSectionTransfer.restore(malformed,"generation",RemoteProtocol.Role.SUB,before));
        JSONObject unknown=new JSONObject(offer.toString());refs(unknown).put("secret",String.join("",Collections.nCopies(64,"a")));
        assertThrows(IOException.class,()->RemoteSectionTransfer.restore(unknown,"generation",RemoteProtocol.Role.SUB,before));
        JSONObject changedDigest=new JSONObject(offer.toString()).put("digest",String.join("",Collections.nCopies(64,"f")));
        assertThrows(IOException.class,()->RemoteSectionTransfer.restore(changedDigest,"generation",RemoteProtocol.Role.SUB,before));
    }

    @Test public void duplicateAndRecursiveReferencesCannotExpand() throws Exception {
        JSONObject before=configuration(),offer=RemoteSectionTransfer.offer(batch(RemoteProtocol.Stream.BASELINE,changed(before)),before);
        JSONObject duplicate=new JSONObject(offer.toString());
        duplicate.getJSONArray("changes").getJSONObject(0).getJSONObject("body").getJSONObject("contract").getJSONObject("sections").put("censor",new JSONObject());
        assertThrows(IOException.class,()->RemoteSectionTransfer.restore(duplicate,"generation",RemoteProtocol.Role.SUB,before));
        JSONObject recursive=new JSONObject(offer.toString());refs(recursive).put("censor",new JSONObject().put("hash","nested"));
        assertThrows(IOException.class,()->RemoteSectionTransfer.restore(recursive,"generation",RemoteProtocol.Role.SUB,before));
    }

    @Test public void expandedRecordAndAggregateBoundsAreEnforcedBeforeReceipt() throws Exception {
        JSONObject before=configuration();
        String large=String.join("",Collections.nCopies(220_000,"x"));
        before.getJSONObject("contract").getJSONObject("sections").put("censor",new JSONObject().put("fixture",large));
        JSONObject after=changed(before);RemoteProtocol.Batch single=batch(RemoteProtocol.Stream.BASELINE,after);
        JSONObject offer=RemoteSectionTransfer.offer(single,before);
        assertEquals("section_batch",offer.getString("type"));
        JSONObject excessive=new JSONObject(offer.toString());
        excessive.getJSONArray("changes").getJSONObject(0).getJSONObject("body").put("extra",large);
        assertThrows(IOException.class,()->RemoteSectionTransfer.restore(excessive,"generation",RemoteProtocol.Role.SUB,before));
        JSONObject aggregate=new JSONObject(offer.toString());JSONArray rows=new JSONArray();
        for(int i=1;i<=6;i++) rows.put(new JSONObject(offer.getJSONArray("changes").getJSONObject(0).toString()).put("seq",i));
        aggregate.put("changes",rows).put("through",6);
        assertThrows(IOException.class,()->RemoteSectionTransfer.restore(aggregate,"generation",RemoteProtocol.Role.SUB,before));
    }

    @Test public void noBasisAndUnchangedEmptyQueueDoNotAddWireManifest() throws Exception {
        RemoteProtocol.Batch pending=batch(RemoteProtocol.Stream.CONFIGURATION,configuration());
        assertEquals("batch",RemoteSectionTransfer.offer(pending,null).getString("type"));
        RemoteProtocol.Batch empty=new RemoteProtocol.Batch("generation",RemoteProtocol.Stream.BASELINE,1,1,Collections.emptyList());
        assertEquals("batch",RemoteSectionTransfer.offer(empty,configuration()).getString("type"));
        assertTrue(RemoteSectionTransfer.offer(empty,configuration()).getJSONArray("changes").length()==0);
    }

    @Test public void oversizedReferenceWrapperKeepsValidNearLimitFullBatch() throws Exception {
        JSONObject values=new JSONObject();
        for(String section:ConfigurationSchema.SECTIONS)values.put(section,new JSONObject());
        JSONObject basis=new JSONObject().put("contract",new JSONObject().put("sections",values));
        int emptyBytes=RemoteProtocol.canonical(nearLimitBatch(basis,0).json()).getBytes(StandardCharsets.UTF_8).length;
        int padding=(RemoteProtocol.MAX_FRAME_BYTES-emptyBytes-10)/5;
        RemoteProtocol.Batch batch=nearLimitBatch(basis,padding);
        int fullBytes=RemoteProtocol.canonical(batch.json()).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(fullBytes<=RemoteProtocol.MAX_FRAME_BYTES);
        assertTrue(fullBytes>RemoteProtocol.MAX_FRAME_BYTES-20);
        RemoteSectionTransfer.EncodedOffer offer=RemoteSectionTransfer.encodeOffer(batch,basis);
        assertEquals("batch",offer.frame.getString("type"));
        assertSame(offer.encoded,offer.fullEncoded);
        assertEquals(batch.digest(),RemoteProtocol.Batch.parse(RemoteProtocol.read(new ByteArrayInputStream(offer.encoded))).digest());
    }

    private RemoteProtocol.Batch nearLimitBatch(JSONObject basis,int padding)throws Exception {
        java.util.List<RemoteProtocol.Change> rows=new java.util.ArrayList<>();
        JSONObject doc=new JSONObject(basis.toString()).put("padding",String.join("",Collections.nCopies(padding,"x")));
        for(int i=1;i<=5;i++)rows.add(new RemoteProtocol.Change(i,"active",RemoteProtocol.canonical(doc),false,1000));
        return new RemoteProtocol.Batch("generation",RemoteProtocol.Stream.BASELINE,0,5,rows);
    }

    private JSONObject refs(JSONObject offer)throws Exception{return offer.getJSONArray("changes").getJSONObject(0).getJSONObject("sectionRefs");}
    private JSONObject changed(JSONObject before)throws Exception {
        JSONObject after=new JSONObject(before.toString());
        after.getJSONObject("contract").getJSONObject("sections").getJSONObject("limits").put("app_timer_per_app_enabled",true);return after;
    }
    private RemoteProtocol.Batch batch(RemoteProtocol.Stream stream,JSONObject doc)throws Exception {
        JSONObject body=stream==RemoteProtocol.Stream.CONFIGURATION?new JSONObject().put("id","command").put("base",0).put("revision",1).put("configuration",doc):doc;
        return new RemoteProtocol.Batch("generation",stream,0,1,Collections.singletonList(new RemoteProtocol.Change(1,
                stream==RemoteProtocol.Stream.CONFIGURATION?"desired":"active",RemoteProtocol.canonical(body),false,1000)));
    }
    private JSONObject configurationBody(RemoteProtocol.Stream stream,JSONObject body)throws Exception {
        return stream==RemoteProtocol.Stream.CONFIGURATION?body.getJSONObject("configuration"):body;
    }
    private int wire(JSONObject value)throws Exception{ByteArrayOutputStream output=new ByteArrayOutputStream();RemoteProtocol.write(output,value);return output.size();}
    private JSONObject configuration()throws Exception {
        JSONObject sections=new JSONObject();
        for(String section:ConfigurationSchema.SECTIONS)sections.put(section,ConfigurationSettingCatalog.defaults(section));
        JSONObject metadata=RemoteConfiguration.currentMetadata("00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002","Synthetic configuration",1,1);
        return RemoteConfiguration.fromSections(metadata,sections,new JSONArray(),
                new JSONObject().put("all",false).put("packages",new JSONArray()).put("allowances",new JSONObject()),
                new JSONObject().put("duration",-1).put("minimum",3600000).put("maximum",86400000).put("hidden",false));
    }
}
