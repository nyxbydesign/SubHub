package com.subhub.app.remote;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import com.subhub.app.people.*;
import com.subhub.app.security.ControllerPinManager;

public final class RemoteUi implements ConnectionUi {
    private static Application application;
    public static void install(Application app) {
        application = app;
        ControllerPinManager.initialize(app);
        RemoteFeature feature=RemoteFeature.get(app);RemotePersonRepository repository=new RemotePersonRepository(app,feature);
        com.subhub.app.activity.ActivitySources.install(feature.activity);feature.activity.install();
        People.installRemoteRepository(repository);People.installRemoteAuthority(feature::controlsLocal);People.installKeyholder(keyholderProvider(feature));
        People.installLocalProjection(repository::localProjection);People.installConnectionUi(new RemoteUi());
        com.subhub.app.relationships.RelationshipUi.install(new FormalRelationshipProvider(app,feature.store));
        feature.installLifecycle(app);
    }
    static com.subhub.app.security.KeyholderProvider keyholderProvider(RemoteFeature feature){
        return new com.subhub.app.security.KeyholderProvider(){
            public boolean withLocalAuthority(java.util.function.BooleanSupplier action){
                try{return feature.recoveryReady()&&feature.store.authorityAccess(()->feature.keyholder.withLocalAuthority(action));}catch(RuntimeException unavailable){return false;}
            }
            public State state(){
                try{return feature.recoveryReady()?feature.store.authorityObservation(feature.keyholder::state):State.UNAVAILABLE;}catch(RuntimeException unavailable){return State.UNAVAILABLE;}
            }
            public String assignedDomName(){
                try{return feature.store.authorityObservation(feature.keyholder::assignedDomName);}catch(RuntimeException unavailable){return "";}
            }
            public com.subhub.app.security.ControllerAuthenticator.Result verifyCode(String code){
                try{return feature.recoveryReady()?feature.store.authorityAccess(()->feature.keyholder.verifyCode(code)):com.subhub.app.security.ControllerAuthenticator.Result.UNAVAILABLE;}
                catch(RuntimeException unavailable){return com.subhub.app.security.ControllerAuthenticator.Result.UNAVAILABLE;}
            }
        };
    }
    @Override public void openPairing(Activity activity){openPairing(activity,ControllerPinManager.isDomModeActive());}
    public void openPairing(Activity activity,boolean asDom){
        Intent intent=new Intent(activity,PairingActivity.class).putExtra("asDom",asDom);
        if(asDom)activity.startActivityForResult(intent.putExtra(PairingActivity.EXTRA_DIRECT_INVITATION,true),0x5348);
        else activity.startActivity(intent);
    }
    @Override public void openConnection(Activity activity,PersonId id){
        if(id.isLocal()) {
            for(RemoteStore.Peer peer:RemoteFeature.get(activity).store.peers(false))if(peer.localRole==RemoteProtocol.Role.SUB){open(activity,RemotePersonRepository.id(peer),RemoteConnectionActivity.class);return;}
            openPairing(activity,false);return;
        }
        open(activity,id,RemoteConnectionActivity.class);
    }
    public void openStats(Activity activity,PersonId id){open(activity,id,com.subhub.app.stats.StatsActivity.class);}
    @Override public boolean bindSessionControls(Activity activity,android.widget.LinearLayout parent,PersonSnapshot person) {
        if (person.id.isLocal()) return false;
        RemoteDurationControls duration = new RemoteDurationControls(activity,parent,person);
        duration.bind();
        new RemoteSessionControls(activity,parent,person,duration::hasUnappliedDraft).bind();
        return true;
    }
    @Override public boolean canEditSection(PersonId id, String section) {
        try { RemoteFeature feature = RemoteFeature.get((android.content.Context) application);
            RemoteStore.Peer peer = new RemotePersonRepository(application, feature).peer(id);
            return RemoteFeaturePolicy.remoteAllowed(feature.store, peer, section);
        } catch (RuntimeException unavailable) { return false; }
    }
    public void editSection(Activity activity,PersonId id,String section){
        if (!canEditSection(id, section)) return;
        activity.startActivity(new Intent(activity,PersonRoutes.sectionActivity(section)).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()));
    }
    public void endSession(Activity activity,PersonId id,String expected) {
        if(!id.isLocal())activity.startActivity(new Intent(activity,RemoteEndActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()).putExtra("expectedSession",expected));
    }
    private void open(Activity activity,PersonId id,Class<? extends Activity> target) {
        if(id.isLocal())return;activity.startActivity(new Intent(activity,target).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()));
    }
}
