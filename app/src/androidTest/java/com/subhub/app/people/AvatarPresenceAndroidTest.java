package com.subhub.app.people;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.ImageView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;

/** One offscreen native witness for badge geometry over initials and synthetic profile artwork. */
public final class AvatarPresenceAndroidTest {
    @Test public void badgesStayCircularInsideSmallAndLargeAvatars() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context context = new androidx.appcompat.view.ContextThemeWrapper(target, R.style.Theme_SubHub);
        Bitmap sheet = Bitmap.createBitmap(930, 280, Bitmap.Config.ARGB_8888);
        Canvas witness = new Canvas(sheet); witness.drawColor(Color.WHITE);
        File output = new File(target.getCacheDir(), "avatar-presence-" + java.util.UUID.randomUUID() + ".png");
        try { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int row = 0; row < 2; row++) {
                int background = row == 0 ? Color.WHITE : context.getColor(R.color.surface_card);
                android.graphics.Paint paint = new android.graphics.Paint(); paint.setColor(background);
                witness.drawRect(0, row * 140, 930, (row + 1) * 140, paint);
                PersonSnapshot.Connection[] states = {PersonSnapshot.Connection.CONNECTED,
                        PersonSnapshot.Connection.IDLE, PersonSnapshot.Connection.OFFLINE};
                for (int index = 0; index < 6; index++) {
                    PersonSnapshot.Connection state = states[index % states.length];
                    int color = switch (state) {
                        case CONNECTED -> R.color.avatar_presence_online;
                        case IDLE -> R.color.avatar_presence_idle;
                        default -> R.color.avatar_presence_offline;
                    };
                    int edge = index < states.length ? 56 : 72;
                    ProfileAvatarView avatar = new ProfileAvatarView(context, ProfileData.empty("Alex Quinn"));
                    android.widget.LinearLayout identity = PersonUi.column(context);
                    avatar.setTag("fixture_avatar"); identity.addView(avatar);
                    if (row == 1) {
                        Bitmap source = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
                        Canvas artwork = new Canvas(source); artwork.drawColor(Color.rgb(45, 110, 150));
                        paint.setColor(Color.rgb(230, 180, 100)); artwork.drawCircle(96, 74, 38, paint);
                        artwork.drawOval(30, 110, 162, 230, paint);
                        try {
                            Bitmap encoded = ProfileImages.decode(ProfileImages.encode(source));
                            assertNotNull(encoded);
                            ImageView image = (ImageView) avatar.getChildAt(1);
                            // Software View.draw does not replay the image child's hardware oval outline.
                            androidx.core.graphics.drawable.RoundedBitmapDrawable rounded =
                                    androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(
                                            context.getResources(), encoded);
                            rounded.setCircular(true); rounded.setAntiAlias(true);
                            image.setImageDrawable(rounded); image.setVisibility(View.VISIBLE);
                        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                        finally { source.recycle(); }
                    }
                    ProfileViews.presence(identity, "fixture", new PersonId(
                            "remote_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_fixture"),
                            state);
                    assertFalse(avatar.isClickable()); assertFalse(avatar.isFocusable());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, avatar.getImportantForAccessibility());
                    int exact = View.MeasureSpec.makeMeasureSpec(edge, View.MeasureSpec.EXACTLY);
                    avatar.measure(exact, exact); avatar.layout(0, 0, edge, edge);
                    Bitmap pixels = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(pixels); canvas.drawColor(background); avatar.draw(canvas);
                    witness.drawBitmap(pixels, index * 155 + 32, row * 140 + 28, null);
                    int center = Math.round(edge * .84f);
                    assertEquals(context.getColor(color), pixels.getPixel(center, center));
                    assertEquals(context.getColor(R.color.surface_card),
                            pixels.getPixel(Math.round(edge * .97f), center));
                    assertEquals(background, pixels.getPixel(edge - 1, edge - 1));
                    ProfileViews.presence(identity, "fixture", PersonId.LOCAL, PersonSnapshot.Connection.LOCAL);
                    canvas.drawColor(background); avatar.draw(canvas);
                    assertNotEquals(context.getColor(color), pixels.getPixel(center, center));
                    pixels.recycle();
                }
            }
        }); } finally {
            try (FileOutputStream stream = new FileOutputStream(output)) {
                assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } finally { sheet.recycle(); }
        }
    }
}
