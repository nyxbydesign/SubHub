package com.subhub.app.util;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
        TextView role = root.findViewById(R.id.header_role_badge);
        if (role != null) role.setText(com.subhub.app.security.ControllerPinManager.isDomModeActive()
                ? R.string.role_dom : R.string.role_sub);
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
        int controlMinimum = Math.round(48 * density);
        control.setMinWidth(controlMinimum);
        control.setMinimumWidth(controlMinimum);
        control.setSingleLine(false);
        control.setEllipsize(null);
        TextView badge = root.findViewById(R.id.header_role_badge);
        badge.setVisibility(control.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
        TextView back = backButton(root);
        back.setText("");
        back.setContentDescription(root.getResources().getString(R.string.back));
        androidx.appcompat.widget.TooltipCompat.setTooltipText(back,
                root.getResources().getString(R.string.back));
        back.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_shell_back, 0, 0, 0);
        back.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(
                root.getContext().getColor(R.color.text_primary)));
        int headerWidth = Math.round(widthDp * density) - 2 * root.getResources()
                .getDimensionPixelSize(R.dimen.page_margin);
        int controlWidth = control.getVisibility() == View.GONE ? 0
                : Math.max(control.getMinWidth(), (int) Math.ceil(
                control.getPaint().measureText(control.getText().toString()))
                + control.getPaddingLeft() + control.getPaddingRight());
        int backWidth = back.getVisibility() == View.GONE ? 0
                : Math.round(48 * density);
        ViewGroup.MarginLayoutParams bodyParams =
                (ViewGroup.MarginLayoutParams) root.findViewById(R.id.primary_header_copy).getLayoutParams();
        int iconWidth = root.getResources().getDimensionPixelSize(R.dimen.primary_header_icon_size);
        int textWidth = headerWidth - header.getPaddingLeft() - header.getPaddingRight()
                - backWidth - iconWidth - Math.round(19 * density) - controlWidth;
        if (badge.getVisibility() == View.VISIBLE) textWidth -= (int) Math.ceil(
                badge.getPaint().measureText(badge.getText().toString()))
                + badge.getPaddingLeft() + badge.getPaddingRight() + Math.round(8 * density);
        boolean showIcon = hasIcon
                && title.getPaint().measureText(title.getText().toString()) <= textWidth;
        icon.setVisibility(showIcon ? View.VISIBLE : View.GONE);
        bodyParams.setMarginStart(showIcon || backWidth > 0 ? Math.round(11 * density) : 0);
        View body = root.findViewById(R.id.primary_header_copy);
        body.setLayoutParams(bodyParams);
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
