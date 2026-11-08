package dev.questheld.host;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.*;
import java.util.List;

final class ExitDiagnostics {
    static void record(Context source) {
        if(LiveHostService.activePairing!=null) return;
        final Context context=source.getApplicationContext();
        new Thread(()->{
            if(Build.VERSION.SDK_INT<30) return;
            File directory=context.getExternalFilesDir("diagnostics");
            if(directory==null || (!directory.isDirectory() && !directory.mkdirs())) return;
            boolean interrupted=context.getSharedPreferences("session-state",0).getBoolean("open",false);
            try(PrintWriter out=new PrintWriter(new File(directory,"historical-exits.txt"),"UTF-8")) {
                out.println("Prior service did not finish cleanup: "+interrupted);
                List<ApplicationExitInfo> exits=context.getSystemService(ActivityManager.class)
                    .getHistoricalProcessExitReasons(context.getPackageName(),0,10);
                for(ApplicationExitInfo exit:exits) out.println("timestamp="+exit.getTimestamp()+" pid="+exit.getPid()
                    +" reason="+exit.getReason()+" status="+exit.getStatus()+" importance="+exit.getImportance()
                    +" pssKiB="+exit.getPss()+" rssKiB="+exit.getRss()+" description="+exit.getDescription());
                if(interrupted) Log.w("QuestHeldHost","Previous streaming service ended without cleanup; historical exits saved");
            } catch(IOException | RuntimeException e) { Log.w("QuestHeldHost","Historical exits unavailable",e); }
        },"amaterasu-exit-diagnostics").start();
    }
}
