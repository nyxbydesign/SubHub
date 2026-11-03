package com.subhub.app.remote.icons;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.util.RemoteAppIcon;
import java.io.ByteArrayOutputStream;
import java.util.Objects;

/** Resolves only the exact currently published package requested by the authenticated DOM. */
public final class AndroidAppIconSource implements AppIconExchange.Source {
    private final Context context;
    private final AppIconExchange.Authority authority;

    public AndroidAppIconSource(Context context, AppIconExchange.Authority authority) {
        this.context = context.getApplicationContext(); this.authority = Objects.requireNonNull(authority);
    }

    @Override public byte[] rasterize(RemoteStore.Peer peer, String packageName) throws Exception {
        new RemoteAppIcon(peer.identity, peer.generation, packageName, null);
        requireCurrent(peer, packageName);
        Drawable drawable;
        try { drawable = context.getPackageManager().getApplicationIcon(packageName); }
        catch (PackageManager.NameNotFoundException unavailable) { return null; }
        requireCurrent(peer, packageName);
        return render(drawable, () -> requireCurrent(peer, packageName));
    }

    static byte[] render(Drawable drawable, Runnable currentGuard) {
        currentGuard.run();
        Bitmap raster = Bitmap.createBitmap(RemoteAppIcon.MAX_PIXELS, RemoteAppIcon.MAX_PIXELS, Bitmap.Config.ARGB_8888);
        try {
            drawable = drawable.mutate();
            drawable.setBounds(0, 0, raster.getWidth(), raster.getHeight());
            drawable.draw(new Canvas(raster));
            currentGuard.run();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!raster.compress(Bitmap.CompressFormat.PNG, 100, bytes) || bytes.size() > RemoteAppIcon.MAX_BYTES)
                return null;
            currentGuard.run();
            return bytes.toByteArray();
        } finally { raster.recycle(); }
    }

    private void requireCurrent(RemoteStore.Peer peer, String name) {
        if (!authority.current(peer) || !authority.published(peer, name)) throw new SecurityException("Icon publication revoked");
    }
}
