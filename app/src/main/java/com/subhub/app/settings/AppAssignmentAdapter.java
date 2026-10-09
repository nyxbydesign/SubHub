package com.subhub.app.settings;

import android.content.Context;
import android.view.*;
import android.widget.*;

import java.util.*;

/** Only visible app rows are created; recycled rows rebind the requested independent choices. */
final class AppAssignmentAdapter extends BaseAdapter {
    interface Changed {
        void selected(String packageName, int module, boolean selected);
    }

    private final Context context;
    private final List<InstalledAppCatalog.Entry> entries;
    private final List<Set<String>> assignments;
    private final Changed changed;
    private boolean editing;

    AppAssignmentAdapter(
            Context context,
            List<InstalledAppCatalog.Entry> entries,
            List<Set<String>> assignments,
            Changed changed) {
        this.context = context;
        this.entries = entries;
        this.assignments = assignments;
        this.changed = changed;
    }

    void setEditing(boolean editing) {
        if (this.editing != editing) {
            this.editing = editing;
            notifyDataSetChanged();
        }
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public InstalledAppCatalog.Entry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean isEnabled(int position) {
        return false;
    }

    @Override
    public View getView(int position, View reused, ViewGroup parent) {
        InstalledAppCatalog.Entry entry = getItem(position);
        AppAssignmentRow row =
                reused instanceof AppAssignmentRow
                        ? (AppAssignmentRow) reused
                        : new AppAssignmentRow(context, "", "", null, new boolean[3]);
        boolean[] selected = new boolean[3];
        for (int module = 0; module < assignments.size(); module++)
            selected[module] = assignments.get(module).contains(entry.packageName);
        row.bind(entry.label, entry.packageName, entry.icon, selected);
        row.setModuleCount(assignments.size());
        for (int module = 0; module < assignments.size(); module++) {
            final int choice = module;
            CheckBox control = row.choice(module);
            control.setEnabled(editing);
            control.setOnCheckedChangeListener(
                    (button, value) -> {
                        if (editing) changed.selected(entry.packageName, choice, value);
                    });
        }
        return row;
    }
}
