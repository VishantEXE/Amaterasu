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
    private final Context context;
    private final File file;
    private boolean ready;
    private long lastPressureAt = -1000;

    MemoryMonitor(Context source) {
        context=source.getApplicationContext();
        File directory=context.getExternalFilesDir("diagnostics");
        file=directory==null?null:new File(directory,"latest-service-memory.csv");
        if(directory==null || (!directory.isDirectory() && !directory.mkdirs())) return;
        try(PrintWriter out=new PrintWriter(file,"UTF-8")) {
            out.println("elapsed_ms,pss_kib,native_pss_kib,graphics_kib,java_used_bytes,native_allocated_bytes,system_available_bytes,low_memory,thermal_status,state,trim_level");
        } catch(IOException e) { Log.w("QuestHeldHost","Memory log unavailable",e); return; }
        ready=true;
        worker.scheduleWithFixedDelay(()->snapshot(0),0,10,TimeUnit.SECONDS);
    }

    // Called by the service on its main thread. Keep the extra work bounded.
    void recordPressure(int level) {
        long now=SystemClock.elapsedRealtime();
        if(!ready || now-lastPressureAt<1000) return;
        lastPressureAt=now;
        worker.execute(()->snapshot(level));
    }

    private void snapshot(int trimLevel) {
        try {
            if(file.length()>1024*1024) return;
            Debug.MemoryInfo own=new Debug.MemoryInfo(); Debug.getMemoryInfo(own);
            ActivityManager.MemoryInfo system=new ActivityManager.MemoryInfo();
            context.getSystemService(ActivityManager.class).getMemoryInfo(system);
            Runtime runtime=Runtime.getRuntime();
            String row=SystemClock.elapsedRealtime()+","+own.getTotalPss()+","+own.nativePss+","+own.getMemoryStat("summary.graphics")
                +","+(runtime.totalMemory()-runtime.freeMemory())+","+Debug.getNativeHeapAllocatedSize()+","+system.availMem
                +","+system.lowMemory+","+context.getSystemService(PowerManager.class).getCurrentThermalStatus()+","+LiveHostService.sessionStatus
                +","+trimLevel;
            try(FileOutputStream output=new FileOutputStream(file,true)) { output.write((row+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        } catch(IOException | RuntimeException e) { Log.w("QuestHeldHost","Memory snapshot unavailable",e); }
    }

    @Override public void close() { ready=false; worker.shutdownNow(); }
}
