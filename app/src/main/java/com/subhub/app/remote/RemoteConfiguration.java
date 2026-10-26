package com.subhub.app.remote;

import com.subhub.app.BuildConfig;
import com.subhub.app.pack.PackSettingCatalog;
import com.subhub.app.pack.SubHubPack;
import com.subhub.app.pack.SubHubPackArchive;
import com.subhub.app.pack.SubHubPackSchema;
import com.subhub.app.update.SemanticVersion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Peer-specific configuration wraps portable settings without credentials or operational commands. */
public final class RemoteConfiguration {
    public static final int SCHEMA=1;
    public static final String DURATION_PREFS="subhub_service_duration_ui";
    private RemoteConfiguration(){}

    public static JSONObject validate(JSONObject input) throws JSONException {
        keys(input,"schema","contract","apps","service");
        if(RemoteProtocol.integer(input,"schema")!=SCHEMA)throw new IllegalArgumentException("Unsupported configuration schema");
        JSONObject contract=input.getJSONObject("contract");
        keys(contract,"id","origin","name","author","description","version","minimumAppVersion","createdAt","updatedAt","sections","assets");
        uuid(contract.getString("id"));uuid(contract.getString("origin"));
        text(contract,"name",160,false);text(contract,"author",160,true);text(contract,"description",4000,true);
        text(contract,"version",64,false);text(contract,"minimumAppVersion",64,false);
        SemanticVersion.parse(contract.getString("version"));
        if(SemanticVersion.parse(BuildConfig.VERSION_NAME).compareTo(SemanticVersion.parse(contract.getString("minimumAppVersion")))<0) {
            throw new IllegalArgumentException("Contract needs a newer app version");
        }
        if(RemoteProtocol.integer(contract,"createdAt")<=0 || RemoteProtocol.integer(contract,"updatedAt")<=0)throw new IllegalArgumentException("Invalid contract date");
        JSONObject sections=contract.getJSONObject("sections"),normalized=new JSONObject();
        if(sections.length()==0)throw new IllegalArgumentException("Empty contract");
        java.util.Iterator<String> sectionNames=sections.keys();
        while(sectionNames.hasNext()) {
            String section=sectionNames.next();
            if(!SubHubPackSchema.SECTIONS.contains(section))throw new IllegalArgumentException("Unsupported contract section");
            JSONObject values=sections.getJSONObject(section);java.util.Iterator<String> names=values.keys();
            while(names.hasNext()) {
                String name=names.next();
                if(SubHubPackSchema.isSecretOrRuntimeKey(name) || PackSettingCatalog.field(section,name)==null) {
                    throw new IllegalArgumentException("Unsupported contract setting");
                }
            }
            normalized.put(section,SubHubPackSchema.sanitizeSection(section,values));
        }
        JSONArray assets=contract.getJSONArray("assets");
        if(assets.length()>512)throw new IllegalArgumentException("Too many contract assets");
        Set<String> paths=new HashSet<>();Map<String,Long> hashes=new LinkedHashMap<>();long total=0;
        for(int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i);keys(asset,"path","hash","length");
            String path=asset.getString("path"),hash=asset.getString("hash");long length=RemoteProtocol.integer(asset,"length");
            if(path.length()>256 || path.contains("\\") || !SubHubPackArchive.isSafeAssetPath(path) || !paths.add(path)
                    || !hash.matches("[0-9a-f]{64}") || length<=0 || length>RemoteAssets.MAX_ASSET_BYTES)throw new IllegalArgumentException("Invalid contract asset");
            Long previous=hashes.put(hash,length);
            if(previous!=null && previous!=length)throw new IllegalArgumentException("Conflicting asset size");
            total+=length;if(total>RemoteAssets.MAX_TOTAL_BYTES)throw new IllegalArgumentException("Contract asset limit exceeded");
        }
        JSONObject apps=input.getJSONObject("apps");keys(apps,"all","packages","allowances");
        if(!(apps.get("all") instanceof Boolean))throw new IllegalArgumentException("Invalid app scope");
        JSONArray packages=apps.getJSONArray("packages");
        if(packages.length()>512)throw new IllegalArgumentException("Too many selected apps");
        Set<String> selected=new HashSet<>();
        for(int i=0;i<packages.length();i++)if(!selected.add(packageName(packages.getString(i))))throw new IllegalArgumentException("Duplicate app selection");
        JSONObject allowances=apps.getJSONObject("allowances");
        if(allowances.length()>512)throw new IllegalArgumentException("Too many app allowances");
        java.util.Iterator<String> names=allowances.keys();
        while(names.hasNext()) {String name=packageName(names.next());long minutes=RemoteProtocol.integer(allowances,name);if(minutes<1 || minutes>1440)throw new IllegalArgumentException("Invalid app allowance");}
        JSONObject service=input.getJSONObject("service");keys(service,"duration","minimum","maximum","hidden");
        long duration=RemoteProtocol.integer(service,"duration"),min=RemoteProtocol.integer(service,"minimum"),max=RemoteProtocol.integer(service,"maximum");
        if(!(service.get("hidden") instanceof Boolean))throw new IllegalArgumentException("Invalid duration visibility");
        if(duration!=-1 && duration!=-2 && duration!=3_600_000L && duration!=86_400_000L
                && duration!=604_800_000L && duration!=2_592_000_000L)throw new IllegalArgumentException("Invalid service duration");
        if(min<1_800_000L || max>2_592_000_000L || max<min)throw new IllegalArgumentException("Invalid duration range");
        JSONObject result=new JSONObject(RemoteProtocol.canonical(input));result.getJSONObject("contract").put("sections",normalized);
        if(RemoteProtocol.canonical(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>RemoteProtocol.MAX_RECORD_BYTES-1024)throw new IllegalArgumentException("Configuration is too large");
        return result;
    }

