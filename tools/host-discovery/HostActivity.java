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
    private static final int CAPTURE_CONSENT=1, AUDIO_PERMISSION=2, NOTIFICATIONS_PERMISSION=3;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private HostUi ui;
    private HostEndpoint fixture;
    private HostPanels.Panel panel;
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
            public void hotspotSettings(){
                if(LiveHostService.activePairing!=null) return;
                Intent tethering=new Intent("android.settings.TETHER_SETTINGS");
                try { startActivity(tethering); }
                catch(android.content.ActivityNotFoundException | SecurityException e) {
                    openSettings(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                }
            }
            public void liveStats(){ showStats(); }
            public boolean supportsAv1(StreamSettings settings){ return settings.supports(StreamSettings.AV1); }
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
        if(selectedAddress()==null) {
            error=ui.directHotspot()?"Turn on the Wi-Fi hotspot in Android settings first."
                    :"Connect both devices to the same Wi-Fi network first.";
            refreshState(); return;
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationsAsked",false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationsAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS_PERMISSION); return;
        }
        if(getPreferences(MODE_PRIVATE).getBoolean("dimHost",false) && !Settings.System.canWrite(this)) Toast.makeText(this,"Dimming is inactive. Use Allow brightness control before your next stream.",Toast.LENGTH_LONG).show();
        try {
            StreamSettings selected=ui.settings();
            int available=selected.preferredAvailableCodec();
            selected.profile(available,0);
            if(selected.codec!=available) Toast.makeText(this,StreamSettings.codecName(selected.codec)
                    +" is unavailable for this mode. "+StreamSettings.codecName(available)+" will be used.",Toast.LENGTH_LONG).show();
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
    private void showStats() {
        if(LiveHostService.activePairing==null) return;
        dismissPanel();
        panel=new HostPanels.Panel(this,"Live stream stats",statsText());
        panel.dialog.show();
    }
    private void dismissPanel() {
        if(panel!=null) panel.dialog.dismiss();
        panel=null;
    }
    private String statsText() {
        LiveStats stats=SessionState.stats();
        if(!LiveHostService.streamingConfirmed) return "Launch Screen in your client to see live stats.";
        if(stats==null) return "Measuring the first frames…";
        if(SystemClock.elapsedRealtime()-stats.sampledAtMs>5000)
            return "Waiting for new stream data.\n\nLast sample\n"+stats.summary();
        return stats.summary();
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
        Pairing pairing=fixture!=null?fixture.pairing:LiveHostService.activePairing;
        ui.connectionDetail(ui.directHotspot()?(hostAddress!=null
                ?"Android hotspot detected. Find the Wi-Fi name and password in Android settings."
                :"Turn on the Wi-Fi hotspot in Android settings.")
                :"Both devices on the same Wi-Fi LAN. No internet required.",LiveHostService.sessionStatus);
        ui.connectionSetup(hostAddress,pairing==null?"Pairing unavailable":pairing.status());
        if(panel!=null) {
            if(!panel.dialog.isShowing() || LiveHostService.activePairing==null) {
                dismissPanel();
            } else {
                String text=statsText();
                if(!text.contentEquals(panel.detail.getText())) panel.detail.setText(text);
            }
        }
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
        Inet4Address address=selectedAddress();
        String next=address==null?null:address.getHostAddress();
        if(!java.util.Objects.equals(next,hostAddress)) { closeFixture(); hostAddress=next; error=null; }
        if(resumed) ensureFixture();
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
    @Override protected void onPause() { resumed=false; handler.removeCallbacksAndMessages(null); dismissPanel(); super.onPause(); }
    @Override protected void onStop() { closeFixture(); super.onStop(); }
    private void closeFixture() { if(fixture!=null) { fixture.close(); fixture=null; } idleAttempted=false; }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("liveRequested",startPending); state.putBoolean("consentPending",consentPending); super.onSaveInstanceState(state);
    }
}
