package com.subhub.app.journal;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.util.FormRow;

/** Shared native surfaces, metadata and actions keep every Journal state in the same hierarchy. */
final class JournalUi {
    private JournalUi() { }

    static LinearLayout surface(LinearLayout parent) {
        LinearLayout surface = PersonUi.card(parent);
        LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) surface.getLayoutParams();
        size.bottomMargin = parent.getResources().getDimensionPixelSize(R.dimen.ui_gap_group); surface.setLayoutParams(size);
        return surface;
    }

    static TextView text(LinearLayout parent, CharSequence value, int sp, boolean primary) {
        TextView text = PersonUi.text(parent, value, false);
        text.setTextSize(sp);
        text.setTextColor(parent.getContext().getColor(primary ? R.color.text_primary : R.color.text_secondary));
        text.setPadding(0, 0, 0, 0); text.setLineSpacing(PersonUi.dp(parent.getContext(), 4), 1);
        return text;
    }

    static TextView heading(LinearLayout parent, CharSequence label, int icon) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView symbol = new ImageView(context); symbol.setImageResource(icon);
        symbol.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.accent_text)));
        symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams imageSize = new LinearLayout.LayoutParams(PersonUi.dp(context, 22), PersonUi.dp(context, 22));
        imageSize.setMarginEnd(PersonUi.dp(context, 10)); row.addView(symbol, imageSize);
        TextView title = new TextView(context); title.setText(label); title.setTextSize(15);
        title.setTextColor(context.getColor(R.color.text_primary)); title.setTypeface(null, Typeface.BOLD);
        ViewCompat.setAccessibilityHeading(title, true); row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, -2);
        rowSize.bottomMargin = PersonUi.dp(context, 12); parent.addView(row, rowSize);
        return title;
    }

    static void divider(LinearLayout parent) {
        View divider = new View(parent.getContext()); divider.setBackgroundColor(parent.getContext().getColor(R.color.outline_subtle));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, PersonUi.dp(parent.getContext(), 1));
        size.topMargin = PersonUi.dp(parent.getContext(), 10); size.bottomMargin = PersonUi.dp(parent.getContext(), 10);
        parent.addView(divider, size);
    }

    static TextView button(Context context, LinearLayout parent, int label, int icon, Runnable action, boolean primary) {
        AppCompatButton button = (AppCompatButton) android.view.LayoutInflater.from(context).inflate(R.layout.view_ux_action, parent, false);
        button.setText(label); button.setTextSize(14); button.setAllCaps(false); button.setMinHeight(PersonUi.dp(context, 48));
        button.setBackgroundResource(primary ? R.drawable.bg_role_primary : R.drawable.bg_outline_button);
        button.setBackgroundTintList(null);
        int color = context.getColor(primary ? R.color.primary_action_text : R.color.accent_text);
        button.setTextColor(color);
        if (icon != 0) {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            button.setCompoundDrawableTintList(ColorStateList.valueOf(color));
            button.setCompoundDrawablePadding(PersonUi.dp(context, 8));
        }
        button.setPadding(PersonUi.dp(context, 12), PersonUi.dp(context, 10), PersonUi.dp(context, 12), PersonUi.dp(context, 10));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    static TextView action(LinearLayout parent, int label, int icon, Runnable action, boolean primary) {
        TextView button = button(parent.getContext(), parent, label, icon, action, primary);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = PersonUi.dp(parent.getContext(), 8); parent.addView(button, size);
        return button;
    }

    static LinearLayout[] pair(LinearLayout parent) {
        FormRow row = new FormRow(parent.getContext(), null); parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout first = PersonUi.column(parent.getContext()), second = PersonUi.column(parent.getContext());
        // FormRow normalizes its first TextView as a field label, never an action's touch target.
        first.addView(new View(parent.getContext()), new LinearLayout.LayoutParams(0, 0));
        second.addView(new View(parent.getContext()), new LinearLayout.LayoutParams(0, 0));
        row.addView(first, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(second, new LinearLayout.LayoutParams(0, -2, 1));
        return new LinearLayout[]{first, second};
    }

    static TextView state(LinearLayout parent, boolean shared) {
        TextView state = text(parent, parent.getContext().getString(shared ? R.string.journal_shared : R.string.journal_private), 12, false);
        state.setCompoundDrawablesRelativeWithIntrinsicBounds(shared ? R.drawable.ic_journal_share : R.drawable.ic_ux_lock, 0, 0, 0);
        state.setCompoundDrawableTintList(ColorStateList.valueOf(parent.getContext().getColor(R.color.accent_text)));
        state.setCompoundDrawablePadding(PersonUi.dp(parent.getContext(), 6));
        state.setPadding(0, PersonUi.dp(parent.getContext(), 8), 0, PersonUi.dp(parent.getContext(), 10));
        return state;
    }

    static void empty(LinearLayout parent, int icon, CharSequence label) {
        LinearLayout surface = surface(parent); surface.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView image = new ImageView(parent.getContext()); image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(parent.getContext().getColor(R.color.accent_text)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(PersonUi.dp(parent.getContext(), 32), PersonUi.dp(parent.getContext(), 32));
        size.topMargin = PersonUi.dp(parent.getContext(), 8); size.bottomMargin = PersonUi.dp(parent.getContext(), 12);
        surface.addView(image, size); TextView text = text(surface, label, 15, true); text.setGravity(Gravity.CENTER);
        text.setPadding(0, 0, 0, PersonUi.dp(parent.getContext(), 8)); text.setTag("journal_empty");
    }

    static LinearLayout progress(LinearLayout parent) {
        LinearLayout row = new LinearLayout(parent.getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(parent.getContext());
        spinner.setIndeterminateTintList(ColorStateList.valueOf(parent.getContext().getColor(R.color.accent_text)));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(PersonUi.dp(parent.getContext(), 24), PersonUi.dp(parent.getContext(), 24));
        size.setMarginEnd(PersonUi.dp(parent.getContext(), 12)); row.addView(spinner, size);
        TextView label = new TextView(parent.getContext()); label.setText(R.string.journal_working); label.setTextSize(13);
        label.setTextColor(parent.getContext().getColor(R.color.text_secondary)); row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, -2); rowSize.bottomMargin = PersonUi.dp(parent.getContext(), 12);
        parent.addView(row, rowSize); return row;
    }
}
