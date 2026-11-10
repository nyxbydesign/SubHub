package com.subhub.app.settings;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import java.util.List;
import java.util.Set;

/** Lifecycle-owned app data and one selection shared by every enabled feature. */
final class IncludedAppsAdapter extends BaseAdapter {
    interface Changed {
        void selected(String packageName, boolean included);
    }

    private final Context context;
    private final List<InstalledAppCatalog.Entry> entries;
    private final Set<String> included;
    private final Changed changed;
    private boolean editing;

    IncludedAppsAdapter(
            Context context,
            List<InstalledAppCatalog.Entry> entries,
            Set<String> included,
            Changed changed) {
        this.context = context;
        this.entries = entries;
        this.included = included;
        this.changed = changed;
    }

    void setEditing(boolean enabled) {
        if (editing != enabled) {
            editing = enabled;
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
        InstalledAppCatalog.Entry app = getItem(position);
        IncludedAppRow row =
                reused instanceof IncludedAppRow
                        ? (IncludedAppRow) reused
                        : new IncludedAppRow(context);
        row.bind(app.label, app.packageName, app.icon, included.contains(app.packageName), editing);
        row.choice()
                .setOnCheckedChangeListener(
                        (button, selected) -> {
                            if (editing) changed.selected(app.packageName, selected);
                        });
        return row;
    }
}
