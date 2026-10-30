package com.subhub.app.people;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.util.AsyncUiScope;

/** The existing initials remain an immediate fallback while bounded profile artwork is decoded. */
public final class ProfileAvatarView extends FrameLayout {
    private final ProfileData profile;
    private final ImageView image;
    private final String job = "profile-avatar:" + System.identityHashCode(this);
    private AsyncUiScope scope;

    public ProfileAvatarView(Context context, ProfileData profile) {
        super(context);
        this.profile = profile;
        TextView fallback = ProfileViews.avatar(context, profile.name);
        addView(fallback, new LayoutParams(-1, -1));
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundResource(R.drawable.bg_profile_avatar);
        image.setClipToOutline(true);
        image.setVisibility(View.INVISIBLE);
        addView(image, new LayoutParams(-1, -1));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (profile.avatar.isEmpty()) return;
        String encoded = profile.avatar;
        try { scope = AsyncUiScope.forContext(getContext()); }
        catch (IllegalArgumentException noPage) { return; }
        scope.load(job, () -> ProfileImages.decode(encoded), loaded -> {
            image.setImageBitmap(loaded);
            image.setVisibility(loaded == null ? View.INVISIBLE : View.VISIBLE);
        }, failure -> image.setVisibility(View.INVISIBLE));
    }

    @Override protected void onDetachedFromWindow() {
        if (scope != null) scope.cancel(job);
        image.setImageDrawable(null);
        super.onDetachedFromWindow();
    }
}
