package dev.questheld.host;

/** Never overwrite a system value that no longer matches the app's override. */
final class OverrideOwnership {
    enum Brightness { RESTORE_ALL, RESTORE_MODE, LEAVE_USER_STATE }
    static boolean restoreAudio(int volume,boolean muted,int applied) {
        return muted && volume==applied;
    }
    static Brightness brightness(int level,int mode,int original,boolean applied) {
        if(mode!=0) return Brightness.LEAVE_USER_STATE;
        if(level==1) return Brightness.RESTORE_ALL;
        if(!applied && level==original) return Brightness.RESTORE_MODE;
        return Brightness.LEAVE_USER_STATE;
    }
}
