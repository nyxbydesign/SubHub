package com.subhub.app.remote;

import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;
import com.subhub.app.security.AuthenticatorActivity;

/** A link selects an identity; only explicit, authenticated approval creates a relationship. */
public final class PairingActivity extends RemotePage {
    static final String EXTRA_DIRECT_INVITATION="com.subhub.app.remote.DIRECT_INVITATION";
    private boolean directInvitation() {
        ComponentName caller=getCallingActivity();
        return requiresDom()&&getIntent().getBooleanExtra(EXTRA_DIRECT_INVITATION,false)
                &&caller!=null&&getPackageName().equals(caller.getPackageName());
    }
    private final Handler clock=new Handler(Looper.getMainLooper());
    private LinearLayout candidates;private PairingInvite invitation;private EditText link;private String linkedGeneration;
    private InvitationCreation creation;
    private java.util.concurrent.CompletableFuture<PairingInvite> observedCreation;
    private boolean restoringInvitation;
    private final Runnable poll=new Runnable(){public void run(){renderCandidates();clock.postDelayed(this,2000);}};
    @Override protected boolean requiresDom(){return getIntent().getBooleanExtra("asDom",false)&&getIntent().getData()==null;}
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);page(R.string.remote_pairing);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        boolean asDom=getIntent().getBooleanExtra("asDom",false) && getIntent().getData()==null;
        if(asDom?!com.subhub.app.security.DeviceRolePreferences.supportsDom(this):!com.subhub.app.security.DeviceRolePreferences.supportsSub(this)){finish();return;}
        if(asDom) {
            creation=new androidx.lifecycle.ViewModelProvider(this).get(InvitationCreation.class);
            if(directInvitation())text(body,getString(R.string.role_loading),false).setTag("pairing_invitation_loading");
            else PersonUi.action(body,R.string.remote_create_invite,this::createInvitation,true);
        }
        else {
            link=input(body,R.string.remote_invite_input,getIntent().getData()==null?"":getIntent().getData().toString(),com.subhub.app.util.InputBounds.PAIRING);
            link.setSaveEnabled(false);
            link.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
            button(body,R.string.remote_paste_code,()->{
                ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=clipboard.getPrimaryClip();
                if(clip!=null&&clip.getItemCount()>0){CharSequence pasted=clip.getItemAt(0).getText();if(pasted!=null&&pasted.length()<=8192)link.setText(pasted);}
            });
            PersonUi.action(body,R.string.remote_approve,()->authorizeEnrollment(this::confirm),true);
        }
        candidates=PersonUi.column(this);body.addView(candidates);
        if(asDom && creation.request==null && state!=null && state.containsKey("invitationId")) {
            String id=state.getString("invitationId"); restoringInvitation=true;
            work(()->feature.restoreInvitation(id),restored->{
                restoringInvitation=false;
                creation.request=java.util.concurrent.CompletableFuture.completedFuture(restored);
                observeCreation();
            },()->{restoringInvitation=false;creation.request=java.util.concurrent.CompletableFuture.completedFuture(null);invitationFailure(R.string.remote_failure);});
        }
    }
    @Override protected void onSaveInstanceState(Bundle state){
        PairingInvite saved=invitation;
        if(saved==null&&creation!=null&&creation.request!=null&&!creation.request.isCompletedExceptionally())
            saved=creation.request.getNow(null);
        if(saved!=null)state.putString("invitationId",saved.id);
        super.onSaveInstanceState(state);
    }
    /** Retain one accepted creation across rotation; only its identifier enters saved state. */
    public static final class InvitationCreation extends androidx.lifecycle.ViewModel {
        java.util.concurrent.CompletableFuture<PairingInvite> request;
        public InvitationCreation() { }
    }
    private void createInvitation() {
        authorize(()->{
            if(isFinishing()||isDestroyed()||!requiresDom()
                    ||!com.subhub.app.security.ControllerPinManager.isDomModeActive())return;
            if(creation.request==null) {
                body.removeAllViews();
                text(body,getString(R.string.role_loading),false).setTag("pairing_invitation_loading");
                candidates=PersonUi.column(this);body.addView(candidates);
                creation.request=feature.createInvite();
            }
            observeCreation();
        });
    }
    private void observeCreation() {
        InvitationCreation captured=creation;
        java.util.concurrent.CompletableFuture<PairingInvite> request=captured.request;
        if(request==null||observedCreation==request)return;
        observedCreation=request;
        java.lang.ref.WeakReference<PairingActivity> weak=new java.lang.ref.WeakReference<>(this);
        request.whenComplete((value,error)->{
            PairingActivity page=weak.get();if(page==null)return;
            page.runOnUiThread(()->{
                if(page.isDestroyed()||page.isFinishing()||page.creation!=captured||captured.request!=request)return;
                if(!page.requiresDom()||!com.subhub.app.security.ControllerPinManager.isDomModeActive()
                        ||!com.subhub.app.security.DeviceRolePreferences.supportsDom(page)){page.finish();return;}
                if(error!=null||value==null){page.invitationFailure(error==null?R.string.remote_expired:R.string.remote_failure);return;}
                page.invitation=value;page.renderInvite();
            });
        });
    }
    private void invitationFailure(int message) {
        body.removeAllViews();text(body,getString(message),false);
        button(body,R.string.role_retry,()->{creation.request=null;observedCreation=null;createInvitation();});
        candidates=PersonUi.column(this);body.addView(candidates);
    }
    @Override protected void onResume(){
        super.onResume();if(isFinishing())return;
        if(requiresDom()&&invitation==null&&!restoringInvitation) {
            if(creation.request!=null)observeCreation();
            else if(directInvitation())createInvitation();
        }
        clock.post(poll);
    }
    @Override protected void onPause(){clock.removeCallbacks(poll);super.onPause();}
    private void renderInvite() {
        PairingInvite shown=invitation;
        body.removeAllViews();
        text(body,getString(R.string.remote_expires_at,java.text.DateFormat.getTimeInstance().format(new java.util.Date(invitation.expiresAt))),false);
        work(()->{
            BitMatrix matrix=new MultiFormatWriter().encode(shown.link(),BarcodeFormat.QR_CODE,640,640);
            Bitmap bitmap=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);
            for(int y=0;y<640;y++)for(int x=0;x<640;x++)bitmap.setPixel(x,y,matrix.get(x,y)?0xff000000:0xffffffff);return bitmap;
        },bitmap->{if(invitation!=shown){bitmap.recycle();return;}ImageView qr=new ImageView(this);qr.setImageBitmap(bitmap);qr.setContentDescription(getString(R.string.remote_pairing));
            body.addView(qr,0,new LinearLayout.LayoutParams(-1,PersonUi.dp(this,280)));});
        button(body,R.string.remote_copy_invite,()->authorize(()->{
            ClipData clip=ClipData.newPlainText(getString(R.string.remote_pairing),invitation.code());
            if(Build.VERSION.SDK_INT>=33){PersistableBundle flags=new PersistableBundle();flags.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(flags);}
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        }));
        button(body,R.string.remote_share_invite,()->authorize(()->startActivity(Intent.createChooser(sharePayload(invitation),getString(R.string.remote_share_invite)))));
        button(body,R.string.remote_cancel_invite,()->authorize(()->{feature.cancel(invitation.id);finish();}));
        candidates=PersonUi.column(this);body.addView(candidates);renderCandidates();
    }
    static Intent sharePayload(PairingInvite invite){return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,invite.code());}
    private void authorizeEnrollment(Runnable action){
        if(com.subhub.app.people.People.keyholder().state()!=com.subhub.app.security.KeyholderProvider.State.NONE){notice(R.string.remote_read_only);return;}
        if(com.subhub.app.security.ControllerPinManager.isConfigured(this)||new com.subhub.app.security.ControllerAuthenticator(this).isPaired())authorize(action);
        else action.run();
    }
    private void confirm() {
        final String entered=link.getText().toString().trim();
        try{PairingInvite.parse(entered,System.currentTimeMillis());}catch(RuntimeException invalid){link.setError(getString(R.string.remote_expired));return;}
        feature.prepareEnrollment(entered).whenComplete((enrollment,error)->runOnUiThread(()->{
            if(isDestroyed()||isFinishing())return;if(error!=null){notice(R.string.remote_failure);return;}
            com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.remote_pair_question)
                .setView(review(getString(R.string.remote_dom_device),enrollment.domIdentity,enrollment.fingerprint)).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.remote_approve,(dialog,which)->authorizeEnrollment(()->feature.confirmEnrollment(enrollment).whenComplete((value,failure)->runOnUiThread(()->{
                    if(isDestroyed()||isFinishing())return;if(failure!=null)notice(R.string.remote_failure);else{link.setText("");notice(R.string.remote_pending);renderCandidates();}
                })))).show();
        }));
    }
    private android.view.View review(String name,String identity,String fingerprint){
        LinearLayout panel=PersonUi.column(this);int padding=PersonUi.dp(this,20);panel.setPadding(padding,padding,padding,padding);
        LinearLayout person=PersonUi.card(panel);text(person,name,true);text(person,identity.substring(0,8)+"…"+identity.substring(60),false);
        text(person,getString(R.string.remote_fingerprint),true);TextView code=text(person,fingerprint,false);code.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout scope=PersonUi.card(panel);text(scope,getString(R.string.remote_shared_controls),true);text(scope,getString(R.string.remote_pairing_scope),false);text(scope,getString(R.string.remote_activity_pairing_scope),false);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(panel);return scroll;
    }
    private void renderLinked(RemoteStore.Peer current){
        linkedGeneration=current.generation;com.subhub.app.util.PrimaryHeader.bindSecondary(findViewById(android.R.id.content),R.string.remote_pairing,false);body.removeAllViews();text(body,getString(R.string.remote_paired),true);text(body,current.name,false);
        text(body,com.subhub.app.people.ProfileViews.targetDiscriminator(this,RemotePersonRepository.id(current),""),false);
        PersonUi.action(body,current.localRole==RemoteProtocol.Role.DOM?R.string.role_profile:R.string.role_connection,()->{
            if(current.localRole==RemoteProtocol.Role.DOM)com.subhub.app.people.PersonRoutes.open(this,RemotePersonRepository.id(current));
            else new RemoteUi().openConnection(this,com.subhub.app.people.PersonId.LOCAL);
            finish();
        },true);
        button(body,android.R.string.ok,this::finish);candidates=null;
    }
    private void renderCandidates() {
        if(candidates==null)return;
        boolean asDom=getIntent().getBooleanExtra("asDom",false)&&getIntent().getData()==null;
        for(RemoteStore.Peer current:feature.store.peers(false))if(current.state==RemoteStore.State.ACTIVE&&
                (asDom?invitation!=null&&invitation.id.equals(current.generation)&&current.localRole==RemoteProtocol.Role.DOM:current.localRole==RemoteProtocol.Role.SUB)) {
            if(!current.generation.equals(linkedGeneration))renderLinked(current);
            return;
        }
        candidates.removeAllViews();
        for(RemoteStore.Invitation invite:feature.store.invitations(System.currentTimeMillis())) {
            if(!asDom||invitation==null||!invitation.id.equals(invite.id)||invite.candidate==null||invite.candidate.isEmpty())continue;
            LinearLayout request=PersonUi.card(candidates);text(request,invite.name,true);text(request,invite.candidate.substring(0,8)+"…"+invite.candidate.substring(60),false);
            if(!invite.approved)button(request,R.string.remote_approve,()->authorize(()->feature.localIdentity().whenComplete((identity,error)->runOnUiThread(()->{
                if(isDestroyed() || isFinishing())return;if(error!=null){notice(R.string.remote_failure);return;}
                String fingerprint=PairingInvite.fingerprint(identity,invite.candidate,invite.id);
                com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.remote_pair_question).setView(review(invite.name,invite.candidate,fingerprint))
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.remote_approve,(d,w)->authorize(()->feature.approve(invite.id,invite.candidate,fingerprint).whenComplete((v,e)->runOnUiThread(()->{if(e!=null)notice(R.string.remote_failure);else renderCandidates();})))).show();
            }))));
            else text(request,getString(R.string.remote_pending),false);
        }
        for(RemoteStore.Peer item:feature.store.peers(false)) {
            if(asDom?(invitation==null||!invitation.id.equals(item.generation)||item.localRole!=RemoteProtocol.Role.DOM):item.localRole!=RemoteProtocol.Role.SUB)continue;
            text(candidates,item.name,true);text(candidates,getString(item.state==RemoteStore.State.PENDING?R.string.remote_pending:R.string.remote_paired),false);
            if(item.localRole==RemoteProtocol.Role.DOM && item.state==RemoteStore.State.ACTIVE)
                PersonUi.row(candidates,R.drawable.ic_nav_people,item.name,"",()->com.subhub.app.people.PersonRoutes.open(this,RemotePersonRepository.id(item)));
            if(item.state==RemoteStore.State.PENDING)button(candidates,R.string.remote_cancel_invite,()->{
                if(item.localRole==RemoteProtocol.Role.SUB)authorizeEnrollment(()->{feature.cancelEnrollment(item);renderCandidates();});
                else authorize(()->{feature.revoke(item);renderCandidates();});
            });
        }
    }
}
