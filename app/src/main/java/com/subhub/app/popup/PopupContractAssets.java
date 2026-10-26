package com.subhub.app.popup;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.*;

/** Exports selected image bytes without granting the peer access to local document providers. */
public final class PopupContractAssets {
    private PopupContractAssets(){}
    public static List<String> selected(Context context,int maximum) throws IOException {
        PopupStormSettings settings=PopupStormSettings.load(context);PopupBitmapCache source=new PopupBitmapCache(context);
        Set<String> paths=new LinkedHashSet<>();List<String> roots=new ArrayList<>(settings.getFolders());
        if(!settings.getPackImageDir().isEmpty())roots.add(0,settings.getPackImageDir());
        for(String root:roots){paths.addAll(source.listFolderImages(root,maximum+1-paths.size()));if(paths.size()>maximum)throw new IOException("Too many contract images");}
        return new ArrayList<>(paths);
    }
    public static InputStream open(Context context,String path)throws IOException {
        if(path.startsWith("content://")){InputStream input=context.getContentResolver().openInputStream(Uri.parse(path));if(input==null)throw new IOException("Image unavailable");return input;}
        return new FileInputStream(path);
    }
}
