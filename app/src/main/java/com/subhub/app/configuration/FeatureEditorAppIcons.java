package com.subhub.app.configuration;

import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import com.subhub.app.R;
import com.subhub.app.remote.RemoteUi;
import com.subhub.app.util.AppIcons;

/** Native shared editors must keep the selected peer's image scope, never the viewer's package. */
public final class FeatureEditorAppIcons {
    private FeatureEditorAppIcons() { }
    public static void bind(ImageView view, String packageName, Drawable localIcon) {
        FeatureEditorSession scope = FeatureEditorSession.scope(view.getContext());
        if (scope == null) AppIcons.bind(view, packageName, R.drawable.ic_settings_apps, localIcon);
        else RemoteUi.bindAppIcon(view, RemoteUi.appIcon(view.getContext(), scope.peer.identity,
                scope.peer.generation, packageName), R.drawable.ic_settings_apps);
    }
}
