package dev.questheld.host;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.util.concurrent.*;

/** Low-frequency public-API observations, never on render/audio/transport threads. */
final class MemoryMonitor implements AutoCloseable {
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    MemoryMonitor(Context source) {
        Context context=source.getApplicationContext();
        File directory=context.getExternalFilesDir("diagnostics");
        if(directory==null || (!directory.isDirectory() && !directory.mkdirs())) return;
        File file=new File(directory,"latest-service-memory.csv");
        try(PrintWriter out=new PrintWriter(file,"UTF-8")) {
            out.println("elapsed_ms,pss_kib,native_pss_kib,graphics_kib,java_used_bytes,native_allocated_bytes,system_available_bytes,low_memory,thermal_status,state");
        } catch(IOException e) { Log.w("QuestHeldHost","Memory log unavailable",e); return; }
        worker.scheduleWithFixedDelay(()->{
            try {
                if(file.length()>1024*1024) return;
                Debug.MemoryInfo own=new Debug.MemoryInfo(); Debug.getMemoryInfo(own);
                ActivityManager.MemoryInfo system=new ActivityManager.MemoryInfo();
                context.getSystemService(ActivityManager.class).getMemoryInfo(system);
                Runtime runtime=Runtime.getRuntime();
                String row=SystemClock.elapsedRealtime()+","+own.getTotalPss()+","+own.nativePss+","+own.getMemoryStat("summary.graphics")
                    +","+(runtime.totalMemory()-runtime.freeMemory())+","+Debug.getNativeHeapAllocatedSize()+","+system.availMem
                    +","+system.lowMemory+","+context.getSystemService(PowerManager.class).getCurrentThermalStatus()+","+LiveHostService.sessionStatus;
                try(FileOutputStream output=new FileOutputStream(file,true)) { output.write((row+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            } catch(IOException | RuntimeException e) { Log.w("QuestHeldHost","Memory snapshot unavailable",e); }
        },0,10,TimeUnit.SECONDS);
    }
    @Override public void close() { worker.shutdownNow(); }
}
