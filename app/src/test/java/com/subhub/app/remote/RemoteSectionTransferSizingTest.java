package com.subhub.app.remote;

import static org.junit.Assert.*;
import com.subhub.app.configuration.ConfigurationSchema;
import com.subhub.app.configuration.ConfigurationSettingCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Comparison fixture only: it does not introduce a negotiated wire format. */
public class RemoteSectionTransferSizingTest {
    @Test public void compareFullAndSingleFeatureTransferIncludingHashOverhead() throws Exception {
        compare("defaults", configuration(false));
        compare("selectedAppsAndArtwork", configuration(true));
    }

    private void compare(String label, JSONObject before) throws Exception {
        JSONObject after=new JSONObject(RemoteProtocol.canonical(before));
        JSONObject sections=after.getJSONObject("contract").getJSONObject("sections");
        JSONObject limits=sections.getJSONObject("limits");
        limits.put("app_timer_per_app_enabled",!limits.getBoolean("app_timer_per_app_enabled"));
        JSONObject manifest=new JSONObject(),refs=new JSONObject();
        JSONObject delta=new JSONObject(RemoteProtocol.canonical(after));
        JSONObject deltaSections=delta.getJSONObject("contract").getJSONObject("sections");
        Iterator<String> names=before.getJSONObject("contract").getJSONObject("sections").keys();
        int changed=0,reused=0;
        while(names.hasNext()) {
            String name=names.next();
            String previous=RemoteProtocol.canonical(before.getJSONObject("contract").getJSONObject("sections").getJSONObject(name));
            String hash=RemoteProtocol.sha256(previous.getBytes(StandardCharsets.UTF_8));
            manifest.put(name,hash);
            if(previous.equals(RemoteProtocol.canonical(sections.getJSONObject(name)))) {
                refs.put(name,hash);deltaSections.remove(name);reused++;
            } else changed++;
        }
        assertEquals(1,changed);assertTrue(reused>0);
        JSONObject fullFrame=frame("batch",after);
        JSONObject deltaFrame=frame("section_batch",delta).put("sectionRefs",refs);
        JSONObject manifestFrame=frame("section_manifest",manifest);
        int full=wire(fullFrame),warmDelta=wire(deltaFrame),manifestBytes=wire(manifestFrame);
        System.out.println("section-sizing "+label+" fullWireBytes="+full+" cachedDeltaWireBytes="+warmDelta
                +" manifestWireBytes="+manifestBytes+" coldDeltaTotal="+(warmDelta+manifestBytes)
                +" fullSections="+(changed+reused)+" changedSections="+changed+" reusedSections="+reused);
        // Prove the proposed refs reconstruct exact original content before an application receipt.
        JSONObject restored=new JSONObject(RemoteProtocol.canonical(delta));
        Iterator<String> referenced=refs.keys();
        while(referenced.hasNext()) {
            String name=referenced.next();
            JSONObject value=before.getJSONObject("contract").getJSONObject("sections").getJSONObject(name);
            assertEquals(refs.getString(name),RemoteProtocol.sha256(RemoteProtocol.canonical(value).getBytes(StandardCharsets.UTF_8)));
            restored.getJSONObject("contract").getJSONObject("sections").put(name,value);
        }
        assertEquals(RemoteProtocol.canonical(after),RemoteProtocol.canonical(restored));
    }

    private JSONObject frame(String type,JSONObject body) throws Exception {
        return new JSONObject().put("version",1).put("type",type).put("generation","fixture-generation").put("body",body);
    }
    private int wire(JSONObject frame) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();RemoteProtocol.write(bytes,frame);return bytes.size();
    }
    private JSONObject configuration(boolean rich) throws Exception {
        JSONObject sections=new JSONObject();
        for(String section:ConfigurationSchema.SECTIONS)sections.put(section,ConfigurationSettingCatalog.defaults(section));
        JSONObject metadata=RemoteConfiguration.currentMetadata("00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002","Synthetic configuration",1,1);
        JSONArray packages=new JSONArray(),assets=new JSONArray();JSONObject allowances=new JSONObject();
        if(rich)for(int i=0;i<32;i++) {
            String packageName="com.example.selected"+i;
            packages.put(packageName);allowances.put(packageName,15+i*5);
            assets.put(new JSONObject().put("path","assets/censor/image-"+i+".png")
                    .put("hash",RemoteProtocol.sha256(("synthetic-artwork-"+i).getBytes(StandardCharsets.UTF_8)))
                    .put("length",10000+i));
        }
        return RemoteConfiguration.fromSections(metadata,sections,assets,
                new JSONObject().put("all",false).put("packages",packages).put("allowances",allowances),
                new JSONObject().put("duration",-1).put("minimum",3600000).put("maximum",86400000).put("hidden",false));
    }
}
