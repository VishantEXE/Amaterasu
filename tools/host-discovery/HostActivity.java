package dev.questheld.host;

import android.Manifest;
import android.app.Activity;
import android.provider.Settings;
import android.net.Uri;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import java.io.IOException;
import java.net.Inet4Address;
import java.security.GeneralSecurityException;

public final class HostActivity extends Activity {
    private static final String TAG="QuestHeldHost";
    private static final int CAPTURE_CONSENT=1, AUDIO_PERMISSION=2, NOTIFICATIONS_PERMISSION=3, HOTSPOT_PERMISSION=4;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private HostUi ui;
    private HostEndpoint fixture;
    private String hostAddress;
    private String error;
    private boolean consentPending, startPending, resumed, idleAttempted;
    private long startDeadline;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            refreshState();
            if(resumed) handler.postDelayed(this,750);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(state!=null) {
            startPending=state.getBoolean("liveRequested",false);
            consentPending=state.getBoolean("consentPending",false);
            startDeadline=SystemClock.elapsedRealtime()+10000;
        }
        if(LiveHostService.activePairing==null) new HostOverrides(this).restoreAll();
        if(LiveHostService.RESTORE.equals(getIntent().getAction())) new HostOverrides(this).restoreScreen();
        ExitDiagnostics.record(this);
        ui=new HostUi(this,getPreferences(MODE_PRIVATE),new HostUi.Actions(){
            public void start(){ startSharing(); }
            public void stop(){ stopSharing(); }
            public void retry(){ error=null; idleAttempted=false; ensureFixture(); refreshState(); }
            public void pair(String pin){ submitPin(pin); }
            public void copyAddress(){ copyHostAddress(); }
            public void connectionModeChanged(){ updateConnection(); }
            public void hotspot(boolean start){ if(start) startHotspot(); else { DirectHotspot.get(HostActivity.this).stop(); updateConnection(); } }
            public void hotspotSettings(){
                if(LiveHostService.activePairing!=null) return;
                DirectHotspot.get(HostActivity.this).stop(); closeFixture();
                openSettings(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            }
            public void dimPermission(){
                if(!Settings.System.canWrite(HostActivity.this)) openSettings(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,Uri.parse("package:"+getPackageName())));
            }
            public void restoreScreen(){
                new HostOverrides(HostActivity.this).restoreScreen();
                Toast.makeText(HostActivity.this,"Screen recovery requested. Android brightness controls remain available.",Toast.LENGTH_LONG).show();
            }
            public void batterySettings(){ openSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            public void licenses(){ LicenseViewer.show(HostActivity.this); }
        });
        setContentView(ui);
        ui.setDimmingPermission(Settings.System.canWrite(this));
    }

