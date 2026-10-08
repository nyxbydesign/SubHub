package com.subhub.app.onboarding;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.*;
import com.subhub.app.settings.*;
import com.subhub.app.util.*;

/** First-run setup using existing SubHub controls. Replay preserves the current configuration. */
public final class OnboardingActivity extends PreferencePage {
    public static final String REPLAY="replay";
    private int step;
    private boolean replay, censor, limits, wallet, appearanceChanged;
    private CensorAppearance.Type style;
    private final ActivityResultLauncher<String> notifications=registerForActivityResult(new ActivityResultContracts.RequestPermission(),ignored->render());
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        replay=getIntent().getBooleanExtra(REPLAY,false);
        FeatureModuleManager modules=new FeatureModuleManager(this);
        censor=modules.isCensorEnabled();limits=modules.isLimitsEnabled();wallet=modules.isWalletEnabled();style=new SettingsRepository(this).loadAppearance().getType();
        if(state!=null){step=state.getInt("step");censor=state.getBoolean("censor");limits=state.getBoolean("limits");wallet=state.getBoolean("wallet");style=CensorAppearance.Type.fromPreference(state.getString("style"));appearanceChanged=state.getBoolean("appearance_changed");}
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){@Override public void handleOnBackPressed(){if(step>0){step--;render();}else finishSetup();}});
        render();
    }
    @Override protected void onResume(){super.onResume();if(page!=null)render();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("step",step);state.putBoolean("censor",censor);state.putBoolean("limits",limits);state.putBoolean("wallet",wallet);state.putString("style",style.getPreferenceValue());state.putBoolean("appearance_changed",appearanceChanged);super.onSaveInstanceState(state);}
    private void render(){
        if(isFinishing()||isDestroyed())return;
        page(R.string.tour_title);
        PrimaryHeader.backButton(page).setOnClickListener(v->{if(step>0){step--;render();}else finishSetup();});
        TextView count=text(page,getString(R.string.tour_progress,step+1,4),12,true);count.setId(R.id.tour_progress);
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(4);progress.setProgress(step+1);page.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
        int[] titles={R.string.settings_features,R.string.settings_appearance,R.string.keyholder_home_title,R.string.tour_permissions};
        TextView title=text(page,getString(titles[step]),23,false);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setId(R.id.tour_step_title);
        if(step==0)features();else if(step==1)appearance();else if(step==2)keyholder();else permissions();
        View scroll=(View)page.getParent();((ViewGroup)scroll.getParent()).removeView(scroll);
        FrameLayout root=new FrameLayout(this);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout footer=new LinearLayout(this);footer.setOrientation(LinearLayout.VERTICAL);footer.setPadding(dp(16),dp(6),dp(16),dp(8));footer.setBackgroundColor(getColor(R.color.background));
        Button next=button(footer,getString(step==3?R.string.tour_finish:R.string.tour_next),()->{if(step==3)finishSetup();else{step++;render();}});next.setId(R.id.tour_next);next.setBackgroundResource(R.drawable.bg_primary_button);next.setTextColor(getColor(R.color.text_primary));
        button(footer,getString(R.string.tour_skip),this::finishSetup).setId(R.id.tour_skip);
        FrameLayout.LayoutParams footerParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);root.addView(footer,footerParams);
        page.setPadding(page.getPaddingLeft(),page.getPaddingTop(),page.getPaddingRight(),dp(160));setContentView(root);
    }
    private void features(){
        LinearLayout choices=card(page);
        toggle(choices,R.string.global_feature_censor,censor,v->censor=v).setEnabled(!replay);
        toggle(choices,R.string.global_feature_limits,limits,v->limits=v).setEnabled(!replay);
        toggle(choices,R.string.global_feature_wallet,wallet,v->wallet=v).setEnabled(!replay);
        text(page,getString(R.string.tour_features_help),14,true);
    }
    private void appearance(){
        CensorAppearance.Type[] types={CensorAppearance.Type.BOX,CensorAppearance.Type.PIXELATE,CensorAppearance.Type.BLUR};
        int[] labels={R.string.style_box,R.string.style_pixelate,R.string.style_blur};
        for(int i=0;i<types.length;i++){
            final CensorAppearance.Type choice=types[i]; LinearLayout row=card(page);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            CensorPreviewView preview=new CensorPreviewView(this,null);preview.setTag(choice.getPreferenceValue());row.addView(preview,new LinearLayout.LayoutParams(dp(80),dp(105)));
            Button select=button(row,getString(labels[i]),()->{if(!replay){style=choice;appearanceChanged=true;render();}});LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.leftMargin=dp(12);select.setLayoutParams(params);
            select.setSelected(style==choice);select.setEnabled(!replay);select.setBackgroundResource(style==choice?R.drawable.bg_primary_button:R.drawable.bg_outline_button);select.setTextColor(getColor(R.color.text_primary));
        }
    }
    private void keyholder(){
        text(page,getString(R.string.tour_keyholder_help),14,true);
        boolean pin=ControllerPinManager.isConfigured(this), auth=new ControllerAuthenticator(this).isPaired();
        text(page,getString(pin&&auth?R.string.keyholder_status_both:pin?R.string.keyholder_status_pin:auth?R.string.keyholder_status_auth:R.string.keyholder_status_none),13,true);
        button(page,getString(R.string.controller_pin_set),()->{if(!ControllerPinManager.hasCredentials(this))ControllerPinManager.useWithoutKeyholder(this);ControllerPinGate.changePin(this,this::render);});
        button(page,getString(R.string.authenticator_pair),()->{
            if(!ControllerPinManager.hasCredentials(this))ControllerPinManager.useWithoutKeyholder(this);
            startActivity(new Intent(this,AuthenticatorActivity.class).putExtra("keyholder_method","remote"));
        });
    }
    private void permissions(){
        boolean runtime=censor||limits||new FeatureModuleManager(this).isSubliminalEnabled();
        if(runtime){
            permission(R.string.permission_accessibility_name,new AppModeManager(this).isAccessibilityEnabled(),()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            if(Build.VERSION.SDK_INT>=33)permission(R.string.permission_notifications_name,checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED,()->notifications.launch(Manifest.permission.POST_NOTIFICATIONS));
        }
        if(censor)permission(R.string.permission_overlay_name,Settings.canDrawOverlays(this),()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
        text(page,getString(R.string.tour_permission_help),13,true);
    }
    private void permission(int name,boolean ready,Runnable open){
        LinearLayout row=card(page);text(row,getString(name),16,false);
        Button action=button(row,getString(ready?R.string.tour_allowed:R.string.tour_allow),open);action.setEnabled(!ready);
    }
    private void finishSetup(){
        if(!replay){
            FeatureModuleManager modules=new FeatureModuleManager(this);modules.save(censor,limits,wallet,modules.isSubliminalEnabled());
            if(appearanceChanged){SettingsRepository settings=new SettingsRepository(this);CensorAppearance old=settings.loadAppearance();settings.saveAppearance(style,old.getIntensity(),old.isShowBorder(),old.isShowText());}
            if(!ControllerPinManager.hasCredentials(this))ControllerPinManager.useWithoutKeyholder(this);
            OnboardingState.complete(this);ControllerPinManager.enterSubMode();
        }
        startActivity(new Intent(this,MainActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();
    }
}
