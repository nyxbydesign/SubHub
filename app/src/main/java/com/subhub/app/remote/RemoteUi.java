package com.subhub.app.remote;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import com.subhub.app.people.*;
import com.subhub.app.security.ControllerPinManager;

public final class RemoteUi implements ConnectionUi {
    public static void install(Application app) {
        RemoteFeature feature=RemoteFeature.get(app);RemotePersonRepository repository=new RemotePersonRepository(app,feature);
        People.installRemoteRepository(repository);People.installRemoteAuthority(feature::controlsLocal);People.installKeyholder(new com.subhub.app.security.KeyholderProvider(){
            public boolean withLocalAuthority(java.util.function.BooleanSupplier action){return feature.recoveryReady()&&feature.keyholder.withLocalAuthority(action);}
            public State state(){return feature.recoveryReady()?feature.keyholder.state():State.UNAVAILABLE;}
            public String assignedDomName(){return feature.keyholder.assignedDomName();}
            public com.subhub.app.security.ControllerAuthenticator.Result verifyCode(String code){return feature.recoveryReady()?feature.keyholder.verifyCode(code):com.subhub.app.security.ControllerAuthenticator.Result.UNAVAILABLE;}
        });
        People.installLocalProjection(repository::localProjection);People.installConnectionUi(new RemoteUi());feature.installLifecycle(app);
    }
    @Override public void openPairing(Activity activity){openPairing(activity,ControllerPinManager.isDomModeActive());}
    public void openPairing(Activity activity,boolean asDom){activity.startActivity(new Intent(activity,PairingActivity.class).putExtra("asDom",asDom));}
    @Override public void openConnection(Activity activity,PersonId id){
        if(id.isLocal()) {
            for(RemoteStore.Peer peer:RemoteFeature.get(activity).store.peers(false))if(peer.localRole==RemoteProtocol.Role.SUB){open(activity,RemotePersonRepository.id(peer),RemoteConnectionActivity.class);return;}
            openPairing(activity,false);return;
        }
        open(activity,id,RemoteConnectionActivity.class);
    }
    public void openContracts(Activity activity,PersonId id){open(activity,id,RemoteLibraryActivity.class);}
    public void openStats(Activity activity,PersonId id){open(activity,id,RemoteStatsActivity.class);}
    public void editSection(Activity activity,PersonId id,String section){activity.startActivity(new Intent(activity,RemoteContractActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()).putExtra("section",section));}
    public void endSession(Activity activity,PersonId id,String expected) {
        if(!id.isLocal())activity.startActivity(new Intent(activity,RemoteEndActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()).putExtra("expectedSession",expected));
    }
    private void open(Activity activity,PersonId id,Class<? extends Activity> target) {
        if(id.isLocal())return;activity.startActivity(new Intent(activity,target).putExtra(PersonRoutes.EXTRA_PERSON_ID,id.value()));
    }
}
