package dev.questheld.host;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.*;
import android.os.*;
import android.util.Log;
import java.io.IOException;
import java.net.Inet4Address;
import java.security.GeneralSecurityException;

public final class LiveHostService extends Service {
    static final String STOP = "dev.questheld.host.STOP_LIVE";
    static final String RESTORE = "dev.questheld.host.RESTORE_SCREEN";
    private static final String TAG = "QuestHeldHost", CHANNEL = "live-host";
    static volatile Pairing activePairing;
    static volatile StreamSettings activeSettings;
    static volatile boolean streamingConfirmed;
    static volatile String sessionStatus = "Server stopped";
    private final Handler main = new Handler(Looper.getMainLooper());
    private HostEndpoint endpoint;
    private MediaProjection projection;
    private PowerManager.WakeLock wake;
    private boolean destroyed;
    private HostOverrides overrides;
    private MemoryMonitor memory;
    private boolean mute, dim, direct;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (endpoint != null) return START_NOT_STICKY;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Screen streaming",NotificationManager.IMPORTANCE_LOW));
        startForeground(2, notification("Preparing screen sharing"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        overrides=new HostOverrides(this);
        overrides.restoreAll();
        direct=intent.getBooleanExtra("directHotspot",false);
        mute=intent.getBooleanExtra("muteHost",true); dim=intent.getBooleanExtra("dimHost",false);
        getSharedPreferences("session-state",0).edit().putBoolean("open",true).commit();
        SessionState.attach(stage -> main.post(() -> onStage(stage)));
        try {
            MediaProjectionManager captures=getSystemService(MediaProjectionManager.class);
            projection=captures.getMediaProjection(Activity.RESULT_OK,intent.getParcelableExtra("grant"));
            if(projection==null) throw new IllegalStateException("Screen capture permission missing");
            projection.registerCallback(new MediaProjection.Callback(){
                @Override public void onStop(){ if(!destroyed) { SessionState.emit(SessionState.Stage.INTERRUPTED); stopSelf(); } }
            },main);
            Inet4Address address=direct?HotspotAddress.find(this):HostEndpoint.wifiAddress(this);
            if(address==null) throw new IOException("Selected Wi-Fi interface has no IPv4 address");
            StreamSettings selected=StreamSettings.fromIntent(intent);
            endpoint=new HostEndpoint(this,address,projection,selected,this::stopSelf);
            activeSettings=selected; activePairing=endpoint.pairing;
            SessionState.emit(SessionState.Stage.SERVER_READY);
            memory=new MemoryMonitor(this);
            Log.i(TAG,"Live host ready: " + address.getHostAddress());
        } catch(IOException | GeneralSecurityException | RuntimeException e){
            Log.e(TAG,"Could not start live host",e); SessionState.emit(SessionState.Stage.INTERRUPTED); stopSelf();
        }
        // A projection grant cannot be silently replayed after process death.
        return START_NOT_STICKY;
    }
    private Notification notification(String detail){
        PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,LiveHostService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,HostActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent restore=PendingIntent.getActivity(this,1,new Intent(this,HostActivity.class).setAction(RESTORE),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setContentTitle("Amaterasu screen sharing").setContentText(detail)
            .setSmallIcon(dev.questheld.host.R.drawable.ic_amaterasu).setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,"Restore screen",restore).build())
            .addAction(new Notification.Action.Builder(null,"Stop streaming",stop).build()).build();
    }
    private void onStage(SessionState.Stage stage){
        if(destroyed) return;
        sessionStatus=stage.name().replace('_',' ');
        Log.i(TAG,"Session state: "+sessionStatus);
        if(stage==SessionState.Stage.STREAMING){
            streamingConfirmed=true;
            overrides.apply(mute,dim);
            if(wake==null){
                wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":streaming");
                wake.setReferenceCounted(false); wake.acquire();
            }
        } else if(stage==SessionState.Stage.INTERRUPTED || stage==SessionState.Stage.DISCONNECTED){
            streamingConfirmed=false;
            overrides.restoreAll(); releaseWake();
            if(direct) stopSelf();
        }
        getSystemService(NotificationManager.class).notify(2,notification(sessionStatus));
    }
    @Override public void onTrimMemory(int level){
        super.onTrimMemory(level);
        Log.w(TAG,"Android memory-pressure callback level="+level+" state="+sessionStatus);
    }
    private void releaseWake(){ if(wake!=null){ if(wake.isHeld()) wake.release(); wake=null; } }
    @Override public void onDestroy(){
        destroyed=true; SessionState.detach(); main.removeCallbacksAndMessages(null);
        activePairing=null; activeSettings=null; streamingConfirmed=false; sessionStatus="STOPPED";
        cleanup("memory monitor",()->{ if(memory!=null) memory.close(); });
        cleanup("listeners",()->{ if(endpoint!=null) endpoint.close(); });
        cleanup("projection",()->{ if(projection!=null) projection.stop(); });
        cleanup("host overrides",()->{ if(overrides!=null) overrides.restoreAll(); });
        cleanup("CPU wake lock",this::releaseWake);
        cleanup("hotspot",()->{ if(direct) DirectHotspot.get(this).stop(); });
        cleanup("notification",()->stopForeground(STOP_FOREGROUND_REMOVE));
        getSharedPreferences("session-state",0).edit().putBoolean("open",false).commit();
        super.onDestroy();
    }
    private void cleanup(String resource,Runnable release){
        try { release.run(); } catch(RuntimeException e) { Log.w(TAG,"Cleanup failed: "+resource,e); }
    }
}
