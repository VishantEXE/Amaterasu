package dev.questheld.host;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.SoftApConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** Application-context owner; never retains an Activity or silently enables tethering. */
final class DirectHotspot {
    private static DirectHotspot instance;
    static synchronized DirectHotspot get(Context context) {
        if(instance==null) instance=new DirectHotspot(context.getApplicationContext());
        return instance;
    }
    private final WifiManager wifi;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private int generation;
    private boolean starting;
    private String status="Hotspot off", ssid, password;
    private DirectHotspot(Context context) { wifi=context.getSystemService(WifiManager.class); }
    synchronized void start() {
        if(starting || reservation!=null) return;
        final int request=++generation;
        starting=true; status="Starting local-only hotspot…";
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback(){
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation value) {
                    synchronized(DirectHotspot.this) {
                        if(request!=generation) { value.close(); return; }
                        reservation=value; starting=false;
                        if(Build.VERSION.SDK_INT>=30) {
                            SoftApConfiguration config=value.getSoftApConfiguration();
                            ssid=config==null?null:config.getSsid(); password=config==null?null:config.getPassphrase();
                        } else {
                            android.net.wifi.WifiConfiguration config=value.getWifiConfiguration();
                            ssid=config==null?null:config.SSID; password=config==null?null:config.preSharedKey;
                        }
                        status="Local-only hotspot ready · no internet";
                    }
                }
                @Override public void onStopped() {
                    synchronized(DirectHotspot.this) {
                        if(request!=generation) return;
                        reservation=null; starting=false; ssid=null; password=null; status="Hotspot stopped by Android";
                    }
                    if(LiveHostService.activePairing!=null) SessionState.emit(SessionState.Stage.INTERRUPTED);
                }
                @Override public void onFailed(int reason) {
                    synchronized(DirectHotspot.this) {
                        if(request!=generation) return;
                        starting=false; status="Hotspot unavailable ("+reason+"). Use Android hotspot settings.";
                    }
                }
            },new Handler(Looper.getMainLooper()));
        } catch(RuntimeException e) {
            starting=false; status="Hotspot unavailable. Check Wi-Fi permissions/location or use Android settings.";
        }
    }
    synchronized void stop() {
        generation++; starting=false;
        WifiManager.LocalOnlyHotspotReservation owned=reservation; reservation=null;
        ssid=null; password=null; status="Hotspot off";
        if(owned!=null) owned.close();
    }
    synchronized boolean owned() { return reservation!=null || starting; }
    synchronized String summary(boolean reveal) {
        String text=status;
        if(ssid!=null) text+="\nSSID: "+ssid+"\nPassword: "+(reveal?(password==null?"Open network":password):"••••••••");
        return text;
    }
    String bandNote() {
        try { return wifi.is5GHzBandSupported()?"5 GHz supported by Wi-Fi hardware; Android chooses the local-only band. For band selection, use Android hotspot settings.":"5 GHz support was not reported. Android controls the hotspot band."; }
        catch(RuntimeException e) { return "Hotspot band is managed by Android."; }
    }
}
