package com.subhub.app.update;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real native TextView rendering of public synthetic release notes, not a phone capture. */
@RunWith(AndroidJUnit4.class)
public final class ReleaseNotesFormattingAndroidTest {
    private static final String NOTES = "### Improvements\n\n- **Readable notes** — A long release description wraps onto several lines without losing the bullet alignment or leaving Markdown markers visible.\n- Smaller follow-up improvement.";
    private Context context;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("subhub_updates", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("automatic_checks", false).commit();
        new UpdateStateStore(context).setReleaseHistory(Collections.singletonList(
                new ReleaseHistoryItem("0.6.5-dev.56", "v0.6.5-dev.56", NOTES,
                        "https://github.com/confiteor48/SubHub/releases", "2026-10-08", true)));
        ControllerPinManager.enterDomMode();
    }

    @After public void cleanup() {
        context.getSharedPreferences("subhub_updates", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("automatic_checks", false).commit();
        ControllerPinManager.enterSubMode();
    }

    @Test public void expandedActivityUsesRealBulletsAndBoldHeadings() {
        try (ActivityScenario<UpdatesActivity> scenario = ActivityScenario.launch(UpdatesActivity.class)) {
            scenario.onActivity(activity -> {
                TextView details = activity.findViewById(R.id.release_details);
                assertNotNull(details);
                ((View) details.getParent()).performClick();
                assertEquals(View.VISIBLE, details.getVisibility());
                assertFormatting(details);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertTrue(
                    ((TextView) activity.findViewById(R.id.release_details)).getLayout().getLineCount() > 3));
        }
    }

    @Test public void wrappedNativeBulletsAlignAtNormalAndLargeTextAndSupportRtl() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int size : new int[] {16, 26}) {
                TextView view = new TextView(context);
                view.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                view.setTextSize(size);
                view.setTextColor(Color.WHITE);
                view.setBackgroundColor(Color.rgb(19, 17, 26));
                view.setText(ReleaseNotesFormatter.forView(view, NOTES, "Unavailable"));
                int width = Math.round(280 * context.getResources().getDisplayMetrics().density);
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                view.layout(0, 0, width, view.getMeasuredHeight());
                assertFormatting(view);
                Layout layout = view.getLayout();
                int bullet = view.getText().toString().indexOf(ReleaseNotesFormatter.BULLET);
                int first = layout.getLineForOffset(bullet);
                assertTrue(layout.getLineStart(first + 1) < view.getText().toString().indexOf('\n', bullet));
                assertEquals("Wrapped text hangs under the first text character",
                        layout.getPrimaryHorizontal(bullet + ReleaseNotesFormatter.BULLET.length()),
                        layout.getPrimaryHorizontal(layout.getLineStart(first + 1)), 2f);
                export(view, "notes-" + size + ".png");
                view.setTextDirection(View.TEXT_DIRECTION_RTL);
                view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                view.setText(ReleaseNotesFormatter.forView(view,
                        "### تحسينات\n- تحديث طويل لتحسين عرض الملاحظات مع نقاط واضحة ومحاذاة مناسبة عند التفاف النص إلى السطر التالي.", ""));
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                view.layout(0, 0, width, view.getMeasuredHeight());
                assertTrue(view.getLayout().getLineCount() > 2);
                Spanned rtl = (Spanned) view.getText();
                assertEquals(1, rtl.getSpans(0, rtl.length(), LeadingMarginSpan.class).length);
                assertEquals(-1, view.getLayout().getParagraphDirection(1));
            }
        });
    }

    private static void assertFormatting(TextView view) {
        assertTrue(view.getText() instanceof Spanned);
        Spanned text = (Spanned) view.getText();
        assertTrue(text.toString().contains("\n" + ReleaseNotesFormatter.BULLET + "Readable notes"));
        assertFalse(text.toString().contains("**"));
        assertFalse(text.toString().contains("\n- "));
        assertEquals(2, text.getSpans(0, text.length(), LeadingMarginSpan.class).length);
        StyleSpan[] bold = text.getSpans(0, text.length(), StyleSpan.class);
        assertEquals(2, bold.length);
        for (StyleSpan span : bold) assertEquals(Typeface.BOLD, span.getStyle());
        assertEquals("Improvements", text.subSequence(text.getSpanStart(bold[0]), text.getSpanEnd(bold[0])).toString());
        assertEquals("Readable notes", text.subSequence(text.getSpanStart(bold[1]), text.getSpanEnd(bold[1])).toString());
    }

    private void export(TextView view, String name) {
        File directory = new File(context.getExternalFilesDir(null), "notes-format-qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (Exception exception) { throw new AssertionError(exception); }
        finally { bitmap.recycle(); }
    }
}
