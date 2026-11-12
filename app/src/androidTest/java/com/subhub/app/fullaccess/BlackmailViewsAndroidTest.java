package com.subhub.app.fullaccess;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Base64;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** Synthetic Android presenter verification, independent of any personal data or screen-sharing grant. */
public final class BlackmailViewsAndroidTest {
    @Test public void compactContactPhotosAndChronologicalThreadFitSmallPhones() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var messages = List.of(
                new JSONObject().put("id", "new").put("detail", "Perfect, see you there.")
                        .put("time", 1762947120000L).put("outgoing", true),
                new JSONObject().put("id", "old").put("detail", "Want to meet by the station at six?")
                        .put("time", 1762947000000L).put("outgoing", false));
        instrumentation.runOnMainSync(() -> {
            Context app = instrumentation.getTargetContext();
            Configuration configuration = new Configuration(app.getResources().getConfiguration());
            configuration.fontScale = 1.3f;
            Context themed = new ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_SubHub);
            LinearLayout overview = panel(themed);
            LinearLayout contacts = BlackmailViews.section(overview, R.string.full_access_contacts, R.drawable.ic_nav_people);
            Bitmap thumbnail = BlackmailViews.photo(avatar());
            assertNotNull(thumbnail);
            LinearLayout first = BlackmailViews.row(contacts, "Alex Rivera", "+1 202-555-0104",
                    0, true, thumbnail, null);
            BlackmailViews.separator(contacts);
            LinearLayout second = BlackmailViews.row(contacts, "Jordan Lee", "+1 202-555-0147",
                    0, true, null, null);
            assertNotNull(first.findViewWithTag("blackmail_photo"));
            assertNull(second.findViewWithTag("blackmail_photo"));
            LinearLayout thread = panel(themed);
            LinearLayout conversations = BlackmailViews.section(overview,
                    R.string.blackmail_messages, android.R.drawable.ic_dialog_email);
            LinearLayout conversation = BlackmailViews.row(conversations, "Alex Rivera", "Perfect, see you there.",
                    0, true, null, () -> BlackmailViews.timeline(thread, messages, message -> message.optString("detail")));
            LinearLayout notifications = BlackmailViews.section(overview,
                    R.string.full_access_notifications, R.drawable.ic_notification_subhub);
            String count = themed.getResources().getQuantityString(R.plurals.blackmail_notification_count, 3, 3);
            LinearLayout group = BlackmailViews.row(notifications, "Calendar", count, R.drawable.ic_notification_subhub,
                    false, null, () -> BlackmailViews.row(notifications, "Studio booking",
                            "Your session starts at six.", 0, false, null, null));
            assertTrue(conversation.performClick());
            assertTrue(group.performClick());
            measure(overview);
            measure(thread);
            assertTrue(first.getHeight() >= dp(themed, 48) && first.getHeight() <= dp(themed, 66));
            assertTrue(second.getHeight() >= dp(themed, 48) && second.getHeight() <= dp(themed, 66));
            assertTrue(conversation.getHeight() >= dp(themed, 48));
            View incoming = thread.findViewWithTag("blackmail_incoming");
            View outgoing = thread.findViewWithTag("blackmail_outgoing");
            assertNotNull(incoming);
            assertNotNull(outgoing);
            assertTrue("Older incoming message appears above the newer sent reply", incoming.getTop() < outgoing.getTop());
            assertTrue("Sent message is aligned toward the opposite edge", incoming.getLeft() < outgoing.getLeft());
            assertTrue(labels(notifications).contains("Studio booking"));
            assertTrue(labels(notifications).contains(count));
            if ("true".equals(InstrumentationRegistry.getArguments().getString("blackmail_preview"))) {
                capture(app, overview, "blackmail-overview-synthetic.png");
                capture(app, thread, "blackmail-thread-synthetic.png");
            }
        });
    }

    private static LinearLayout panel(Context context) {
        LinearLayout view = PersonUi.column(context);
        view.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        view.setBackgroundColor(context.getColor(R.color.background));
        return view;
    }

    private static void measure(View view) {
        int width = dp(view.getContext(), 320);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static List<String> labels(View view) {
        List<String> text = new ArrayList<>();
        if (view instanceof TextView label) text.add(label.getText().toString());
        if (view instanceof android.view.ViewGroup group)
            for (int index = 0; index < group.getChildCount(); index++) text.addAll(labels(group.getChildAt(index)));
        return text;
    }

    private static String avatar() {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(0xff51437a);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xffcdbcec);
            canvas.drawCircle(48, 34, 17, paint);
            canvas.drawRoundRect(19, 55, 77, 104, 24, 24, paint);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes));
            return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        } finally { bitmap.recycle(); }
    }

    private static void capture(Context context, View view, String filename) {
        File directory = context.getExternalCacheDir();
        assertNotNull(directory);
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
            view.draw(new Canvas(bitmap));
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (IOException unavailable) { throw new AssertionError(unavailable); }
        finally { bitmap.recycle(); }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
