package dev.questheld.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;

/** Persistent, best-effort recovery. No code can run after the process is killed. */
final class HostOverrides {
    private final Context context;
    private final SharedPreferences journal;
    private final AudioManager audio;
    HostOverrides(Context context) {
        this.context=context.getApplicationContext();
        journal=context.getSharedPreferences("host-recovery",Context.MODE_PRIVATE);
        audio=context.getSystemService(AudioManager.class);
    }
    synchronized void apply(boolean mute, boolean dim) {
        if(mute && !journal.getBoolean("audioOwned",false)) {
            try {
                boolean alreadyMuted=audio.isStreamMute(AudioManager.STREAM_MUSIC);
                if(!alreadyMuted) {
                    int volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    // Persist before changing the system. Never change calls/alarms/DND.
                    if(!journal.edit().putBoolean("audioOwned",true).putInt("audioVolume",volume).commit()) return;
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_MUTE,0);
                    journal.edit().putInt("audioApplied",audio.getStreamVolume(AudioManager.STREAM_MUSIC)).commit();
                    Log.i("QuestHeldHost","Media output muted; playback-capture compatibility requires device verification");
                }
            } catch(RuntimeException e) { Log.w("QuestHeldHost","Media mute unavailable",e); }
        }
        if(dim && !Settings.System.canWrite(context)) Log.w("QuestHeldHost","Dimming skipped: Modify system settings permission is not granted");
        if(dim && Settings.System.canWrite(context) && !journal.getBoolean("brightnessOwned",false)) {
            try {
                int level=Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128);
                int mode=Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,0);
                if(!journal.edit().putBoolean("brightnessOwned",true).putInt("brightness",level).putInt("brightnessMode",mode).commit()) return;
                if(!Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        || !Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,1)) {
                    restoreScreen();
                    Log.w("QuestHeldHost","Minimum brightness was rejected by firmware");
                } else {
                    journal.edit().putBoolean("brightnessApplied",true).commit();
                    Log.i("QuestHeldHost","Display brightness request accepted: minimum=1 previous="+level+" previousMode="+mode);
                }
            } catch(RuntimeException e) { Log.w("QuestHeldHost","Display dimming unavailable",e); }
        }
    }
    synchronized void restoreAudio() {
        if(!journal.getBoolean("audioOwned",false)) return;
        try {
            int expected=journal.getInt("audioApplied",journal.getInt("audioVolume",-1));
            if(OverrideOwnership.restoreAudio(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audio.isStreamMute(AudioManager.STREAM_MUSIC),expected)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);
            }
            journal.edit().remove("audioOwned").remove("audioVolume").remove("audioApplied").commit();
        } catch(RuntimeException e) { Log.w("QuestHeldHost","Audio recovery pending",e); }
    }
    synchronized void restoreScreen() {
        if(!journal.getBoolean("brightnessOwned",false)) return;
        if(!Settings.System.canWrite(context)) return; // Retain the journal until permission is restored.
        try {
            int level=Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,-1);
            int mode=Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,-1);
            OverrideOwnership.Brightness action=OverrideOwnership.brightness(level,mode,journal.getInt("brightness",-2),journal.getBoolean("brightnessApplied",false));
            if(action==OverrideOwnership.Brightness.RESTORE_ALL) {
                if(!Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,journal.getInt("brightness",128))) return;
                if(!Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,journal.getInt("brightnessMode",0))) return;
            } else if(action==OverrideOwnership.Brightness.RESTORE_MODE) {
                // Recovery if the process died between changing mode and changing brightness.
                if(!Settings.System.putInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,journal.getInt("brightnessMode",0))) return;
            }
            journal.edit().remove("brightnessOwned").remove("brightness").remove("brightnessMode").remove("brightnessApplied").commit();
        } catch(RuntimeException e) { Log.w("QuestHeldHost","Screen recovery pending",e); }
    }
    synchronized void restoreAll() { restoreAudio(); restoreScreen(); }
    boolean screenRecoveryPending() { return journal.getBoolean("brightnessOwned",false); }
}
