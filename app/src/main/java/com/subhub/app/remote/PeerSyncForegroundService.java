package com.subhub.app.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import com.subhub.app.R;
import java.util.concurrent.CompletableFuture;

/** Keeps the authenticated peer endpoint available for active pairs, independently of pairing clocks. */
public final class PeerSyncForegroundService extends Service {
    static final String CHANNEL="peer_connection";
    static final int NOTIFICATION=7403;
    interface FeatureFactory { RemoteFeature get(Context context); }
    private static FeatureFactory factory=RemoteFeature::get;
    private final Handler main=new Handler(Looper.getMainLooper());
    private RemoteFeature feature;
    private RemoteFeature.ConnectedServiceLease lease;
    private CompletableFuture<RemoteFeature.ConnectedServiceLease> acquiring;
    private boolean destroyed;
    private long acquisition;

    /** Called from a visible Activity or the existing user-started pairing operation, never boot or a job. */
    static void requestStart(Context context){
        try{context.startForegroundService(new Intent(context,PeerSyncForegroundService.class));}
        catch(RuntimeException unavailable){android.util.Log.w("PeerSync","Connected service start deferred");}
    }
    @Override public void onCreate(){
        super.onCreate();
        NotificationChannel channel=new NotificationChannel(CHANNEL,getString(R.string.peer_sync_channel),NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(destroyed)return START_NOT_STICKY;
        try{
            Notification notification=notification();
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION,notification);
        }catch(RuntimeException unavailable){stopSelf();return START_NOT_STICKY;}
        if(lease!=null&&!lease.isClosed()||acquiring!=null)return START_STICKY;
        feature=factory.get(this);
        long attempt=++acquisition;
        acquiring=feature.acquireConnectedService(()->main.post(()->{if(!destroyed&&attempt==acquisition)finish();}));
        acquiring.whenComplete((acquired,failure)->main.post(()->{
            if(destroyed||attempt!=acquisition){if(acquired!=null)acquired.close();return;}
            acquiring=null;
            if(failure!=null||acquired==null||acquired.isClosed()){if(acquired!=null)acquired.close();finish();return;}
            lease=acquired;
        }));
        // Android may restore an already user-started service. A force-stopped/user-stopped app is not revived here.
        return START_STICKY;
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,NOTIFICATION,new Intent(this,RemoteConnectionActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_nav_people)
                .setContentTitle(getString(R.string.peer_sync_notification_title)).setContentText(getString(R.string.peer_sync_notification_body))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW).setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setCategory(NotificationCompat.CATEGORY_SERVICE).build();
    }
    private void finish(){
        if(destroyed)return;
        if(lease!=null){lease.close();lease=null;}
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        destroyed=true;acquisition++;
        // A late acquisition callback closes its own lease instead of reviving a destroyed service.
        if(lease!=null){lease.close();lease=null;}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
