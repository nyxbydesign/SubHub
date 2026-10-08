package com.subhub.app.util;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;

import com.subhub.app.R;

/** Shared, accessible header geometry for primary destinations and secondary pages. */
public final class PrimaryHeader {
    private PrimaryHeader() {}

    public static void bind(View root, @DrawableRes int iconRes, @StringRes int titleRes,
            @StringRes int subtitleRes) {
        ImageView icon = root.findViewById(R.id.primary_header_icon);
        TextView title = root.findViewById(R.id.primary_header_title);
        TextView subtitle = root.findViewById(R.id.primary_header_subtitle);
        icon.setImageResource(iconRes);
        icon.setContentDescription(null);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ViewCompat.setAccessibilityHeading(title, true);
        title.setText(titleRes);
        subtitle.setText(subtitleRes == 0 ? "" : root.getResources().getString(subtitleRes));
        subtitle.setVisibility(subtitleRes == 0 ? View.GONE : View.VISIBLE);
        fitHeader(root, icon, title, iconRes != 0);
    }

    public static void bindSecondary(View root, @StringRes int titleRes,
            boolean controllerControl) {
        backButton(root).setVisibility(View.VISIBLE);
        editLockButton(root).setVisibility(controllerControl ? View.VISIBLE : View.GONE);
        bind(root, 0, titleRes, 0);
    }

    private static void fitHeader(View root, ImageView icon, TextView title, boolean hasIcon) {
        TextView control = editLockButton(root);
        ViewGroup header = (ViewGroup) view(root);
        float density = root.getResources().getDisplayMetrics().density;
        int widthDp = root.getResources().getConfiguration().screenWidthDp;
        boolean compact = widthDp < 360
                || root.getResources().getConfiguration().fontScale >= 1.3f;
        int controlMinimum = Math.round((compact ? 64 : 88) * density);
        control.setMinWidth(controlMinimum);
        control.setMinimumWidth(controlMinimum);
        int headerWidth = Math.round(widthDp * density) - 2 * root.getResources()
                .getDimensionPixelSize(R.dimen.page_margin);
        int controlWidth = control.getVisibility() == View.GONE ? 0
                : Math.max(control.getMinWidth(), (int) Math.ceil(
                control.getPaint().measureText(control.getText().toString()))
                + control.getPaddingLeft() + control.getPaddingRight());
        TextView back = backButton(root);
        int backWidth = back.getVisibility() == View.GONE ? 0
                : Math.max(back.getMinWidth(), (int) Math.ceil(
                        back.getPaint().measureText(back.getText().toString())));
        ViewGroup.MarginLayoutParams bodyParams =
                (ViewGroup.MarginLayoutParams) ((View) title.getParent()).getLayoutParams();
        int iconWidth = root.getResources().getDimensionPixelSize(R.dimen.primary_header_icon_size);
        int textWidth = headerWidth - header.getPaddingLeft() - header.getPaddingRight()
                - backWidth - iconWidth - Math.round(19 * density) - controlWidth;
        boolean stacked = root.getResources().getConfiguration().fontScale >= 1.3f;
        if (stacked) {
            textWidth = headerWidth - header.getPaddingLeft() - header.getPaddingRight()
                    - backWidth - iconWidth - Math.round(11 * density);
        }
        boolean showIcon = hasIcon
                && title.getPaint().measureText(title.getText().toString()) <= textWidth;
        icon.setVisibility(showIcon ? View.VISIBLE : View.GONE);
        bodyParams.setMarginStart(showIcon || backWidth > 0 ? Math.round(11 * density) : 0);
        View body = (View) title.getParent();
        body.setLayoutParams(bodyParams);
        if (stacked && body.getParent() == header) {
            // Keep the title and explanation together; move the control to its own row
            // when enlarged text needs the full card width.
            LinearLayout column = (LinearLayout) header;
            LinearLayout row = new LinearLayout(root.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            column.removeView(icon);
            column.removeView(body);
            column.removeView(back);
            row.addView(back);
            row.addView(icon);
            row.addView(body);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(row, 0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams controlParams = (LinearLayout.LayoutParams)
                    control.getLayoutParams();
            controlParams.gravity = android.view.Gravity.END;
            controlParams.setMarginStart(0);
            controlParams.topMargin = Math.round(8 * density);
            control.setLayoutParams(controlParams);
        }
    }

    public static View view(View root) {
        return root.findViewById(R.id.primary_header);
    }

    public static TextView backButton(View root) {
        return root.findViewById(R.id.button_back);
    }

    public static TextView editLockButton(View root) {
        return root.findViewById(R.id.button_edit_lock);
    }

    public static TextView subtitle(View root) {
        return root.findViewById(R.id.primary_header_subtitle);
    }
}
