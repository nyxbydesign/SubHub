package com.subhub.app.remote;

import com.subhub.app.configuration.ConfigurationSchema;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Optional wire references; durable records and acknowledgments always use the complete batch. */
final class RemoteSectionTransfer {
    private RemoteSectionTransfer() { }

    static final class MissingBasis extends IOException {
        MissingBasis() { super("Configuration section basis unavailable"); }
    }

    static final class EncodedOffer {
        final JSONObject frame;
        final byte[] encoded, fullEncoded;
        EncodedOffer(JSONObject frame,byte[] encoded,byte[] fullEncoded) {
            this.frame=frame;this.encoded=encoded;this.fullEncoded=fullEncoded;
        }
    }

    static JSONObject offer(RemoteProtocol.Batch batch, JSONObject basis) throws Exception {
        return encodeOffer(batch,basis).frame;
    }

    static EncodedOffer encodeOffer(RemoteProtocol.Batch batch, JSONObject basis) throws Exception {
        JSONObject full=batch.json();
        byte[] fullEncoded=encode(full);
        EncodedOffer unchanged=new EncodedOffer(full,fullEncoded,fullEncoded);
        if (!supported(batch.stream) || basis==null) return unchanged;
        JSONObject known=sections(basis), reduced=new JSONObject(RemoteProtocol.canonical(full));
        if(known==null) return unchanged;
        boolean reused=false;
        JSONArray rows=reduced.getJSONArray("changes");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i), refs=new JSONObject();
            row.put("sectionRefs",refs);
            if(row.getBoolean("deleted") || !documentKey(batch.stream,row.getString("key")))continue;
            JSONObject doc=document(batch.stream,row.getJSONObject("body")), values=sections(doc);
            if(values==null)continue;
            for(String name:ConfigurationSchema.SECTIONS) {
                JSONObject value=values.optJSONObject(name), previous=known.optJSONObject(name);
                if(value!=null && previous!=null) {
                    String hash=digest(value);
                    if(hash.equals(digest(previous))) {
                        refs.put(name,hash);values.remove(name);reused=true;
                    }
                }
            }
        }
        if(!reused)return unchanged;
        reduced.put("type","section_batch").put("digest",batch.digest());
        // Hash references can cost more than small compressed sections. Use actual wire bytes.
        if(bytes(reduced)>RemoteProtocol.MAX_FRAME_BYTES)return unchanged;
        byte[] encoded=encode(reduced);
        return encoded.length<fullEncoded.length?new EncodedOffer(reduced,encoded,fullEncoded):unchanged;
    }

    static RemoteProtocol.Batch restore(JSONObject frame,String generation,RemoteProtocol.Role writer,JSONObject basis)
            throws JSONException,IOException {
        validateHeader(frame,generation,writer);
        JSONObject full=new JSONObject(RemoteProtocol.canonical(frame));
        JSONObject known=sections(basis);boolean missing=false, referenced=false;
        JSONArray rows=full.getJSONArray("changes");
        if(rows.length()==0 || rows.length()>RemoteProtocol.MAX_BATCH_RECORDS)throw new IOException("Invalid section batch size");
        JSONObject header=new JSONObject(RemoteProtocol.canonical(full));
        header.remove("digest");header.put("type","batch").put("changes",new JSONArray());
        long expandedBytes=bytes(header);
        RemoteProtocol.Stream stream=RemoteProtocol.Stream.valueOf(frame.getString("stream"));
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);
            RemoteConfiguration.keys(row,"seq","key","body","deleted","observedAt","sectionRefs");
            JSONObject refs=row.getJSONObject("sectionRefs");
            if(refs.length()>ConfigurationSchema.SECTIONS.size())throw new IOException("Too many section references");
            JSONObject values=null;
            if(refs.length()>0) {
                referenced=true;
                if(row.getBoolean("deleted") || !documentKey(stream,row.getString("key")))throw new IOException("Invalid section document");
                values=sections(document(stream,row.getJSONObject("body")));
                if(values==null || values.length()+refs.length()>ConfigurationSchema.SECTIONS.size())throw new IOException("Invalid section set");
                Iterator<String> actual=values.keys();
                while(actual.hasNext())if(!ConfigurationSchema.SECTIONS.contains(actual.next()))throw new IOException("Unknown section");
            }
            Iterator<String> names=refs.keys();
            while(names.hasNext()) {
                String name=names.next();Object hash=refs.get(name);
                if(!ConfigurationSchema.SECTIONS.contains(name) || !(hash instanceof String)
                        || !((String)hash).matches("[0-9a-f]{64}") || values.has(name))throw new IOException("Invalid section reference");
                JSONObject previous=known==null?null:known.optJSONObject(name);
                if(previous==null || !hash.equals(digest(previous)))missing=true;
                else {
                    // Preflight before copying a referenced subtree; do not build a reference bomb.
                    long added=bytes(previous)+JSONObject.quote(name).getBytes(StandardCharsets.UTF_8).length+2L;
                    if(bytes(row.getJSONObject("body"))+added>RemoteProtocol.MAX_RECORD_BYTES
                            || expandedBytes+bytes(row)+added>RemoteProtocol.MAX_FRAME_BYTES)
                        throw new IOException("Expanded section content too large");
                    values.put(name,new JSONObject(RemoteProtocol.canonical(previous)));
                }
            }
            row.remove("sectionRefs");
            if(RemoteProtocol.canonical(row.getJSONObject("body")).getBytes(StandardCharsets.UTF_8).length>RemoteProtocol.MAX_RECORD_BYTES)
                throw new IOException("Expanded section record too large");
            expandedBytes+=bytes(row)+(i==0?0:1);
            if(expandedBytes>RemoteProtocol.MAX_FRAME_BYTES)throw new IOException("Expanded section frame too large");
        }
        if(!referenced)throw new IOException("Empty section references");
        // Validate the complete reduced range and rows even when a local basis is absent.
        full.remove("digest");full.put("type","batch");
        if(RemoteProtocol.canonical(full).getBytes(StandardCharsets.UTF_8).length>RemoteProtocol.MAX_FRAME_BYTES)
            throw new IOException("Expanded section frame too large");
        RemoteProtocol.Batch batch=RemoteProtocol.Batch.parse(full);
        if(missing)throw new MissingBasis();
        if(!batch.digest().equals(frame.getString("digest")))throw new IOException("Section batch digest mismatch");
        return batch;
    }

    static JSONObject fullRequest(JSONObject offer) throws JSONException {
        return new JSONObject().put("version",1).put("type","section_full").put("generation",offer.getString("generation"))
                .put("stream",offer.getString("stream")).put("after",RemoteProtocol.integer(offer,"after"))
                .put("through",RemoteProtocol.integer(offer,"through")).put("digest",offer.getString("digest"));
    }

    static void requireFullRequest(JSONObject request,RemoteProtocol.Batch batch) throws JSONException,IOException {
        RemoteConfiguration.keys(request,"version","type","generation","stream","after","through","digest");
        RemoteProtocol.requireVersion(request);
        if(!"section_full".equals(request.getString("type")) || !batch.generation.equals(request.getString("generation"))
                || !batch.stream.name().equals(request.getString("stream")) || batch.after!=RemoteProtocol.integer(request,"after")
                || batch.through!=RemoteProtocol.integer(request,"through") || !batch.digest().equals(request.getString("digest")))
            throw new IOException("Unmatched section fallback request");
    }

    static RemoteProtocol.Batch fullResponse(JSONObject full,JSONObject offer) throws JSONException,IOException {
        RemoteConfiguration.keys(full,"version","type","generation","stream","after","through","changes");
        JSONArray rows=full.getJSONArray("changes");
        if(rows.length()>RemoteProtocol.MAX_BATCH_RECORDS)throw new IOException("Too many fallback records");
        for(int i=0;i<rows.length();i++)RemoteConfiguration.keys(rows.getJSONObject(i),"seq","key","body","deleted","observedAt");
        RemoteProtocol.Batch batch=RemoteProtocol.Batch.parse(full);
        requireFullRequest(fullRequest(offer),batch);
        return batch;
    }

    private static void validateHeader(JSONObject frame,String generation,RemoteProtocol.Role writer)throws JSONException,IOException {
        RemoteConfiguration.keys(frame,"version","type","generation","stream","after","through","changes","digest");
        RemoteProtocol.requireVersion(frame);
        RemoteProtocol.Stream stream;
        try{stream=RemoteProtocol.Stream.valueOf(frame.getString("stream"));}
        catch(IllegalArgumentException invalid){throw new IOException("Unknown section stream",invalid);}
        Object hash=frame.get("digest");
        if(!"section_batch".equals(frame.getString("type")) || !generation.equals(frame.getString("generation"))
                || !supported(stream) || stream.writer!=writer || !(hash instanceof String) || !((String)hash).matches("[0-9a-f]{64}"))
            throw new IOException("Invalid section batch header");
    }
    private static boolean supported(RemoteProtocol.Stream stream) {
        return stream==RemoteProtocol.Stream.CONFIGURATION || stream==RemoteProtocol.Stream.BASELINE;
    }
    private static boolean documentKey(RemoteProtocol.Stream stream,String key) {
        return (stream==RemoteProtocol.Stream.CONFIGURATION?"desired":"active").equals(key);
    }
    private static JSONObject document(RemoteProtocol.Stream stream,JSONObject body) {
        return stream==RemoteProtocol.Stream.CONFIGURATION?body.optJSONObject("configuration"):body;
    }
    private static JSONObject sections(JSONObject doc) {
        JSONObject contract=doc==null?null:doc.optJSONObject("contract");
        return contract==null?null:contract.optJSONObject("sections");
    }
    private static String digest(JSONObject value) {
        return RemoteProtocol.sha256(RemoteProtocol.canonical(value).getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] encode(JSONObject value)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();RemoteProtocol.write(bytes,value);return bytes.toByteArray();
    }
    private static int bytes(JSONObject value) {
        return RemoteProtocol.canonical(value).getBytes(StandardCharsets.UTF_8).length;
    }
}