    private void startSharing() {
        if(consentPending || startPending || LiveHostService.activePairing!=null) return;
        error=null;
        if(selectedAddress()==null) { error="Connect Wi-Fi or start a hotspot in Connection first."; refreshState(); return; }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationsAsked",false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationsAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS_PERMISSION); return;
        }
        if(getPreferences(MODE_PRIVATE).getBoolean("dimHost",false) && !Settings.System.canWrite(this)) Toast.makeText(this,"Dimming is inactive. Use Allow brightness control before your next stream.",Toast.LENGTH_LONG).show();
        try {
            StreamSettings selected=ui.settings();
            boolean hevcAvailable=selected.supportsHevc();
            selected.profile(hevcAvailable?1:0,0);
            if(selected.hevc && !hevcAvailable) Toast.makeText(this,"HEVC is unavailable for this mode. H.264 will be used.",Toast.LENGTH_LONG).show();
        } catch(IOException e) { error=e.getMessage(); refreshState(); return; }
        consentPending=true;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) requestCapture();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_PERMISSION);
    }
    private void requestCapture() {
        try {
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),CAPTURE_CONSENT);
        } catch(RuntimeException e) {
            consentPending=false; error="Screen sharing could not be opened. Please try again.";
            Log.w(TAG,"Capture consent unavailable",e); refreshState();
        }
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==NOTIFICATIONS_PERMISSION) { if(results.length==0 || results[0]!=PackageManager.PERMISSION_GRANTED) Toast.makeText(this,"Notifications are disabled. Stop sharing from Amaterasu or Android’s active-app controls.",Toast.LENGTH_LONG).show(); startSharing(); return; }
        if(code==HOTSPOT_PERMISSION) {
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED && ui.directHotspot() && LiveHostService.activePairing==null) DirectHotspot.get(this).start();
            else Toast.makeText(this,"Wi-Fi permission denied. You can use Android hotspot settings instead.",Toast.LENGTH_LONG).show();
            return;
        }
        if(code==AUDIO_PERMISSION) {
            if(results.length==0 || results[0]!=PackageManager.PERMISSION_GRANTED) Toast.makeText(this,"Audio permission was not granted. You can still share your screen.",Toast.LENGTH_LONG).show();
            requestCapture();
        }
    }
    @Override protected void onActivityResult(int code,int result,Intent grant) {
        super.onActivityResult(code,result,grant);
        if(code!=CAPTURE_CONSENT) return;
        consentPending=false;
        if(result!=RESULT_OK || grant==null) { startPending=false; refreshState(); return; }
        closeFixture(); startPending=true; startDeadline=SystemClock.elapsedRealtime()+10000; error=null;
        try {
            startForegroundService(ui.settings().putInto(new Intent(this,LiveHostService.class).putExtra("grant",grant).putExtra("muteHost",ui.muteHost()).putExtra("dimHost",ui.dimHost()).putExtra("directHotspot",ui.directHotspot())));
        } catch(RuntimeException e) {
            startPending=false; error="Sharing could not start. Please try again."; Log.w(TAG,"Live service startup failed",e);
        }
        refreshState();
    }
    private void stopSharing() {
        stopService(new Intent(this,LiveHostService.class)); startPending=false; consentPending=false; error=null;
        handler.postDelayed(this::refreshState,150);
    }
    private void submitPin(String pin) {
        if(!pin.matches("[0-9]{4}")) { ui.pairFeedback("Enter the four-digit PIN shown by Moonlight.",false); return; }
        try {
            Pairing pairing=fixture!=null?fixture.pairing:LiveHostService.activePairing;
            boolean submitted=pairing!=null && pairing.enterPin(pin);
            ui.pairFeedback(submitted?"PIN submitted. Finish connecting in Moonlight.":"Select Amaterasu in Moonlight first, then enter its new PIN.",submitted);
            if(submitted) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(ui.getWindowToken(),0);
        } catch(GeneralSecurityException e) {
            ui.pairFeedback("The PIN could not be submitted. Please try again.",false); Log.w(TAG,"PIN submission failed",e);
        }
    }
    private void copyHostAddress() {
        if(hostAddress==null) return;
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Amaterasu host",hostAddress));
        if(Build.VERSION.SDK_INT<33) Toast.makeText(this,"Host address copied",Toast.LENGTH_SHORT).show();
    }
    private void ensureFixture() {
        if(fixture!=null || idleAttempted || consentPending || startPending || LiveHostService.activePairing!=null) return;
        idleAttempted=true;
        Inet4Address address=selectedAddress(); hostAddress=address==null?null:address.getHostAddress();
        if(address==null) return;
        try { fixture=new HostEndpoint(this,address,null,StreamSettings.fixture(),null); }
        catch(IOException | GeneralSecurityException | RuntimeException e) {
            error="The host could not open. Stop any other streaming host on this device, then try again.";
            Log.e(TAG,"Discovery listener failed",e);
        }
    }
    private void refreshState() {
        if(ui==null) return;
        ui.setDimmingPermission(Settings.System.canWrite(this));
        updateConnection();
        ui.connectionDetail(ui.directHotspot()?(hostAddress!=null && !DirectHotspot.get(this).owned()?"System hotspot interface detected · credentials are in Android settings":DirectHotspot.get(this).summary(ui.revealPassword()))+"\n"+DirectHotspot.get(this).bandNote():"Both devices on the same Wi-Fi LAN. No internet required.",LiveHostService.sessionStatus);
        if(LiveHostService.activePairing!=null) {
            startPending=false; error=null; ui.setState(HostUi.State.SHARING,hostAddress,null,LiveHostService.activeSettings); ui.mediaStatus(LiveHostService.streamingConfirmed);
        } else if(startPending && SystemClock.elapsedRealtime()<startDeadline) {
            ui.setState(HostUi.State.STARTING,hostAddress,null,null);
        } else {
            if(startPending) { startPending=false; error="Sharing did not start. Please check your connection and try again."; }
            if(resumed) ensureFixture();
            ui.setState(hostAddress==null?HostUi.State.NO_NETWORK:error!=null?HostUi.State.ERROR:HostUi.State.READY,hostAddress,error,null);
        }
    }
    private Inet4Address selectedAddress() {
        return ui!=null && ui.directHotspot()?HotspotAddress.find(this):HostEndpoint.wifiAddress(this);
    }
    private void updateConnection() {
        if(ui==null || consentPending || startPending || LiveHostService.activePairing!=null) return;
        if(!ui.directHotspot() && DirectHotspot.get(this).owned()) DirectHotspot.get(this).stop();
        Inet4Address address=selectedAddress();
        String next=address==null?null:address.getHostAddress();
        if(!java.util.Objects.equals(next,hostAddress)) { closeFixture(); hostAddress=next; error=null; }
        if(resumed) ensureFixture();
    }
    private void startHotspot() {
        if(LiveHostService.activePairing!=null || !ui.directHotspot()) return;
        String permission=Build.VERSION.SDK_INT>=33?Manifest.permission.NEARBY_WIFI_DEVICES:Manifest.permission.ACCESS_FINE_LOCATION;
        if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{permission},HOTSPOT_PERMISSION);
        else DirectHotspot.get(this).start();
    }
    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch(RuntimeException e) { Toast.makeText(this,"This settings screen is unavailable. Open Android Settings manually.",Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if(LiveHostService.RESTORE.equals(intent.getAction())) new HostOverrides(this).restoreScreen();
    }
    @Override protected void onStart() {
        super.onStart(); idleAttempted=false; Inet4Address address=selectedAddress(); hostAddress=address==null?null:address.getHostAddress();
    }
    @Override protected void onResume() { super.onResume(); if(LiveHostService.activePairing==null && !startPending) new HostOverrides(this).restoreAll(); resumed=true; handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onPause() { resumed=false; handler.removeCallbacksAndMessages(null); super.onPause(); }
    @Override protected void onStop() { closeFixture(); super.onStop(); }
    @Override protected void onDestroy() {
        if(isFinishing() && !startPending && LiveHostService.activePairing==null) DirectHotspot.get(this).stop();
        super.onDestroy();
    }
    private void closeFixture() { if(fixture!=null) { fixture.close(); fixture=null; } idleAttempted=false; }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("liveRequested",startPending); state.putBoolean("consentPending",consentPending); super.onSaveInstanceState(state);
    }
}
