package com.subhub.app.fullaccess;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.util.UiIdentity;
import java.util.Locale;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;
import org.json.JSONObject;

/** Compact rows and thread bubbles shared by the live and on-device Blackmail viewers. */
final class BlackmailViews {
    private BlackmailViews() { }

    static TextView text(Context context, CharSequence text, int size, int color, boolean strong) {
        TextView view = new TextView(context);
        view.setText(text);
        UiIdentity.textSize(view, size);
        view.setTextColor(context.getColor(color));
        if (strong) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    static LinearLayout section(LinearLayout parent, int label, int icon) {
        Context context = parent.getContext();
        LinearLayout card = PersonUi.card(parent);
        LinearLayout heading = horizontal(context);
        heading.setPadding(0, 0, 0, dp(context, 8));
        leading(heading, context, icon, "", null);
        TextView name = text(context, context.getText(label), R.dimen.ui_text_section, R.color.text_primary, true);
        ViewCompat.setAccessibilityHeading(name, true);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    static LinearLayout entryRow(LinearLayout parent) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(context, 10);
        parent.addView(row, size);
        return row;
    }

    static void entry(LinearLayout parent, int label, int resource, String scope, Runnable open) {
        Context context = parent.getContext();
        LinearLayout tile = PersonUi.column(context);
        tile.setTag("blackmail_entry:" + scope);
        tile.setMinimumHeight(dp(context, 106));
        int padding = dp(context, 12);
        tile.setPaddingRelative(padding, padding, padding, padding);
        tile.setBackgroundResource(R.drawable.bg_card);
        tile.setForeground(ripple(context, null));
        tile.setFocusable(true);
        ViewCompat.setScreenReaderFocusable(tile, true);
        tile.setOnClickListener(view -> open.run());

        FrameLayout badge = new FrameLayout(context);
        int badgeColor = switch (scope) {
            case "contacts", "files" -> R.color.blackmail_entry_iris;
            case "notifications", "screen" -> R.color.blackmail_entry_rose;
            default -> R.color.blackmail_entry_violet;
        };
        badge.setBackground(shape(context, badgeColor, 14));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ImageView glyph = icon(context, resource, R.color.text_primary);
        glyph.setTag("blackmail_entry_icon");
        glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
        glyph.setPadding(0, 0, 0, 0);
        badge.addView(glyph, new FrameLayout.LayoutParams(dp(context, 22), dp(context, 22), Gravity.CENTER));
        FrameLayout icons = new FrameLayout(context);
        icons.addView(badge, new FrameLayout.LayoutParams(dp(context, 44), dp(context, 44), Gravity.START));
        ImageView arrow = icon(context, R.drawable.ic_overview_chevron, R.color.accent_text);
        icons.addView(arrow, new FrameLayout.LayoutParams(dp(context, 18), dp(context, 18),
                Gravity.END | Gravity.CENTER_VERTICAL));
        tile.addView(icons, new LinearLayout.LayoutParams(-1, dp(context, 44)));

        TextView title = text(context, context.getText(label), R.dimen.ui_text_section, R.color.text_primary, true);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(-1, -2);
        titleSize.topMargin = dp(context, 6);
        tile.addView(title, titleSize);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1);
        if (parent.getChildCount() > 0) size.setMarginStart(dp(context, 10));
        parent.addView(tile, size);
    }

    static LinearLayout row(LinearLayout parent, CharSequence title, CharSequence detail, int icon,
            boolean initials, Bitmap photo, Runnable open) {
        Context context = parent.getContext();
        LinearLayout row = horizontal(context);
        row.setMinimumHeight(dp(context, 48));
        row.setPadding(0, dp(context, 5), 0, dp(context, 5));
        if (icon != 0 || initials || photo != null) leading(row, context, icon, title, photo);
        LinearLayout copy = PersonUi.column(context);
        TextView name = text(context, title, R.dimen.ui_text_row_title, R.color.text_primary, true);
        copy.addView(name, new LinearLayout.LayoutParams(-1, -2));
        if (!TextUtils.isEmpty(detail)) {
            TextView description = text(context, detail, R.dimen.ui_text_body, R.color.text_secondary, false);
            description.setMaxLines(2);
            description.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(description, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        if (open != null) {
            ImageView chevron = icon(context, R.drawable.ic_overview_chevron, R.color.accent_text);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(context, 16), dp(context, 16));
            size.setMarginStart(dp(context, 8));
            row.addView(chevron, size);
            row.setOnClickListener(view -> open.run());
            row.setFocusable(true);
            row.setForeground(ripple(context, null));
            ViewCompat.setScreenReaderFocusable(row, true);
        }
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    static void separator(LinearLayout parent) {
        View line = new View(parent.getContext());
        line.setBackgroundColor(parent.getContext().getColor(R.color.outline_subtle));
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(parent.getContext(), 1)));
    }

