package com.subhub.app.configuration;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.subhub.app.internal.*;
import com.subhub.app.remote.RemoteAssets;
import com.subhub.app.remote.RemoteContracts;
import java.io.*;
import java.util.*;

/** User-picked content is staged as immutable owned blobs before an internal manifest can reference it. */
final class InternalEditorArtwork {
    private final Context physical;
    private final InternalStorageScope storage;
    private final InternalConfigurationStore.EditorDraft draft;
    private final Runnable authority;
    private final Set<String> censor, popup, disabled;
    @SuppressWarnings("unchecked") InternalEditorArtwork(Context physical, InternalStorageScope storage,
            InternalConfigurationStore.EditorDraft draft, Runnable authority) {
        this.physical=physical;this.storage=storage;this.draft=draft;this.authority=authority;
        Map<String,Object> saved=draft.acknowledgedBase().configuration().get(InternalConfigurationStore.ARTWORK);
        censor=new LinkedHashSet<>((Set<String>)saved.get("censor_assets"));
        popup=new LinkedHashSet<>((Set<String>)saved.get("popup_assets"));
        disabled=new LinkedHashSet<>((Set<String>)saved.get("disabled_assets"));
    }
    private Set<String> group(String group){
        if("censor".equals(group))return censor;if("popup".equals(group))return popup;
        throw new IllegalArgumentException("Unknown internal artwork group");
    }
    synchronized List<String> list(String group){authority.run();return new ArrayList<>(group(group));}
    synchronized boolean enabled(String hash){authority.run();requireOwned(hash);return !disabled.contains(hash);}
    synchronized void enable(String hash,boolean enabled){
        authority.run();requireOwned(hash);if(enabled)disabled.remove(hash);else disabled.add(hash);publish();
    }
    synchronized void delete(String hash){
        authority.run();requireOwned(hash);censor.remove(hash);popup.remove(hash);disabled.remove(hash);publish();
        // Retained immutable blobs may still be used by an active lease. No eager physical deletion.
    }
    private void requireOwned(String hash){if(!censor.contains(hash)&&!popup.contains(hash))throw new SecurityException("Unknown internal artwork");}
    private void publish(){draft.setArtwork(new LinkedHashSet<>(censor),new LinkedHashSet<>(popup),new LinkedHashSet<>(disabled));}
    private RemoteAssets assets()throws IOException{return new RemoteAssets(storage.directory(InternalStorageScope.Area.ASSETS));}
    Bitmap thumbnail(String hash,int maximum){
        try {
            synchronized(this){authority.run();requireOwned(hash);}
            RemoteAssets assets=assets();File file=assets.finish(hash);
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
            while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>Math.max(1,maximum)&&options.inSampleSize<(1<<28))options.inSampleSize*=2;
            return BitmapFactory.decodeFile(file.getPath(),options);
        }catch(IOException|RuntimeException unavailable){return null;}
    }
    int add(String group,List<Uri> uris){
        int added=0;
        for(Uri uri:uris){File temporary=null;
            try {
                synchronized(this){authority.run();if(censor.size()+popup.size()>=512)break;group(group);}
                temporary=storage.file(InternalStorageScope.Area.CACHE,"import-"+UUID.randomUUID()+".tmp");
                try(InputStream input=physical.getContentResolver().openInputStream(uri);FileOutputStream output=new FileOutputStream(temporary)){
                    if(input==null)throw new IOException("Selected image unavailable");
                    byte[] buffer=new byte[8192];long total=0;int count;
                    while((count=input.read(buffer))!=-1){total+=count;if(total>RemoteAssets.MAX_ASSET_BYTES)throw new IOException("Image too large");output.write(buffer,0,count);}
                    output.getFD().sync();
                }
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(temporary.getPath(),bounds);
                if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("Unsupported image");
                String hash=RemoteAssets.digest(temporary);RemoteAssets assets=assets();
                try(InputStream input=new FileInputStream(temporary)){RemoteContracts.ingest(assets,hash,temporary.length(),input);}
                com.subhub.app.storage.InternalAssetDurability.seal(storage.directory(InternalStorageScope.Area.ASSETS),
                        assets, hash, temporary.length());
                synchronized(this){authority.run();if(censor.size()+popup.size()>=512)break;if(group(group).add(hash)){publish();added++;}}
            }catch(IOException|RuntimeException unavailable){/* Caller displays the successfully staged count. */}
            finally{if(temporary!=null)temporary.delete();}
        }
        return added;
    }
}