    public static JSONObject fromPortable(SubHubPack pack,JSONArray assets,JSONObject apps,JSONObject service) throws JSONException {
        JSONObject sections=new JSONObject();for(String section:pack.getIncludedSections())sections.put(section,pack.getSection(section));
        JSONObject contract=new JSONObject().put("id",pack.getId()).put("origin",pack.getOriginDeviceId()).put("name",pack.getName())
                .put("author",pack.getAuthor()).put("description",pack.getDescription()).put("version",pack.getPackVersion())
                .put("minimumAppVersion",pack.getMinimumSubHubVersion()).put("createdAt",pack.getCreatedAt()).put("updatedAt",pack.getUpdatedAt())
                .put("sections",sections).put("assets",assets);
        return validate(new JSONObject().put("schema",SCHEMA).put("contract",contract).put("apps",apps).put("service",service));
    }

    public static JSONObject active(JSONObject input) throws JSONException {
        JSONObject result=validate(input),sections=result.getJSONObject("contract").getJSONObject("sections");
        Set<String> included=new HashSet<>();sections.keys().forEachRemaining(included::add);
        if(!included.equals(SubHubPackSchema.SECTIONS))throw new IllegalArgumentException("Incomplete active configuration");
        for(String section:included) {
            Set<String> fields=new HashSet<>();sections.getJSONObject(section).keys().forEachRemaining(fields::add);
            if(!fields.equals(PackSettingCatalog.keys(section)))throw new IllegalArgumentException("Incomplete active settings");
        }
        return result;
    }

    public static SubHubPack portable(JSONObject configuration) throws JSONException {
        JSONObject doc=validate(configuration),contract=doc.getJSONObject("contract"),raw=contract.getJSONObject("sections");
        Map<String,JSONObject> sections=new LinkedHashMap<>();raw.keys().forEachRemaining(key -> sections.put(key,raw.optJSONObject(key)));
        return new SubHubPack(contract.getString("id"),contract.getString("origin"),contract.getString("name"),contract.getString("author"),
                contract.getString("description"),contract.getString("version"),contract.getLong("createdAt"),contract.getLong("updatedAt"),
                contract.getString("minimumAppVersion"),sections,new JSONObject().put("serviceDurationMillis",doc.getJSONObject("service").getLong("duration")),java.util.Collections.emptyMap());
    }

    public static Set<String> selected(JSONObject configuration) throws JSONException {
        Set<String> result=new LinkedHashSet<>();JSONArray rows=configuration.getJSONObject("apps").getJSONArray("packages");
        for(int i=0;i<rows.length();i++)result.add(packageName(rows.getString(i)));return result;
    }

    static String packageName(String value) {
        if(value==null || value.length()>255 || !value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*"))throw new IllegalArgumentException("Invalid app identity");
        return value;
    }
    static void keys(JSONObject object,String... expected) {
        Set<String> allowed=new HashSet<>(Arrays.asList(expected));java.util.Iterator<String> actual=object.keys();
        while(actual.hasNext())if(!allowed.contains(actual.next()))throw new IllegalArgumentException("Unsupported configuration field");
        for(String name:expected)if(!object.has(name))throw new IllegalArgumentException("Incomplete configuration");
    }
    private static void uuid(String value){if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("Invalid contract identity");}
    private static void text(JSONObject object,String name,int max,boolean empty) throws JSONException {
        Object value=object.get(name);if(!(value instanceof String) || ((String)value).length()>max || !empty && ((String)value).trim().isEmpty())throw new IllegalArgumentException("Invalid contract text");
    }
}