    static void action(LinearLayout parent, int label, Runnable click, boolean emphasized) {
        Context context = parent.getContext();
        AppCompatButton button = new AppCompatButton(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 48));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(context, 14), dp(context, 5), dp(context, 14), dp(context, 5));
        UiIdentity.textSize(button, R.dimen.ui_text_row_title);
        button.setTextColor(context.getColor(R.color.accent_text));
        button.setBackground(ripple(context, emphasized ? shape(context, R.color.control_checked_surface, 14) : null));
        ViewCompat.setBackgroundTintList(button, null);
        button.setOnClickListener(view -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = dp(context, 4);
        parent.addView(button, params);
    }

    static void fileDownload(LinearLayout row, CharSequence label, Runnable click) {
        Context context = row.getContext();
        androidx.appcompat.widget.AppCompatImageButton button = new androidx.appcompat.widget.AppCompatImageButton(context);
        button.setImageResource(R.drawable.ic_tab_export);
        button.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.accent_text)));
        button.setBackground(ripple(context, null));
        button.setContentDescription(label);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(button, label);
        button.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        button.setOnClickListener(view -> click.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48));
        size.setMarginStart(dp(context, 4));
        row.addView(button, size);
    }

    static void timeline(LinearLayout parent, List<JSONObject> newestFirst, Function<JSONObject, String> body) {
        Context context = parent.getContext();
        LocalDate last = null;
        ZoneId zone = ZoneId.systemDefault();
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            JSONObject item = newestFirst.get(index);
            long time = item.optLong("time");
            if (time > 0) {
                LocalDate day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate();
                if (!day.equals(last)) timestamp(parent, DateUtils.formatDateTime(context, time,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
                last = day;
            }
            bubble(parent, body.apply(item),
                    time > 0 ? DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_TIME) : "",
                    item.optBoolean("outgoing"));
        }
    }

    private static void timestamp(LinearLayout parent, String label) {
        Context context = parent.getContext();
        TextView stamp = text(context, label, R.dimen.ui_text_label, R.color.text_secondary, false);
        stamp.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, 8);
        params.bottomMargin = dp(context, 6);
        parent.addView(stamp, params);
    }

    private static void bubble(LinearLayout parent, String body, CharSequence time, boolean outgoing) {
        Context context = parent.getContext();
        LinearLayout bubble = PersonUi.column(context);
        bubble.setTag(outgoing ? "blackmail_outgoing" : "blackmail_incoming");
        bubble.setBackground(shape(context, outgoing ? R.color.control_checked_surface : R.color.surface_card, 18));
        bubble.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        TextView message = text(context, body, R.dimen.ui_text_row_title, R.color.text_primary, false);
        // Each side retains a margin from the opposite edge at narrow widths and large font scales.
        int window = context.getResources().getDisplayMetrics().widthPixels;
        message.setMaxWidth(Math.min(dp(context, 420), Math.max(dp(context, 120), window * 3 / 4 - dp(context, 48))));
        bubble.addView(message, new LinearLayout.LayoutParams(-2, -2));
        if (!TextUtils.isEmpty(time)) {
            TextView timestamp = text(context, time, R.dimen.ui_text_caption, R.color.text_secondary, false);
            LinearLayout.LayoutParams timestampParams = new LinearLayout.LayoutParams(-2, -2);
            timestampParams.gravity = Gravity.END;
            timestampParams.topMargin = dp(context, 4);
            bubble.addView(timestamp, timestampParams);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.gravity = outgoing ? Gravity.END : Gravity.START;
        params.bottomMargin = dp(context, 5);
        parent.addView(bubble, params);
    }

    static Bitmap photo(String encoded) {
        int maxBytes = FullAccessContactsSource.PHOTO_BYTES;
        int edge = FullAccessContactsSource.PHOTO_EDGE;
        if (encoded.isEmpty() || encoded.length() > ((maxBytes + 2) / 3) * 4) return null;
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            if (bytes.length < 3 || bytes.length > maxBytes
                    || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8 || bytes[2] != (byte) 0xff) return null;
            BitmapFactory.Options size = new BitmapFactory.Options();
            size.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, size);
            if (size.outWidth < 1 || size.outHeight < 1
                    || size.outWidth > edge || size.outHeight > edge) return null;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static void leading(LinearLayout row, Context context, int icon, CharSequence title, Bitmap photo) {
        FrameLayout chip = new FrameLayout(context);
        chip.setBackground(shape(context, R.color.control_checked_surface, 22));
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        chip.setClipToOutline(true);
        LinearLayout.LayoutParams dimensions = new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36));
        dimensions.setMarginEnd(dp(context, 10));
        row.addView(chip, dimensions);
        if (photo != null) {
            ImageView image = new ImageView(context);
            image.setTag("blackmail_photo");
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(photo);
            chip.addView(image, new FrameLayout.LayoutParams(-1, -1));
        } else if (icon != 0) {
            chip.addView(icon(context, icon, R.color.accent_text),
                    new FrameLayout.LayoutParams(dp(context, 19), dp(context, 19), Gravity.CENTER));
        } else {
            String name = title.toString().trim();
            String initial = name.isEmpty() ? "?" : new String(Character.toChars(name.codePointAt(0)));
            TextView fallback = text(context, initial.toUpperCase(Locale.getDefault()),
                    R.dimen.ui_text_label, R.color.accent_text, true);
            fallback.setGravity(Gravity.CENTER);
            chip.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private static LinearLayout horizontal(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private static ImageView icon(Context context, int resource, int color) {
        ImageView icon = new ImageView(context);
        icon.setImageResource(resource);
        icon.setImageTintList(ColorStateList.valueOf(context.getColor(color)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(context.getColor(color));
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    private static RippleDrawable ripple(Context context, GradientDrawable fill) {
        return new RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.accent_dim)),
                fill, shape(context, android.R.color.white, 14));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
