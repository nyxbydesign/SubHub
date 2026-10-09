package com.subhub.app.capture.export;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.subhub.app.R;
import com.subhub.app.capture.ExportActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;

/** Sub exports synthetic media and requests deletion only after both outputs have been verified. */
public final class GallerySubModeAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences prefs;
    private Map<String, ?> original;
    private final List<Uri> owned = new ArrayList<>();
    @Before public void fixture() {
        prefs = ExportSettings.preferences(context); original = prefs.getAll();
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterSubMode();
        Intents.init();
    }
    @After public void restore() {
        Intents.release();
        for (Uri uri : owned) try { context.getContentResolver().delete(uri, null, null); } catch (RuntimeException ignored) { }
        SharedPreferenceTestRestore.restore(prefs, original);
        ControllerPinManager.enterSubMode();
    }
    @Test public void subChoosesOutputOptionsAndExportsWithoutChangingLookOrAreas() throws Exception {
        ExportSettings.reset(context);
        prefs.edit().putString(SettingsRepository.KEY_CENSOR_TYPE, "pixelate")
                .putBoolean(SettingsRepository.KEY_REVERSE_MODE, true)
                .putFloat(SettingsRepository.KEY_REVERSE_STRENGTH, 1f)
                .putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.emptySet()).commit();
        File videoFile = new File(context.getCacheDir(), "sub-gallery-synthetic.mp4");
        long[] times = new long[30]; for (int i=0;i<times.length;i++) times[i]=i*50000L;
        ExportFoundationAndroidTest.generate(videoFile, times, 90);
        Uri video = seed(videoFile, true);
        File photoFile = new File(context.getCacheDir(), "sub-gallery-synthetic.jpg");
        Bitmap photo = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
        photo.eraseColor(Color.rgb(127, 67, 164));
        try (OutputStream out = new FileOutputStream(photoFile)) { assertTrue(photo.compress(Bitmap.CompressFormat.JPEG,95,out)); }
        finally { photo.recycle(); }
        Uri image = seed(photoFile, false);
        Intent chosen = new Intent(); ClipData media = ClipData.newRawUri("Synthetic review media", image);
        media.addItem(new ClipData.Item(video)); chosen.setClipData(media);
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(new Instrumentation.ActivityResult(Activity.RESULT_OK, chosen));
        await(() -> !ExportService.isRunning(), 10000);
        try (ActivityScenario<ExportActivity> page = ActivityScenario.launch(ExportActivity.class)) {
            page.onActivity(a -> {
                assertEquals(.45f, a.findViewById(R.id.export_look_button).getAlpha(), .01f);
                assertEquals(.45f, a.findViewById(R.id.export_areas_button).getAlpha(), .01f);
                a.findViewById(R.id.export_look_button).performClick();
                a.findViewById(R.id.export_areas_button).performClick();
                assertFalse(ControllerPinManager.isDomModeActive());
                assertEquals("pixelate", prefs.getString(SettingsRepository.KEY_CENSOR_TYPE, ""));
                assertTrue(prefs.getStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Set.of()).isEmpty());
                ((RadioGroup) a.findViewById(R.id.export_quality_choices)).check(R.id.export_quality_high);
                ((RadioGroup) a.findViewById(R.id.export_detection_choices)).check(R.id.export_detection_fast);
                ((CompoundButton) a.findViewById(R.id.export_mute)).setChecked(true);
                assertEquals(ExportOptions.Quality.HIGH, ExportSettings.options(context).quality);
                assertEquals(2, ExportSettings.options(context).detectEvery);
                assertTrue(ExportSettings.options(context).mute);
            });
            onView(withHint(R.string.controller_pin_label)).check(doesNotExist());
            onView(withId(R.id.button_pick_images)).perform(scrollTo(),click());
            page.onActivity(a -> ((CompoundButton) a.findViewById(R.id.switch_delete_originals)).setChecked(true));
            onView(withText(R.string.export_delete_warning_enable)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            page.recreate();
            page.onActivity(a -> {
                assertTrue(((CompoundButton) a.findViewById(R.id.switch_delete_originals)).isChecked());
                assertTrue(a.findViewById(R.id.export_start_batch_button).isEnabled());
                assertFalse(ControllerPinManager.isDomModeActive());
                capture(a, "gallery-sub-controls");
            });
            onView(withId(R.id.export_start_batch_button)).perform(scrollTo(),click());
            await(() -> {
                try (ExportJobStore store = new ExportJobStore(context)) {
                    String job = store.latest();
                    List<ExportJobStore.Item> items = job == null ? List.of() : store.items(job);
                    return items.size()==2 && items.stream().allMatch(item -> item.state==ExportJobStore.State.SAVED || item.state==ExportJobStore.State.FAILED);
                }
            },90000);
            try (ExportJobStore store = new ExportJobStore(context)) {
                String job = store.latest();
                assertTrue(store.options(job).deleteOriginals);
                for (ExportJobStore.Item item : store.items(job)) {
                    assertEquals(item.message, ExportJobStore.State.SAVED,item.state);
                    Uri output = Uri.parse(item.output); owned.add(output);
                    try (InputStream in = context.getContentResolver().openInputStream(output)) { assertNotNull(in); assertTrue(in.read()>=0); }
                    try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(item.source))) { assertNotNull(in); }
                }
            }
            await(() -> !ExportService.isRunning(),10000);
            AtomicBoolean deletionReady = new AtomicBoolean();
            await(() -> {
                page.onActivity(a -> deletionReady.set(
                        a.findViewById(R.id.export_delete_saved).getVisibility() == View.VISIBLE));
                return deletionReady.get();
            },10000);
            onView(withId(R.id.export_delete_saved)).perform(scrollTo(),click());
            onView(withText(R.string.export_delete_saved)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            UiObject2 allow=device.wait(Until.findObject(By.pkg("com.google.android.providers.media.module").text("Allow")),5000);
            if(allow==null)allow=device.findObject(By.pkg("com.google.android.providers.media.module").res("android:id/button1"));
            assertNotNull("Android must retain deletion consent",allow);
            allow.click();
            await(() -> !readable(image) && !readable(video),10000);
            assertFalse(ControllerPinManager.isDomModeActive());
            for (Uri uri : owned) if (!uri.equals(image) && !uri.equals(video)) assertTrue(readable(uri));
        }
    }
    private boolean readable(Uri uri) {
        try (InputStream in=context.getContentResolver().openInputStream(uri)) { return in!=null; }
        catch(IOException|RuntimeException missing){return false;}
    }
    private Uri seed(File file, boolean video) throws IOException {
        ContentValues values=new ContentValues(); values.put(MediaStore.MediaColumns.DISPLAY_NAME,"UX synthetic "+file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE,video?"video/mp4":"image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,(video?"Movies":"Pictures")+"/SubHubTest");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(video?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        assertNotNull(uri); owned.add(uri);
        try(InputStream in=new FileInputStream(file);OutputStream out=context.getContentResolver().openOutputStream(uri)){
            byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))>=0)out.write(buffer,0,count);
        }
        values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(uri,values,null,null);return uri;
    }
    private static void await(java.util.function.BooleanSupplier ready,long timeout){
        long until=SystemClock.uptimeMillis()+timeout;
        while(!ready.getAsBoolean()&&SystemClock.uptimeMillis()<until)SystemClock.sleep(100);
        assertTrue("Expected Gallery state before timeout",ready.getAsBoolean());
    }
    private static void capture(Activity a,String name){
        View root=a.getWindow().getDecorView();root.post(()->{
            Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));
            try(OutputStream out=new FileOutputStream(new File(a.getFilesDir(),"review-"+name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}
            catch(IOException error){throw new AssertionError(error);}finally{image.recycle();}
        });
    }
}
