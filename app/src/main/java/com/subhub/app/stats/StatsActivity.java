package com.subhub.app.stats;

import android.os.Bundle;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import com.subhub.app.R;
import com.subhub.app.util.PreferencePage;
import com.subhub.app.penance.*;
import java.text.DateFormat;
import java.util.*;

/** Mutually exclusive daily, lifetime, and saved-session views. */
public final class StatsActivity extends PreferencePage {
    private int tab,sessionPage;
    private boolean includeEmptySessions;
    private DailyStatsPanel daily;
    private Bundle dailyState=new Bundle();
    @Override protected void onCreate(Bundle state){super.onCreate(state);if(state!=null){tab=state.getInt("stats_tab");sessionPage=state.getInt("stats_session_page");includeEmptySessions=state.getBoolean("stats_include_empty");dailyState=state;}render();}
    @Override protected void onResume(){super.onResume();render();}
    @Override protected void onSaveInstanceState(Bundle state){if(daily!=null)daily.save(state);else state.putAll(dailyState);state.putInt("stats_tab",tab);state.putInt("stats_session_page",sessionPage);state.putBoolean("stats_include_empty",includeEmptySessions);super.onSaveInstanceState(state);}
    private void render(){
        if(daily!=null){daily.save(dailyState);daily=null;}page(R.string.statistics_title);
        LinearLayout tabs=new LinearLayout(this);page.addView(tabs,new LinearLayout.LayoutParams(-1,-2));
        int[] titles={R.string.stats_tab_activity,R.string.stats_tab_totals,R.string.stats_tab_sessions};int[] ids={R.id.stats_tab_activity,R.id.stats_tab_totals,R.id.stats_tab_sessions};
        for(int i=0;i<3;i++){final int next=i;Button b=button(tabs,getString(titles[i]),()->{tab=next;render();});b.setId(ids[i]);b.setSelected(tab==i);b.setTextColor(getColor(tab==i?R.color.text_primary:R.color.accent_hot));b.setBackgroundResource(tab==i?R.drawable.bg_primary_button:R.drawable.bg_outline_button);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(2),0,dp(2),dp(14));b.setLayoutParams(p);}
        if(tab==0){daily=new DailyStatsPanel(this,false);daily.restore(dailyState);page.addView(daily,new LinearLayout.LayoutParams(-1,-2));}
        else if(tab==1)totals();else sessions();
    }
    private void totals(){
        StatsRepository repository=new StatsRepository(this);StatsSnapshot stats=repository.load();
        text(page,getString(R.string.stats_totals_explanation),13,true);
        LinearLayout time=section(R.string.stats_service_sessions);row(time,R.string.stats_time,StatsSnapshot.formatDuration(stats.getTotalProtectedSeconds()));row(time,R.string.stats_sessions,number(stats.getSessions()));row(time,R.string.statistics_longest,StatsSnapshot.formatDuration(stats.getLongestSessionSeconds()));row(time,R.string.statistics_current_streak,getString(R.string.stats_days_value,stats.getCurrentStreak()));
        LinearLayout censor=section(R.string.stats_censor_limits);row(censor,R.string.stats_censors,number(stats.getTotalBlocks()));row(censor,R.string.stats_limited_app_time,StatsSnapshot.formatDuration(stats.getLimitedAppMillis()/1000));row(censor,R.string.stats_limit_stops,number(stats.getLimitInterventions()));
        LinearLayout rituals=section(R.string.atmosphere_title);row(rituals,R.string.stats_whispers,number(stats.getSubliminalImpressions()));row(rituals,R.string.stats_popups,number(stats.getPopupImpressions()));
        LinearLayout wallet=section(R.string.global_feature_wallet);row(wallet,R.string.stats_tributes,number(stats.getTributeEvents()));
        for(String currency:new String[]{"EUR","USD"}){text(wallet,currency,14,true).setTypeface(null,Typeface.BOLD);amount(wallet,R.string.stats_assessed,WalletCurrency.format(currency,repository.tributeCents(currency,false)));amount(wallet,R.string.stats_paid,WalletCurrency.format(currency,new PenanceManager(this).getTotalPaidCents(currency)));}
        LinearLayout exports=section(R.string.stats_exports);row(exports,R.string.daily_photos,number(stats.getExportedImages()));
    }
    private void sessions(){
        List<StatsRepository.SessionEntry> entries=new ArrayList<>(new StatsRepository(this).getSessionHistory());Collections.reverse(entries);
        boolean hasEmpty=false;for(StatsRepository.SessionEntry e:entries)if(e.getDurationSeconds()==0&&e.getActivityEvents()==0&&e.getLimitedAppMillis()==0)hasEmpty=true;
        if(hasEmpty){com.subhub.app.util.StateToggle filter=new com.subhub.app.util.StateToggle(this);filter.setText(R.string.stats_include_empty);filter.setTextColor(getColor(R.color.text_primary));filter.setChecked(includeEmptySessions);filter.setId(R.id.stats_sessions_empty_filter);filter.setOnCheckedChangeListener((button,checked)->{includeEmptySessions=checked;sessionPage=0;render();});page.addView(filter,new LinearLayout.LayoutParams(-1,-2));}
        if(!includeEmptySessions)entries.removeIf(e->e.getDurationSeconds()==0&&e.getActivityEvents()==0&&e.getLimitedAppMillis()==0);
        if(entries.isEmpty()){LinearLayout empty=section(R.string.stats_tab_sessions);text(empty,getString(hasEmpty?R.string.stats_no_nonempty_sessions:R.string.statistics_no_history),14,true);return;}
        int pages=(entries.size()+4)/5;sessionPage=Math.max(0,Math.min(sessionPage,pages-1));text(page,getResources().getQuantityString(R.plurals.stats_saved_count,entries.size(),entries.size()),13,true);
        for(int i=sessionPage*5;i<Math.min(entries.size(),sessionPage*5+5);i++){
            StatsRepository.SessionEntry e=entries.get(i);LinearLayout card=card(page);card.setTag("stats:session");TextView date=text(card,DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(e.getStartMillis())),15,false);date.setTypeface(null,Typeface.BOLD);
            text(card,e.getDurationSeconds()==0?getString(R.string.stats_no_duration):(e.getDurationSeconds()<60?getString(R.string.stats_seconds_value,e.getDurationSeconds()):StatsSnapshot.formatDuration(e.getDurationSeconds())),15,true);
            List<String> details=new ArrayList<>();if(e.getBlocks()>0)details.add(getString(R.string.stats_history_censors,e.getBlocks()));if(e.getLimitInterventions()>0)details.add(getString(R.string.stats_history_stops,e.getLimitInterventions()));if(e.getTributeEvents()>0)details.add(getString(R.string.stats_history_tributes,e.getTributeEvents()));if(e.getSubliminals()>0)details.add(getString(R.string.stats_history_whispers,e.getSubliminals()));if(e.getPopupImpressions()>0)details.add(getString(R.string.stats_history_popups,e.getPopupImpressions()));
            text(card,details.isEmpty()?getString(R.string.stats_history_no_activity):String.join(" · ",details),13,true);
        }
        LinearLayout navigation=new LinearLayout(this);page.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        Button previous=button(navigation,getString(R.string.stats_previous),()->{sessionPage--;render();});previous.setEnabled(sessionPage>0);previous.setId(R.id.stats_sessions_previous);
        Button next=button(navigation,getString(R.string.stats_next),()->{sessionPage++;render();});next.setEnabled(sessionPage<pages-1);next.setId(R.id.stats_sessions_next);
        for(int i=0;i<navigation.getChildCount();i++){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(3),0,dp(3),0);navigation.getChildAt(i).setLayoutParams(p);}
        text(page,getString(R.string.stats_page,sessionPage+1,pages),12,true);
    }
    private LinearLayout section(int title){LinearLayout c=card(page);TextView t=text(c,getString(title),17,false);t.setTypeface(null,Typeface.BOLD);return c;}
    private void row(LinearLayout parent,int label,String value){amount(parent,label,value);}
    private void amount(LinearLayout parent,int label,String value){LinearLayout line=new LinearLayout(this);line.setGravity(android.view.Gravity.CENTER_VERTICAL);parent.addView(line,new LinearLayout.LayoutParams(-1,-2));TextView name=text(line,getString(label),14,true);name.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));TextView amount=text(line,value,18,false);amount.setTypeface(null,Typeface.BOLD);amount.setGravity(android.view.Gravity.END);amount.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));}
    private String number(long value){return java.text.NumberFormat.getIntegerInstance().format(value);}
}
