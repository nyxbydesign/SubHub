package com.subhub.app.stats;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.subhub.app.R;
import com.subhub.app.people.InternalRuntimePresentationSnapshot;
import com.subhub.app.people.PersonUi;
import com.subhub.app.penance.WalletCurrency;
import com.subhub.app.util.AsyncUiScope;
import java.util.*;

/** Normal statistics components fed only by one acknowledged internal observation. */
final class InternalStatsPane extends LinearLayout {
    private final StatsActivity activity;
    private final InternalStatsSource source;
    private final AsyncUiScope requests;
    private final LinearLayout totals, details, allowances, sessions, achievements;
    private final DailyStatsPanel daily;
    private int sessionLimit;
    private InternalStatsSource.Loaded rendered;
    InternalStatsPane(StatsActivity activity,InternalStatsSource source,Bundle state,int sessionLimit) {
        super(activity); this.activity=activity; this.source=source; this.sessionLimit=sessionLimit;
        requests=AsyncUiScope.forContext(activity); setOrientation(VERTICAL); setTag("internal_statistics");
        totals=PersonUi.card(this);totals.setId(R.id.stats_lifetime_summary);
        daily=new DailyStatsPanel(activity,false,state,source);addView(daily,new LayoutParams(-1,-2));
        allowances=PersonUi.card(this);allowances.setId(R.id.profile_stats_allowances);
        sessions=PersonUi.card(this);sessions.setTag("stats_session_history");
        details=PersonUi.card(this);details.setId(R.id.stats_totals_details);
        achievements=PersonUi.card(this);achievements.setId(R.id.stats_achievements);
        unavailable();
    }
    void refresh() {
        try { source.requireAccess(); } catch (RuntimeException revoked) { source.clear(); unavailable(); activity.finish(); return; }
        if(rendered!=null&&!source.accessible()) { rendered=null; unavailable(); }
        if(requests.isPending("internal-statistics"))return;
        requests.load("internal-statistics",source::read,loaded->{
            if(activity.isFinishing()||activity.isDestroyed())return;
            try {
                if(!source.publish(loaded)) { unavailable(); return; }
                rendered=loaded; render(loaded);daily.refreshIfNeeded();
            }catch(RuntimeException unavailable){source.clear();unavailable();}
        },failure->{source.clear();rendered=null;unavailable();});
    }
    void pause(){requests.cancel("internal-statistics");source.clear();rendered=null;unavailable();}
    void save(Bundle state){daily.save(state);}
    int sessionLimit(){return sessionLimit;}
    private void unavailable(){
        for(LinearLayout card:new LinearLayout[]{totals,details,allowances,sessions,achievements})card.removeAllViews();
        PersonUi.text(totals,activity.getString(R.string.role_person_unavailable),false);
        daily.refreshIfNeeded();
    }
    private void render(InternalStatsSource.Loaded value){
        InternalRuntimePresentationSnapshot snapshot=value.snapshot;StatsSnapshot stats=snapshot.statistics();
        totals.removeAllViews();
        activity.heading(totals, R.string.stats_all_time);
        activity.row(totals,R.string.stats_time,activity.duration(stats.getTotalProtectedSeconds()));
        activity.row(totals,R.string.stats_censors,activity.number(stats.getTotalBlocks()));
        activity.row(totals,R.string.stats_sessions,activity.number(stats.getSessions()));
        PersonUi.text(totals,activity.getString(R.string.stats_session_as_of,
                java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                        .withLocale(activity.getResources().getConfiguration().getLocales().get(0))
                        .withZone(java.time.ZoneId.of(snapshot.observed.zone)).format(java.time.Instant.ofEpochMilli(snapshot.observed.at))
                        + " · " + snapshot.observed.zone),false);
        // Session occupancy is owned by the runtime gateway, never inferred from historical counters.
        activity.row(totals,R.string.role_service_duration,activity.getString(R.string.role_unknown_data));
        allowances.removeAllViews();PersonUi.allowances(allowances,value.person);
        allowances.setVisibility(allowances.getChildCount()==0?GONE:VISIBLE);
        details.removeAllViews();
        activity.heading(details, R.string.profile_stats_feature_totals);
        activity.row(details,R.string.statistics_longest,activity.duration(stats.getLongestSessionSeconds()));
        activity.row(details,R.string.statistics_current_streak,activity.days(stats.getCurrentStreak()));
        activity.row(details,R.string.stats_limited_app_time,activity.duration(stats.getLimitedAppMillis()/1000));
        activity.row(details,R.string.stats_limit_stops,activity.number(stats.getLimitInterventions()));
        activity.row(details,R.string.stats_whispers,activity.number(stats.getSubliminalImpressions()));
        activity.row(details,R.string.stats_popups,activity.number(stats.getPopupImpressions()));
        activity.row(details,R.string.stats_tributes,activity.number(stats.getTributeEvents()));
        for (String currency : List.of("EUR", "USD"))
            activity.row(details, R.string.stats_assessed,
                    WalletCurrency.format(currency, snapshot.assessed(currency)) + " " + currency);
        renderSessions(snapshot);
        achievements.removeAllViews();
        PersonUi.text(achievements, activity.getString(R.string.achievements_title), true);
        Object unlocked=snapshot.runtime.namespaces().getOrDefault("achievements",Collections.emptyMap()).get("unlocked");
        if(unlocked!=null&&!(unlocked instanceof Set))throw new IllegalStateException("Internal achievement receipts unavailable");
        int count=unlocked==null?0:((Set<?>)unlocked).size();
        PersonUi.text(achievements,activity.getResources().getQuantityString(R.plurals.achievement_notice_count,count,count),false);
        View subtitle=com.subhub.app.util.PrimaryHeader.subtitle(activity.findViewById(android.R.id.content));
        if(subtitle instanceof TextView){((TextView)subtitle).setText(value.person.name+" · "+activity.getString(R.string.internal_sub_device));subtitle.setVisibility(VISIBLE);}
    }
    private void renderSessions(InternalRuntimePresentationSnapshot snapshot){
        sessions.removeAllViews();
        activity.heading(sessions, R.string.stats_recent_sessions);
        List<StatsRepository.SessionEntry> entries=new ArrayList<>(snapshot.accounting.sessions());Collections.reverse(entries);
        entries.removeIf(e->e.getDurationSeconds()==0&&e.getActivityEvents()==0&&e.getLimitedAppMillis()==0);
        if(entries.isEmpty()){PersonUi.text(sessions,activity.getString(R.string.statistics_no_history),false);return;}
        for(int i=0;i<Math.min(sessionLimit,entries.size());i++){
            StatsRepository.SessionEntry entry=entries.get(i);
            LinearLayout line=PersonUi.column(activity);line.setTag("stats:session");sessions.addView(line,new LayoutParams(-1,-2));
            String date=java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                    .withLocale(activity.getResources().getConfiguration().getLocales().get(0)).withZone(java.time.ZoneId.of(entry.zone()))
                    .format(java.time.Instant.ofEpochMilli(entry.getStartMillis()));
            PersonUi.text(line,date+" · "+entry.zone(),true);
            activity.row(line, R.string.stats_time, activity.duration(entry.getDurationSeconds()));
            activity.row(line, R.string.stats_censors, activity.number(entry.getBlocks()));
            activity.row(line,R.string.stats_limited_app_time,activity.duration(entry.getLimitedAppMillis()/1000));
        }
        if (entries.size() > 3) {
            boolean expanded = sessionLimit >= entries.size();
            activity.addSessionHistoryAction(sessions, expanded, () -> {
                if (!source.accessible() || rendered == null || rendered.snapshot != snapshot) {
                    unavailable();
                    return;
                }
                sessionLimit = expanded ? 3 : Math.min(entries.size(), sessionLimit + 3);
                renderSessions(snapshot);
            });
        }
    }
}
