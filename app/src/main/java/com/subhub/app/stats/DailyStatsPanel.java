package com.subhub.app.stats;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.subhub.app.R;
import com.subhub.app.penance.WalletCurrency;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Shared daily queries for Home and Statistics; units are never combined into a score. */
public final class DailyStatsPanel extends LinearLayout {
    private static final String[] METRICS = {"service_ms","censors","limits_ms","limit_stops","whispers","popups","photos","videos","assessed_EUR","assessed_USD","paid_EUR","paid_USD"};
    private static final int[] LABELS = {R.string.daily_service,R.string.daily_censors,R.string.daily_limits_time,R.string.daily_limits_stops,R.string.daily_whispers,R.string.daily_popups,R.string.daily_photos,R.string.daily_videos,R.string.daily_assessed_eur,R.string.daily_assessed_usd,R.string.daily_paid_eur,R.string.daily_paid_usd};
    private final boolean compact;
    private int range = 30, metric, month = LocalDate.now().getMonthValue();
    private LocalDate selected = LocalDate.now();
    private long rendered;
    private Map<String, Map<String, Long>> data = Collections.emptyMap();
    private String since;
    private TextView detail;
    public DailyStatsPanel(Context context, boolean compact) {
        super(context); this.compact = compact; if (compact) range = 7;
        setOrientation(VERTICAL); setPadding(dp(14), dp(12), dp(14), dp(14)); setBackgroundResource(R.drawable.bg_card);
        setId(R.id.daily_statistics_panel); refresh();
    }
    public void save(Bundle state) { state.putInt("daily_range", range); state.putInt("daily_metric", metric); state.putInt("daily_month", month); state.putString("daily_selected", selected.toString()); }
    public void restore(Bundle state) {
        if (state == null) return;
        range = state.getInt("daily_range", compact ? 7 : 30); metric = Math.max(0, Math.min(METRICS.length - 1, state.getInt("daily_metric", 0)));
        month = Math.max(1, Math.min(12, state.getInt("daily_month", LocalDate.now().getMonthValue())));
        try { selected = LocalDate.parse(state.getString("daily_selected", LocalDate.now().toString())); } catch (RuntimeException ignored) { selected = LocalDate.now(); }
        refresh();
    }
    public void refreshIfNeeded() { if (android.os.SystemClock.elapsedRealtime() - rendered >= 15000) refresh(); }
    public void refresh() {
        rendered = android.os.SystemClock.elapsedRealtime(); removeAllViews();
        text(getContext().getString(compact ? R.string.daily_today : R.string.daily_activity), 18, false).setTypeface(null, Typeface.BOLD);
        LocalDate today = LocalDate.now(); LocalDate first = range == 365 ? today.withDayOfYear(1) : today.minusDays(range - 1);
        try {
            DailyStatsStore store = DailyStatsStore.get(getContext()); since = store.since(); data = store.days(first.toString(), today.toString());
        } catch (RuntimeException failure) { text(getContext().getString(R.string.daily_unavailable), 14, true); return; }
        if (!compact) {
            LinearLayout ranges = new LinearLayout(getContext()); addView(ranges);
            int[] counts = {7,30,365}, ids = {R.id.daily_range_7,R.id.daily_range_30,R.id.daily_range_year};
            for (int i = 0; i < counts.length; i++) { final int count = counts[i]; Button choice = action(ranges, getContext().getString(count == 7 ? R.string.daily_7 : count == 30 ? R.string.daily_30 : R.string.daily_year), () -> { range = count; selected = today; refresh(); }); choice.setId(ids[i]); choice.setSelected(range == count); choice.setTextColor(getContext().getColor(range == count ? R.color.text_primary : R.color.accent_hot)); choice.setBackgroundResource(range == count ? R.drawable.bg_primary_button : R.drawable.bg_outline_button); }
            Spinner filter = new Spinner(getContext()); filter.setId(R.id.daily_metric_filter);
            List<String> labels = new ArrayList<>(); for (int label : LABELS) labels.add(getContext().getString(label));
            ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, labels); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            filter.setAdapter(adapter); filter.setSelection(metric); filter.setMinimumHeight(dp(48)); addView(filter, new LayoutParams(-1,-2));
            filter.setContentDescription(getContext().getString(R.string.daily_filter));
            filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { if (position != metric) { metric = position; refresh(); } }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            text(first + " — " + today, 12, true);
        }
        long total = 0; List<Long> trend = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) { long value = value(day, METRICS[metric]); total = total > Long.MAX_VALUE - value ? Long.MAX_VALUE : total + value; trend.add(day.toString().compareTo(since) < 0 ? -1L : value); }
        if (!compact) {
            text(getContext().getString(LABELS[metric]) + " · " + format(METRICS[metric], total), 14, false);
            addView(new Trend(getContext(), trend), new LayoutParams(-1, dp(70)));
        }
        if (range == 365 && !compact) {
            GridLayout months = new GridLayout(getContext()); months.setColumnCount(3); addView(months, new LayoutParams(-1,-2));
            for (int m = 1; m <= 12; m++) { final int chosen = m; LocalDate start = today.withMonth(m).withDayOfMonth(1);
                Button pick = new Button(getContext()); pick.setText(start.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))); pick.setAllCaps(false); pick.setTextColor(getContext().getColor(R.color.text_primary));
                pick.setBackgroundResource(month == m ? R.drawable.bg_primary_button : R.drawable.bg_outline_button); pick.setOnClickListener(v -> { month = chosen; refresh(); });
                GridLayout.LayoutParams params = new GridLayout.LayoutParams(GridLayout.spec((m-1)/3),GridLayout.spec((m-1)%3,1f)); params.width=0; params.height=dp(48); months.addView(pick, params);
            }
        }
        LocalDate calendarStart = range == 365 ? today.withMonth(month).withDayOfMonth(1) : first;
        LocalDate calendarEnd = range == 365 ? calendarStart.withDayOfMonth(calendarStart.lengthOfMonth()) : today;
        if (!compact) text(range == 365 ? calendarStart.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())) : calendarStart + " — " + calendarEnd, 12, true);
        HorizontalScrollView calendarScroll = new HorizontalScrollView(getContext()); calendarScroll.setFillViewport(true); addView(calendarScroll,new LayoutParams(-1,-2));
        GridLayout calendar = new GridLayout(getContext()); calendar.setColumnCount(7); calendar.setMinimumWidth(dp(336)); calendarScroll.addView(calendar, new HorizontalScrollView.LayoutParams(-1,-2));
        long maximum = 1; for (LocalDate day = calendarStart; !day.isAfter(calendarEnd); day = day.plusDays(1)) maximum = Math.max(maximum, value(day,METRICS[metric]));
        int index = 0;
        for (LocalDate day = calendarStart; !day.isAfter(calendarEnd); day = day.plusDays(1), index++) {
            final LocalDate chosen = day; long amount = value(day,METRICS[metric]);
            TextView cell = new TextView(getContext()); cell.setText(Integer.toString(day.getDayOfMonth())); cell.setTextSize(13); cell.setGravity(Gravity.CENTER);
            cell.setTextColor(getContext().getColor(R.color.text_primary)); cell.setMinHeight(dp(44)); cell.setFocusable(true);
            android.graphics.drawable.GradientDrawable tile = new android.graphics.drawable.GradientDrawable(); tile.setCornerRadius(dp(8));
            int accent = getContext().getColor(R.color.accent); int alpha = amount == 0 ? 22 : 60 + (int)(165 * Math.sqrt(amount / (double) maximum));
            tile.setColor((accent & 0x00ffffff) | (alpha << 24)); if (day.equals(selected)) tile.setStroke(dp(2), getContext().getColor(R.color.accent_hot)); cell.setBackground(tile);
            boolean known = day.toString().compareTo(since) >= 0 && !day.isAfter(today); cell.setAlpha(known ? 1f : .35f);
            cell.setContentDescription(day + ": " + (known ? getContext().getString(LABELS[metric]) + " " + format(METRICS[metric],amount) : getContext().getString(R.string.daily_no_history)));
            cell.setOnClickListener(v -> { selected = chosen; refresh(); });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(GridLayout.spec(index/7), GridLayout.spec(index%7,1f)); params.width=0; params.height=dp(44); params.setMargins(dp(2),dp(2),dp(2),dp(2)); calendar.addView(cell, params);
        }
        detail = text(compact ? getContext().getString(R.string.daily_compact_summary, format("service_ms",value(selected,"service_ms")),value(selected,"censors")) : summary(selected), 13, true); detail.setId(R.id.daily_day_summary);
        if (!compact) text(getContext().getString(R.string.daily_history_note, since), 11, true);
        if (compact) { Button open = action(this, getContext().getString(R.string.daily_open), () -> getContext().startActivity(new android.content.Intent(getContext(), StatsActivity.class))); open.setId(R.id.daily_open_statistics); }
    }
    private String summary(LocalDate day) {
        if (day.toString().compareTo(since) < 0 || day.isAfter(LocalDate.now())) return day + " · " + getContext().getString(R.string.daily_no_history);
        return day + "\n" + getContext().getString(R.string.daily_summary,
                format("service_ms",value(day,"service_ms")), value(day,"censors"), format("limits_ms",value(day,"limits_ms")),value(day,"limit_stops"),
                value(day,"whispers"),value(day,"popups"),value(day,"photos"),value(day,"videos"))
                + "\n" + getContext().getString(R.string.daily_wallet_summary,format("assessed_EUR",value(day,"assessed_EUR")),format("assessed_USD",value(day,"assessed_USD")),format("paid_EUR",value(day,"paid_EUR")),format("paid_USD",value(day,"paid_USD")));
    }
    private long value(LocalDate day, String metric) { return data.getOrDefault(day.toString(),Collections.emptyMap()).getOrDefault(metric,0L); }
    private String format(String metric, long value) {
        if (metric.endsWith("_ms")) return StatsSnapshot.formatDuration(value/1000);
        if (metric.endsWith("_EUR") || metric.endsWith("_USD")) { String currency=metric.substring(metric.length()-3); return WalletCurrency.format(currency,value) + " " + currency; }
        return Long.toString(value);
    }
    private TextView text(String value,int size,boolean muted) { TextView text = new TextView(getContext()); text.setText(value); text.setTextSize(size); text.setTextColor(getContext().getColor(muted?R.color.text_secondary:R.color.text_primary)); text.setPadding(0,dp(5),0,dp(6)); text.setLineSpacing(dp(3),1); addView(text,new LayoutParams(-1,-2)); return text; }
    private Button action(LinearLayout parent,String title,Runnable run) { Button b=(Button) LayoutInflater.from(getContext()).inflate(R.layout.view_ux_action,parent,false); b.setText(title); b.setAllCaps(false); b.setOnClickListener(v->run.run()); LayoutParams p=parent==this?new LayoutParams(-1,-2):new LayoutParams(0,-2,1); p.topMargin=dp(5); parent.addView(b,p); return b; }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private final class Trend extends View {
        private final List<Long> values; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Trend(Context c,List<Long> values) { super(c); this.values=values; setContentDescription(c.getString(R.string.daily_trend)); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); long max=1; for(long value:values) max=Math.max(max,value);
            paint.setColor(getContext().getColor(R.color.accent)); paint.setStrokeWidth(dp(2));
            float step=getWidth()/(float)Math.max(1,values.size());
            for(int i=0;i<values.size();i++) { if (values.get(i) < 0) continue; float h=(getHeight()-dp(6))*values.get(i)/(float)max; canvas.drawRoundRect(i*step, getHeight()-Math.max(dp(2),h), Math.max(i*step+1,(i+1)*step-dp(1)),getHeight(),dp(2),dp(2),paint); }
        }
    }
}
