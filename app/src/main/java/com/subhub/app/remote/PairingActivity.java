package com.subhub.app.remote;

import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.subhub.app.R;
import com.subhub.app.people.PersonUi;

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
    private boolean restoringInvitation,invitationUnavailable;
    private LinearLayout lifecyclePanel;
    private RemoteFeature.PairingOperation operation;
    private String operationId,operationGeneration;
    private boolean resumed,operationBusy,preparingStart,statusUnavailable,discoveringCreation;
    private long visibleRevision;
    private long selectionRevision;
    private long creationStartRevision=-1;
    private String startingObservationKey;
    private long startingObservedAt;
    private final Runnable poll=new Runnable(){public void run(){if(!resumed)return;renderCandidates();refreshOperation();clock.postDelayed(this,2000);}};
    @Override protected boolean requiresDom(){
        if(PairingForegroundService.OPEN.equals(getIntent().getAction()))
            return "CREATE".equals(getIntent().getStringExtra(PairingForegroundService.DIRECTION));
        return getIntent().getBooleanExtra("asDom",false)&&getIntent().getData()==null;
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);page(R.string.remote_pairing);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        boolean asDom=requiresDom();
        if(asDom?!com.subhub.app.security.DeviceRolePreferences.supportsDom(this):!com.subhub.app.security.DeviceRolePreferences.supportsSub(this)){finish();return;}
        if(asDom) {
            creation=new androidx.lifecycle.ViewModelProvider(this).get(InvitationCreation.class);
            if(state!=null)creation.directAttempted|=state.getBoolean("pairingDirectAttempted",false)||state.containsKey("invitationId");
            if(directInvitation()&&!creation.directAttempted)text(body,getString(R.string.role_loading),false).setTag("pairing_invitation_loading");
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
            PersonUi.action(body,R.string.remote_approve,()->{
                final long revision=visibleRevision;
                authorizeEnrollment(()->confirm(revision));
            },true);
        }
        candidates=PersonUi.column(this);body.addView(candidates);
        attachLifecyclePanel();
        if(PairingForegroundService.OPEN.equals(getIntent().getAction())) {
            String direction=getIntent().getStringExtra(PairingForegroundService.DIRECTION);
            if(!(asDom?"CREATE":"ACCEPT").equals(direction)){finish();return;}
            operationId=getIntent().getStringExtra(PairingForegroundService.ID);
            operationGeneration=getIntent().getStringExtra(PairingForegroundService.GENERATION);
        } else if(state!=null) {
            operationId=state.getString("pairingOperationId");operationGeneration=state.getString("pairingOperationGeneration");
        }
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
        state.putString("pairingOperationId",operationId);state.putString("pairingOperationGeneration",operationGeneration);
        state.putBoolean("pairingDirectAttempted",creation!=null&&creation.directAttempted);
        PairingInvite saved=invitation;
        if(saved==null&&creation!=null&&creation.request!=null&&!creation.request.isCompletedExceptionally())
            saved=creation.request.getNow(null);
        if(saved!=null)state.putString("invitationId",saved.id);
        super.onSaveInstanceState(state);
    }
    /** Retain one accepted creation across rotation; only its identifier enters saved state. */
    public static final class InvitationCreation extends androidx.lifecycle.ViewModel {
        java.util.concurrent.CompletableFuture<PairingInvite> request;
        boolean directAttempted;
        public InvitationCreation() { }
    }
    private void createInvitation() {
        final long interactionRevision=visibleRevision;
        authorize(()->{
            if(!visible()||interactionRevision!=visibleRevision||!requiresDom()||discoveringCreation
                    ||!com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked())return;
            if(creation.request!=null){observeCreation();return;}
            discoveringCreation=true;creation.directAttempted=true;
            final long revision=interactionRevision;
            feature.currentPairingOperation(true).whenComplete((current,error)->runOnUiThread(()->{
                discoveringCreation=false;
                if(!visible()||revision!=visibleRevision){showCreationAction();return;}
                if(error!=null){invitationFailure(R.string.pairing_status_unavailable);return;}
                if(current!=null&&(current.live||!current.clockCertain||current.active)){
                    acceptOperation(current);
                    showRetainedOperation();
                    restoreOperationInvitation(current);
                    return;
                }
                // The backend reuses live invitations and retires only authoritatively expired clocks.
                // Do not let an expired UI snapshot prevent that guarded replacement.
                if(creation.request==null){
                    selectionRevision++;operation=null;operationId=null;operationGeneration=null;invitationUnavailable=false;
                    body.removeAllViews();
                    text(body,getString(R.string.role_loading),false).setTag("pairing_invitation_loading");
                    candidates=PersonUi.column(this);body.addView(candidates);attachLifecyclePanel();
                    creationStartRevision=interactionRevision;
                    creation.request=feature.createInvite();
                }
                observeCreation();
            }));
        });
    }
    private void observeCreation() {
        InvitationCreation captured=creation;
        java.util.concurrent.CompletableFuture<PairingInvite> request=captured.request;
        if(request==null||observedCreation==request)return;
        observedCreation=request;
        final long interactionRevision=creationStartRevision;
        java.lang.ref.WeakReference<PairingActivity> weak=new java.lang.ref.WeakReference<>(this);
        request.whenComplete((value,error)->{
            PairingActivity page=weak.get();if(page==null)return;
            page.runOnUiThread(()->{
                if(page.isDestroyed()||page.isFinishing()||page.creation!=captured||captured.request!=request)return;
                if(!page.requiresDom()||!com.subhub.app.security.ControllerPinManager.isDomWorkspaceUnlocked()
                        ||!com.subhub.app.security.DeviceRolePreferences.supportsDom(page)){page.finish();return;}
                if(error!=null||value==null){page.invitationFailure(error==null?R.string.remote_expired:R.string.remote_failure);return;}
                page.invitation=value;page.renderInvite();page.refreshOperation();
                page.startNewOperation(value.id,true,interactionRevision);
            });
        });
    }
    private void invitationFailure(int message) {
        body.removeAllViews();text(body,getString(message),false);
        button(body,R.string.role_retry,()->{creation.request=null;observedCreation=null;createInvitation();});
        candidates=PersonUi.column(this);body.addView(candidates);
        attachLifecyclePanel();
    }
    @Override protected void onResume(){
        super.onResume();if(isFinishing())return;
        resumed=true;visibleRevision++;
        if(requiresDom()&&invitation==null&&!restoringInvitation) {
            if(creation.request!=null)observeCreation();
            else if(directInvitation()&&!creation.directAttempted){
                long revision=visibleRevision;
                // LifecycleRegistry reaches RESUMED after onResume returns.
                clock.post(()->{if(visible()&&revision==visibleRevision)createInvitation();});
            }
        }
        clock.removeCallbacks(poll);clock.post(poll);
        clock.post(this::showCreationAction);
    }
    @Override protected void onPause(){resumed=false;visibleRevision++;clock.removeCallbacks(poll);super.onPause();}
    @Override protected void onDestroy(){clock.removeCallbacksAndMessages(null);super.onDestroy();}
    private boolean visible(){return resumed&&!isFinishing()&&!isDestroyed()
            &&getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);}
    private boolean asDom(){return requiresDom();}
    private void showCreationAction(){
        if(!visible()||creation==null||creation.request!=null||discoveringCreation
                ||!creation.directAttempted||body.findViewWithTag("pairing_invitation_loading")==null)return;
        body.removeAllViews();PersonUi.action(body,R.string.remote_create_invite,this::createInvitation,true);
        candidates=PersonUi.column(this);body.addView(candidates);attachLifecyclePanel();
    }
    private void showRetainedOperation(){
        body.removeAllViews();
        candidates=PersonUi.column(this);body.addView(candidates);
        attachLifecyclePanel();renderCandidates();
    }
    private void attachLifecyclePanel(){
        lifecyclePanel=PersonUi.column(this);body.addView(lifecyclePanel);renderOperation();
    }
    private void acceptOperation(RemoteFeature.PairingOperation current){
        if(current!=null&&(operation==null||!current.id.equals(operation.id)||!current.generation.equals(operation.generation)))selectionRevision++;
        statusUnavailable=false;operation=current;
        if(current!=null){operationId=current.id;operationGeneration=current.generation;}
        renderOperation();
    }
    private void refreshOperation(){
        if(!visible()||operationBusy||discoveringCreation)return;
        operationBusy=true;
        final long selected=selectionRevision;
        final String requestedId=operationId,requestedGeneration=operationGeneration;
        java.util.concurrent.CompletableFuture<RemoteFeature.PairingOperation> request=
                operationId==null||operationGeneration==null?feature.currentPairingOperation(asDom())
                        :feature.pairingStatus(operationId,operationGeneration,asDom());
        request.whenComplete((current,error)->runOnUiThread(()->{
            operationBusy=false;if(isFinishing()||isDestroyed())return;
            if(selected!=selectionRevision||!java.util.Objects.equals(requestedId,operationId)
                    ||!java.util.Objects.equals(requestedGeneration,operationGeneration))return;
            if(error!=null){statusUnavailable=true;renderOperation();return;}
            acceptOperation(current);
            if(current!=null&&asDom()&&invitation==null&&!restoringInvitation)restoreOperationInvitation(current);
        }));
    }
    private void restoreOperationInvitation(RemoteFeature.PairingOperation current){
        if(!asDom()||current.active||!current.live||!current.clockCertain||restoringInvitation||invitation!=null)return;
        restoringInvitation=true;
        work(()->feature.restoreInvitation(current.id),restored->{
            restoringInvitation=false;
            if(operation==null||!current.id.equals(operation.id)||!current.generation.equals(operation.generation))return;
            if(restored!=null){invitationUnavailable=false;invitation=restored;creation.request=java.util.concurrent.CompletableFuture.completedFuture(restored);renderInvite();}
            else if("PENDING".equals(current.state)){invitationUnavailable=true;renderOperation();}
        },()->{restoringInvitation=false;statusUnavailable=true;renderOperation();});
    }
    private void renderOperation(){
        if(lifecyclePanel==null)return;
        lifecyclePanel.removeAllViews();
        RemoteFeature.PairingOperation shown=operation;
        if(shown==null){
            if(statusUnavailable)text(lifecyclePanel,getString(R.string.pairing_status_unavailable),false);
            return;
        }
        TextView status=text(lifecyclePanel,"",false);status.setTag("pairing_lifecycle_status");
        if(shown.active)status.setText(R.string.remote_paired);
        else if(invitationUnavailable||!shown.live||!shown.clockCertain||shown.remainingMillis<=0)status.setText(R.string.pairing_recovery);
        else {
            RemoteFeature.PairingServiceState service=feature.pairingServiceState(shown.id,shown.generation,shown.asDom);
            String key=shown.direction+":"+shown.id+":"+shown.generation;
            boolean starting=service==RemoteFeature.PairingServiceState.STARTING;
            if(starting&&!key.equals(startingObservationKey)){startingObservationKey=key;startingObservedAt=SystemClock.uptimeMillis();}
            if(!starting)startingObservationKey=null;
            // This bounds only presentation of an unacknowledged OS start, never the pairing lifetime.
            boolean unacknowledged=starting&&SystemClock.uptimeMillis()-startingObservedAt>=10_000;
            int copy=statusUnavailable||unacknowledged?R.string.pairing_status_unavailable
                    :preparingStart||starting?R.string.pairing_starting
                    :service==RemoteFeature.PairingServiceState.RUNNING?R.string.pairing_running
                    :service==RemoteFeature.PairingServiceState.UNAVAILABLE?R.string.pairing_unavailable:R.string.pairing_stopped;
            status.setText(copy);
            // Display only the authoritative sampled remainder, never extend or infer a local deadline.
            text(lifecyclePanel,getString(R.string.pairing_remaining,android.text.format.DateUtils.formatElapsedTime(Math.max(0,shown.remainingMillis)/1000)),false);
            if(!preparingStart&&(statusUnavailable||unacknowledged
                    ||service!=RemoteFeature.PairingServiceState.RUNNING&&!starting))
                button(lifecyclePanel,R.string.pairing_resume,()->resumeOperation(shown)).setTag("pairing_lifecycle_resume");
        }
        if(!shown.active)button(lifecyclePanel,android.R.string.cancel,()->cancelOperation(shown)).setTag("pairing_lifecycle_cancel");
        if(shown.live&&!shown.active&&!invitationUnavailable)text(lifecyclePanel,getString(R.string.pairing_preserved),false);
        if(shown.live&&!shown.active&&!invitationUnavailable&&!PairingForegroundService.drawerAvailable(this)){
            text(lifecyclePanel,getString(R.string.pairing_notifications_blocked),false);
            button(lifecyclePanel,R.string.pairing_notification_settings,()->{
                Intent settings=new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,PairingForegroundService.CHANNEL);
                try{startActivity(settings);}catch(ActivityNotFoundException unavailable){notice(R.string.remote_failure);}
            });
        }
    }
    private void resumeOperation(RemoteFeature.PairingOperation selected){
        if(!visible()||preparingStart)return;
        final long interactionRevision=visibleRevision;
        Runnable action=()->prepareAndStart(selected,interactionRevision);
        if(selected.asDom)authorize(action);else authorizeEnrollment(action);
    }
    private void startNewOperation(String generation,boolean asDom,long interactionRevision){
        if(!visible()||interactionRevision!=visibleRevision)return;
        feature.currentPairingOperation(asDom).whenComplete((current,error)->runOnUiThread(()->{
            if(!visible()||interactionRevision!=visibleRevision)return;
            if(error!=null){statusUnavailable=true;renderOperation();return;}
            if(current==null||!generation.equals(current.generation)||current.asDom!=asDom)return;
            acceptOperation(current);prepareAndStart(current,interactionRevision);
        }));
    }
    private void prepareAndStart(RemoteFeature.PairingOperation selected,long revision){
            if(!visible()||revision!=visibleRevision||preparingStart)return;
            preparingStart=true;renderOperation();
            feature.preparePairingStart(selected.id,selected.generation,selected.asDom).whenComplete((start,error)->runOnUiThread(()->{
                preparingStart=false;
                if(!visible()||revision!=visibleRevision){renderOperation();return;}
                if(error!=null||start==null){statusUnavailable=true;renderOperation();return;}
                RemoteFeature.PairingOperation ready=start.operation;
                if(!selected.id.equals(ready.id)||!selected.generation.equals(ready.generation)
                        ||selected.asDom!=ready.asDom||!selected.direction.equals(ready.direction)){statusUnavailable=true;renderOperation();return;}
                try {
                    // Both the native click and its asynchronous authorization callback must remain resumed.
                    feature.reportPairingServiceState(ready,RemoteFeature.PairingServiceState.STARTING);
                    startingObservationKey=ready.direction+":"+ready.id+":"+ready.generation;startingObservedAt=SystemClock.uptimeMillis();
                    androidx.core.content.ContextCompat.startForegroundService(this,PairingForegroundService.startIntent(this,start));
                } catch(RuntimeException unavailable){feature.reportPairingServiceState(ready,RemoteFeature.PairingServiceState.UNAVAILABLE);}
                refreshOperation();renderOperation();
            }));
    }
    private void cancelOperation(RemoteFeature.PairingOperation selected){
        Runnable action=()->{
            if(!visible())return;
            feature.cancelPairingOperation(selected.id,selected.generation,selected.asDom,null).whenComplete((cancelled,error)->runOnUiThread(()->{
                if(isDestroyed()||isFinishing())return;
                if(error!=null){statusUnavailable=true;renderOperation();return;}
                if(Boolean.TRUE.equals(cancelled)){
                    selectionRevision++;
                    invitation=null;invitationUnavailable=false;operation=null;operationId=null;operationGeneration=null;
                    if(creation!=null){creation.request=null;observedCreation=null;}
                    if(selected.asDom){
                        body.removeAllViews();PersonUi.action(body,R.string.remote_create_invite,this::createInvitation,true);
                        candidates=PersonUi.column(this);body.addView(candidates);attachLifecyclePanel();
                    }
                }
                refreshOperation();renderCandidates();renderOperation();
            }));
        };
        if(selected.asDom)authorize(action);else authorizeEnrollment(action);
    }
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
        // Cancellation is operation-scoped and durable before the service releases its lease.
        candidates=PersonUi.column(this);body.addView(candidates);renderCandidates();
        attachLifecyclePanel();
    }
    static Intent sharePayload(PairingInvite invite){return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,invite.code());}
    private void authorizeEnrollment(Runnable action){
        if(com.subhub.app.people.People.keyholder().state()!=com.subhub.app.security.KeyholderProvider.State.NONE){notice(R.string.remote_read_only);return;}
        if(com.subhub.app.security.ControllerPinManager.isConfigured(this)||new com.subhub.app.security.ControllerAuthenticator(this).isPaired())authorize(action);
        else action.run();
    }
    private void confirm(long interactionRevision) {
        if(!visible()||interactionRevision!=visibleRevision)return;
        final String entered=link.getText().toString().trim();
        final PairingInvite parsed;
        try{parsed=PairingInvite.parse(entered,System.currentTimeMillis());}catch(RuntimeException invalid){link.setError(getString(R.string.remote_expired));return;}
        feature.prepareEnrollment(entered).whenComplete((enrollment,error)->runOnUiThread(()->{
            if(isDestroyed()||isFinishing())return;if(error!=null){notice(R.string.remote_failure);return;}
            com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.remote_pair_question)
                .setView(review(getString(R.string.remote_dom_device),enrollment.domIdentity,enrollment.fingerprint)).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.remote_approve,(dialog,which)->authorizeEnrollment(()->feature.confirmEnrollment(enrollment).whenComplete((value,failure)->runOnUiThread(()->{
                    if(isDestroyed()||isFinishing())return;if(failure!=null)notice(R.string.remote_failure);else{link.setText("");selectionRevision++;operationId=null;operationGeneration=null;notice(R.string.remote_pending);renderCandidates();refreshOperation();startNewOperation(parsed.id,false,interactionRevision);}
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
    private String selectedGeneration(){
        if(invitation!=null)return invitation.id;
        return operation!=null&&(operation.active||operation.live&&operation.clockCertain&&"APPROVED".equals(operation.state))
                ?operation.generation:null;
    }
    private void renderCandidates() {
        if(candidates==null)return;
        boolean asDom=requiresDom();
        for(RemoteStore.Peer current:feature.store.peers(false))if(current.state==RemoteStore.State.ACTIVE&&
                (asDom?current.generation.equals(selectedGeneration())&&current.localRole==RemoteProtocol.Role.DOM:current.localRole==RemoteProtocol.Role.SUB)) {
            if(!current.generation.equals(linkedGeneration))renderLinked(current);
            return;
        }
        candidates.removeAllViews();
        String selectedGeneration=selectedGeneration();
        for(RemoteStore.Invitation invite:feature.store.invitations(System.currentTimeMillis())) {
            if(!asDom||!invite.id.equals(selectedGeneration)||invite.candidate==null||invite.candidate.isEmpty())continue;
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
            if(asDom?(!item.generation.equals(selectedGeneration)||item.localRole!=RemoteProtocol.Role.DOM):item.localRole!=RemoteProtocol.Role.SUB)continue;
            text(candidates,item.name,true);text(candidates,getString(item.state==RemoteStore.State.PENDING?R.string.remote_pending:R.string.remote_paired),false);
            if(item.localRole==RemoteProtocol.Role.DOM && item.state==RemoteStore.State.ACTIVE)
                PersonUi.row(candidates,R.drawable.ic_nav_people,item.name,"",()->com.subhub.app.people.PersonRoutes.open(this,RemotePersonRepository.id(item)));
            // Pending cancellation belongs to the exact lifecycle operation, never the ACTIVE peer.
        }
    }
}
