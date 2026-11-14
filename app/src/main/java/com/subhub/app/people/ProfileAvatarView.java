package com.subhub.app.people;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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
    private final Paint presencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int presenceColor;

    public ProfileAvatarView(Context context, ProfileData profile) {
        super(context);
        this.profile = profile;
        // Only the image child is oval-clipped; the corner badge needs the full square bounds.
        setClipToOutline(false);
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

    /** Avatar previews have no badge until a paired profile supplies presence. */
    public void setPresence(PersonSnapshot.Connection state) {
        presenceColor = switch (state) {
            case CONNECTED -> R.color.avatar_presence_online;
            case IDLE -> R.color.avatar_presence_idle;
            default -> R.color.avatar_presence_offline;
        };
        invalidate();
    }

    public void clearPresence() {
        presenceColor = 0;
        invalidate();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (presenceColor == 0) return;
        float edge = Math.min(getWidth(), getHeight());
        float outer = edge * .16f;
        float cx = getWidth() - outer, cy = getHeight() - outer;
        // Both circles stay inside the avatar bounds, including inside clipping parents.
        presencePaint.setColor(getContext().getColor(R.color.surface_card));
        canvas.drawCircle(cx, cy, outer, presencePaint);
        presencePaint.setColor(getContext().getColor(presenceColor));
        canvas.drawCircle(cx, cy, edge * .11f, presencePaint);
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
