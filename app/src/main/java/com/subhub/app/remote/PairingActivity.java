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
    private final Handler clock=new Handler(Looper.getMainLooper());
    private LinearLayout candidates;private PairingInvite invitation;private EditText link;private boolean creating;
    private final Runnable poll=new Runnable(){public void run(){renderCandidates();clock.postDelayed(this,2000);}};
    @Override protected boolean requiresDom(){return false;}
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);page(R.string.remote_pairing);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        boolean asDom=getIntent().getBooleanExtra("asDom",false) && getIntent().getData()==null;
        if(asDom)button(body,R.string.remote_create_invite,this::createInvitation);
        else {
            link=input(body,R.string.remote_invite_input,getIntent().getData()==null?"":getIntent().getData().toString());
            link.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
            button(body,R.string.remote_approve,()->authorize(this::confirm));
        }
        button(body,R.string.remote_settings,()->startActivity(new Intent(this,AuthenticatorActivity.class)));
        candidates=PersonUi.column(this);body.addView(candidates);
    }
    private void createInvitation() {
        authorize(()->{
            if(creating)return;
            creating=true;
            feature.createInvite().whenComplete((value,error)->runOnUiThread(()->{
                if(isDestroyed()||isFinishing())return;
                creating=false;
                if(error!=null){notice(R.string.remote_failure);return;}
                invitation=value;
                renderInvite();
            }));
        });
    }
    @Override protected void onResume(){super.onResume();clock.post(poll);}
    @Override protected void onPause(){clock.removeCallbacks(poll);super.onPause();}
    private void renderInvite() {
        PairingInvite shown=invitation;
        body.removeAllViews();
        text(body,java.text.DateFormat.getTimeInstance().format(new java.util.Date(invitation.expiresAt)),false);
        work(()->{
            BitMatrix matrix=new MultiFormatWriter().encode(shown.link(),BarcodeFormat.QR_CODE,640,640);
            Bitmap bitmap=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);
            for(int y=0;y<640;y++)for(int x=0;x<640;x++)bitmap.setPixel(x,y,matrix.get(x,y)?0xff000000:0xffffffff);return bitmap;
        },bitmap->{if(invitation!=shown){bitmap.recycle();return;}ImageView qr=new ImageView(this);qr.setImageBitmap(bitmap);qr.setContentDescription(getString(R.string.remote_pairing));
            body.addView(qr,0,new LinearLayout.LayoutParams(-1,PersonUi.dp(this,280)));});
        button(body,R.string.remote_copy_invite,()->{
            ClipData clip=ClipData.newPlainText(getString(R.string.remote_pairing),invitation.link());
            if(Build.VERSION.SDK_INT>=33){PersistableBundle flags=new PersistableBundle();flags.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(flags);}
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        });
        button(body,R.string.remote_share_invite,()->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,invitation.link()),getString(R.string.remote_share_invite))));
        button(body,R.string.remote_cancel_invite,()->authorize(()->{feature.cancel(invitation.id);finish();}));
        candidates=PersonUi.column(this);body.addView(candidates);renderCandidates();
    }
    private void confirm() {
        final String entered=link.getText().toString().trim();final PairingInvite invite;
        try{invite=PairingInvite.parse(entered,System.currentTimeMillis());}catch(RuntimeException invalid){link.setError(getString(R.string.remote_expired));return;}
        feature.localIdentity().whenComplete((identity,error)->runOnUiThread(()->{
            if(isDestroyed() || isFinishing())return;if(error!=null){notice(R.string.remote_failure);return;}
            String fingerprint=PairingInvite.fingerprint(invite.domIdentity,identity,invite.id);
            com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.remote_pair_question)
                .setMessage(invite.domIdentity+"\n\n"+fingerprint).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.remote_approve,(dialog,which)->authorize(()->feature.acceptInvite(entered,"Dom device").whenComplete((value,failure)->runOnUiThread(()->{
                    if(isDestroyed() || isFinishing())return;if(failure!=null)notice(R.string.remote_failure);else{link.setText("");notice(R.string.remote_pending);renderCandidates();}
                })))).show();
        }));
    }
    private void renderCandidates() {
        if(candidates==null)return;candidates.removeAllViews();
        for(RemoteStore.Invitation invite:feature.store.invitations(System.currentTimeMillis())) {
            if(invite.candidate==null || invite.candidate.isEmpty())continue;
            text(candidates,invite.name,true);text(candidates,invite.candidate,false);
            if(!invite.approved)button(candidates,R.string.remote_approve,()->authorize(()->feature.localIdentity().whenComplete((identity,error)->runOnUiThread(()->{
                if(isDestroyed() || isFinishing())return;if(error!=null){notice(R.string.remote_failure);return;}
                String fingerprint=PairingInvite.fingerprint(identity,invite.candidate,invite.id);
                com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.remote_pair_question).setMessage(invite.name+"\n\n"+invite.candidate+"\n\n"+fingerprint)
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.remote_approve,(d,w)->authorize(()->feature.approve(invite.id,invite.candidate,fingerprint).whenComplete((v,e)->runOnUiThread(()->{if(e!=null)notice(R.string.remote_failure);else renderCandidates();})))).show();
            }))));
            else text(candidates,getString(R.string.remote_pending),false);
        }
        for(RemoteStore.Peer item:feature.store.peers(false)) {
            text(candidates,item.name+" · "+item.state.name(),false);
            if(item.localRole==RemoteProtocol.Role.DOM && item.state==RemoteStore.State.ACTIVE)
                PersonUi.row(candidates,R.drawable.ic_nav_people,item.name,"",()->com.subhub.app.people.PersonRoutes.open(this,RemotePersonRepository.id(item)));
            if(item.state==RemoteStore.State.PENDING)button(candidates,R.string.remote_cancel_invite,()->authorize(()->{feature.revoke(item);renderCandidates();}));
        }
    }
}
