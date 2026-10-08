package com.subhub.app.settings;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.CompoundButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CensorStylePhoneAndroidTest {
    private SharedPreferences preferences;
    private Map<String, ?> original;
    private boolean wasDom;
    @Before public void prepare() {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = new SettingsRepository(context).preferences();
        original = new java.util.LinkedHashMap<>(preferences.getAll());
        wasDom = ControllerPinManager.isDomModeActive();
        ControllerPinManager.enterDomMode();
        preferences.edit().putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                .putString(SettingsRepository.KEY_CENSOR_TYPE, "box")
                .putBoolean(SettingsRepository.KEY_REVERSE_MODE, false).commit();
    }
    @After public void restore() {
        SharedPreferenceTestRestore.restore(preferences, original);
        if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
    }

    @Test public void imageManagementOnlyAppearsForAnApplicableImageStyleAndSurvivesReopen() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.button_appearance_details).performClick();
                int[] choices = {R.id.radio_box, R.id.radio_pixelate, R.id.radio_blur, R.id.radio_custom,
                        R.id.radio_static, R.id.radio_glitch, R.id.radio_tape, R.id.radio_error};
                for (int id : choices) {
                    activity.findViewById(id).performClick();
                    assertEquals(id == R.id.radio_custom ? View.VISIBLE : View.GONE,
                            activity.findViewById(R.id.censor_images_section).getVisibility());
                }
                activity.findViewById(R.id.radio_custom).performClick();
                ((CompoundButton) activity.findViewById(R.id.switch_reverse)).setChecked(true);
                assertEquals(View.GONE, activity.findViewById(R.id.censor_images_section).getVisibility());
                ((CompoundButton) activity.findViewById(R.id.switch_reverse)).setChecked(false);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.censor_images_section).getVisibility());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(((CompoundButton) activity.findViewById(R.id.radio_custom)).isChecked());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.censor_images_section).getVisibility());
                assertEquals(CensorAppearance.Type.CUSTOM, new SettingsRepository(activity).loadAppearance().getType());
            });
        }
    }

    @Test public void allEightEffectsShareAnUnclippedPortraitPhoneAndRemainDistinct() throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Context context = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_SubHub);
                String[] styles = {"box", "pixelate", "blur", "custom", "static", "glitch", "tape", "error"};
                String[] titles = {"Blackout", "Pixelate", "Blur", "Custom Image", "TV Static", "Glitch", "Privacy Tape", "Error Popup"};
                Bitmap[] previews = new Bitmap[styles.length];
                Bitmap sheet = Bitmap.createBitmap(680, 270, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(sheet);
                canvas.drawColor(context.getColor(R.color.background));
                Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
                text.setColor(context.getColor(R.color.text_primary));
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(13);
                File output = new File(context.getExternalFilesDir(null), "style-qa");
                assertTrue(output.isDirectory() || output.mkdirs());
                for (int index = 0; index < styles.length; index++) {
                    CensorPreviewView preview = new CensorPreviewView(context, null);
                    preview.setTag(styles[index]);
                    preview.measure(View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(88, View.MeasureSpec.EXACTLY));
                    preview.layout(0, 0, 150, 88);
                    Bitmap bitmap = Bitmap.createBitmap(150, 88, Bitmap.Config.ARGB_8888);
                    preview.draw(new Canvas(bitmap));
                    previews[index] = bitmap;
                    int minX=150, minY=88, maxX=-1, maxY=-1;
                    for (int y=0; y<88; y++) for (int x=0; x<150; x++) {
                        if (Color.alpha(bitmap.getPixel(x,y)) > 0) {
                            minX=Math.min(minX,x); maxX=Math.max(maxX,x);
                            minY=Math.min(minY,y); maxY=Math.max(maxY,y);
                        }
                    }
                    assertTrue("Preview must be visibly portrait", maxY-minY > (maxX-minX)*1.6f);
                    assertTrue("Phone cannot clip the canvas", minX>0 && minY>0 && maxX<149 && maxY<87);
                    assertEquals("Common phone frame centered", 74.5f, (minX+maxX)/2f, 1f);
                    for (int earlier=0; earlier<index; earlier++) assertFalse(bitmap.sameAs(previews[earlier]));
                    write(bitmap, new File(output, styles[index]+".png"));
                    int left=10+(index%4)*170, top=12+(index/4)*130;
                    canvas.drawBitmap(bitmap,left,top,null);
                    canvas.drawText(titles[index],left+75,top+108,text);
                }
                write(sheet,new File(output,"phone-style-contact-sheet.png"));
                for (Bitmap bitmap:previews) bitmap.recycle();
                sheet.recycle();
            } catch (Exception exception) { failure.set(exception); }
        });
        if (failure.get()!=null) throw failure.get();
    }
    private static void write(Bitmap bitmap, File file) throws java.io.IOException {
        try(FileOutputStream stream=new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)); }
    }
}
