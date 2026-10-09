package com.subhub.app.util;

import android.net.Uri;

import androidx.core.content.FileProvider;

import com.subhub.app.pack.SubHubPackArchive;

import java.util.Locale;

/** Report pack content consistently regardless of Android's extension-to-MIME mapping. */
public final class SubHubFileProvider extends FileProvider {
    @Override
    public String getType(Uri uri) {
        String defaultType = super.getType(uri); // Retain FileProvider's path validation.
        String name = uri.getLastPathSegment();
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(SubHubPackArchive.EXTENSION)
                ? SubHubPackArchive.MIME_TYPE
                : defaultType;
    }
}
