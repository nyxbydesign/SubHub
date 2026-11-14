package com.subhub.app.tasks;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;

/** Task cards share the role pages' surfaces, type scale and touch targets. */
final class TasksUi {
    private TasksUi() { }

    static void occurrenceHeader(LinearLayout card, TaskEngine.Occurrence occurrence, int statusLabel) {
        Context context = card.getContext();
        TaskDefinition task = occurrence.task;
        PersonUi.text(card, task.title, true).setTextSize(24);
        if (statusLabel != 0) badge(card, context.getString(statusLabel));

        ZonedDateTime start = task.start(occurrence.date).atZone(task.zone);
        ZonedDateTime end = task.end(occurrence.date).atZone(task.zone);
        DateTimeFormatter dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);
        DateTimeFormatter endFormat = start.toLocalDate().equals(end.toLocalDate())
                ? DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) : dateTime;
        TextView window = PersonUi.text(card, context.getString(R.string.tasks_window,
                dateTime.format(start), endFormat.format(end), task.zone.getId()), false);
        window.setTag("task_window:" + occurrence.id);
        if (task.recurring()) {
            String[] labels = context.getResources().getStringArray(R.array.tasks_weekdays);
            ArrayList<String> days = new ArrayList<>();
            for (DayOfWeek day : task.weekdays) days.add(labels[day.getValue() - 1]);
            window.setPadding(0, window.getPaddingTop(), 0, 0);
            TextView weekdays = PersonUi.text(card, String.join(" · ", days), false);
            weekdays.setPadding(0, 0, 0, weekdays.getPaddingBottom());
            weekdays.setTag("task_weekdays:" + occurrence.id);
        }
        if (!task.text.isEmpty()) PersonUi.text(card, task.text, false);
    }

    static void counts(LinearLayout parent, int completed, int active, int pending, int scheduled) {
        LinearLayout row = counterRow(parent);
        count(row, completed, R.string.tasks_completed, 4);
        count(row, active, R.string.tasks_active, 4);
        count(row, pending, R.string.tasks_pending, 4);
        count(row, scheduled, R.string.tasks_scheduled, 4);
    }

    static void counts(LinearLayout parent, int active, int pending, int scheduled) {
        LinearLayout row = counterRow(parent);
        count(row, active, R.string.tasks_active, 8);
        count(row, pending, R.string.tasks_pending, 8);
        count(row, scheduled, R.string.tasks_scheduled, 8);
    }

    private static LinearLayout counterRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setTag("tasks_counts");
        row.setPadding(0, PersonUi.dp(parent.getContext(), 12), 0, 0);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private static void count(LinearLayout row, int number, int label, int rightPaddingDp) {
        Context context = row.getContext();
        LinearLayout column = PersonUi.column(context);
        column.setPadding(0, 0, PersonUi.dp(context, rightPaddingDp), 0);
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = PersonUi.text(column, Integer.toString(number), true);
        value.setTextSize(26);
        value.setTextColor(context.getColor(R.color.accent_text));
        ViewCompat.setAccessibilityHeading(value, false);
        TextView caption = PersonUi.text(column, context.getString(label), false);
        caption.setTextSize(12);
        caption.setPadding(0, 0, 0, 0);
    }

    static TextView badge(LinearLayout parent, CharSequence label) {
        Context context = parent.getContext();
        TextView value = new TextView(context);
        value.setText(label);
        value.setTextSize(12);
        value.setTypeface(null, Typeface.BOLD);
        value.setTextColor(context.getColor(R.color.accent_text));
        value.setBackgroundResource(R.drawable.bg_tasks_badge);
        value.setPadding(PersonUi.dp(context, 10), PersonUi.dp(context, 5),
                PersonUi.dp(context, 10), PersonUi.dp(context, 5));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.topMargin = PersonUi.dp(context, 8);
        size.bottomMargin = PersonUi.dp(context, 8);
        parent.addView(value, size);
        return value;
    }

    static void empty(LinearLayout parent, int label) {
        LinearLayout card = PersonUi.card(parent);
        card.setPadding(PersonUi.dp(parent.getContext(), 20), PersonUi.dp(parent.getContext(), 28),
                PersonUi.dp(parent.getContext(), 20), PersonUi.dp(parent.getContext(), 28));
        android.widget.ImageView icon = new android.widget.ImageView(parent.getContext());
        icon.setImageResource(R.drawable.ic_nav_tasks);
        icon.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(PersonUi.dp(parent.getContext(), 32),
                PersonUi.dp(parent.getContext(), 32));
        size.gravity = Gravity.CENTER_HORIZONTAL;
        size.bottomMargin = PersonUi.dp(parent.getContext(), 12);
        card.addView(icon, size);
        PersonUi.text(card, parent.getContext().getString(label), true).setGravity(Gravity.CENTER);
    }
}
