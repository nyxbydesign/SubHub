package com.subhub.app.people;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import com.subhub.app.util.UiIdentity;

/** Shared allowance geometry; only the Sub-owned version creates native checkable controls. */
public final class FeatureConsentView extends ViewGroup {
    private int columns = 1;
    private int[] heights = new int[0];
    private final int columnGap, rowGap;

    public FeatureConsentView(Context context) {
        super(context);
        columnGap = PersonUi.dp(context, 8); rowGap = PersonUi.dp(context, 4);
        setTag("feature_consent_grid");
    }

    public static LinearLayout card(LinearLayout parent) {
        LinearLayout card = PersonUi.card(parent);
        int inset = PersonUi.dp(parent.getContext(), 12); card.setPadding(inset, inset, inset, inset);
        TextView heading = PersonUi.text(card, parent.getContext().getString(R.string.sub_features_title), true);
        heading.setPadding(0, 0, 0, PersonUi.dp(parent.getContext(), 4));
        return card;
    }

    public CompoundButton editable(String key, int label, boolean allowed) {
        Cell cell = new Cell(getContext(), label);
        AppCompatCheckBox control = new AppCompatCheckBox(getContext()) {
            @Override public void setChecked(boolean value) {
                super.setChecked(value);
                cell.state(isChecked());
                ViewCompat.setStateDescription(this, getContext().getString(isChecked()
                        ? R.string.control_state_on : R.string.control_state_off));
            }
        };
        control.setButtonDrawable((android.graphics.drawable.Drawable) null);
        control.setText(getContext().getString(label)); control.setTag("sub_feature:" + key);
        UiIdentity.textSize(control, R.dimen.ui_text_row_title);
        control.setTextColor(getContext().getColor(R.color.text_primary));
        control.setMinWidth(0); control.setMinHeight(PersonUi.dp(getContext(), 48));
        control.setGravity(Gravity.CENTER_VERTICAL); control.setPaddingRelative(PersonUi.dp(getContext(), 8),
                PersonUi.dp(getContext(), 4), cell.statusWidth + PersonUi.dp(getContext(), 16), PersonUi.dp(getContext(), 4));
        control.setBackgroundResource(R.drawable.bg_home_composition_stat);
        cell.label = control;
        cell.addView(control, 0, new FrameLayout.LayoutParams(-1, -1));
        control.setChecked(allowed);
        addView(cell, new LayoutParams(-1, -2));
        return control;
    }

    public void readOnly(String key, int label, Boolean allowed) {
        Cell cell = new Cell(getContext(), label);
        TextView name = new TextView(getContext()); name.setText(getContext().getString(label));
        UiIdentity.textSize(name, R.dimen.ui_text_row_title);
        name.setTextColor(getContext().getColor(R.color.text_primary)); name.setGravity(Gravity.CENTER_VERTICAL);
        name.setPaddingRelative(PersonUi.dp(getContext(), 8), PersonUi.dp(getContext(), 4),
                cell.statusWidth + PersonUi.dp(getContext(), 16), PersonUi.dp(getContext(), 4));
        cell.label = name; cell.addView(name, 0, new FrameLayout.LayoutParams(-1, -1));
        if (allowed == null) {
            cell.status.setText(R.string.remote_unknown);
            cell.statusWidth = Math.max(cell.statusWidth, (int) Math.ceil(cell.status.getPaint()
                    .measureText(cell.status.getText().toString())));
            name.setPaddingRelative(PersonUi.dp(getContext(), 8), PersonUi.dp(getContext(), 4),
                    cell.statusWidth + PersonUi.dp(getContext(), 16), PersonUi.dp(getContext(), 4));
        } else cell.state(allowed);
        cell.setTag("person_allowance:" + key);
        cell.setContentDescription(getContext().getString(label) + ", " + cell.status.getText());
        name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        ViewCompat.setScreenReaderFocusable(cell, true);
        addView(cell, new LayoutParams(-1, -2));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), widest = 0;
        for (int index = 0; index < getChildCount(); index++) widest = Math.max(widest, ((Cell) getChildAt(index)).naturalWidth());
        columns = getChildCount() > 1 && 2 * widest + columnGap <= width ? 2 : 1;
        int cellWidth = Math.max(0, (width - (columns - 1) * columnGap) / columns);
        heights = new int[(getChildCount() + columns - 1) / columns];
        for (int index = 0; index < getChildCount(); index++) {
            View cell = getChildAt(index);
            cell.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            heights[index / columns] = Math.max(heights[index / columns], cell.getMeasuredHeight());
        }
        int height = 0;
        for (int row = 0; row < heights.length; row++) {
            if (row > 0) height += rowGap;
            height += heights[row];
            for (int index = row * columns; index < Math.min(getChildCount(), (row + 1) * columns); index++)
                getChildAt(index).measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(heights[row], MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int y = 0;
        for (int row = 0; row < heights.length; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= getChildCount()) break;
                View cell = getChildAt(index);
                int x = column * (cell.getMeasuredWidth() + columnGap);
                if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) x = getWidth() - x - cell.getMeasuredWidth();
                cell.layout(x, y, x + cell.getMeasuredWidth(), y + heights[row]);
            }
            y += heights[row] + rowGap;
        }
    }

    private static final class Cell extends FrameLayout {
        final TextView status;
        TextView label;
        int statusWidth;
        Cell(Context context, int title) {
            super(context); setMinimumHeight(PersonUi.dp(context, 48));
            status = new TextView(context); UiIdentity.textSize(status, R.dimen.ui_text_body);
            status.setTypeface(null, android.graphics.Typeface.BOLD);
            status.setTextColor(context.getColor(R.color.text_secondary));
            status.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            statusWidth = (int) Math.ceil(Math.max(status.getPaint().measureText(context.getString(R.string.control_state_on)),
                    status.getPaint().measureText(context.getString(R.string.control_state_off))));
            FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
            size.setMarginEnd(PersonUi.dp(context, 8)); addView(status, size);
        }
        void state(boolean allowed) {
            status.setText(allowed ? R.string.control_state_on : R.string.control_state_off);
            status.setTextColor(getContext().getColor(allowed ? R.color.control_checked_text : R.color.text_secondary));
        }
        int naturalWidth() {
            return (int) Math.ceil(android.text.Layout.getDesiredWidth(label.getText(), label.getPaint()))
                    + statusWidth + PersonUi.dp(getContext(), 24);
        }
    }
}
