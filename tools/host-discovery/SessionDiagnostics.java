package dev.questheld.host;

import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded asynchronous timing log. Disk work never runs on capture or send threads. */
final class SessionDiagnostics implements AutoCloseable {
    private final ArrayBlockingQueue<String> rows = new ArrayBlockingQueue<>(1024);
    private final AtomicLong droppedRows = new AtomicLong();
    private final Thread writer;
    private volatile boolean closed;

    SessionDiagnostics(File directory, String profile) throws IOException {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Diagnostic directory unavailable");
        }
        File[] previous = directory.listFiles((dir, name) -> name.startsWith("session-") && name.endsWith(".csv"));
        if (previous != null && previous.length >= 5) {
            Arrays.sort(previous, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i <= previous.length - 5; i++) previous[i].delete();
        }
        File file = new File(directory, "session-" + System.currentTimeMillis() + ".csv");
        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8), 32768);
        output.write("# " + profile + "\n");
        output.write("event,frame_id,source_pts_us,host_monotonic_ns,bytes,detail\n");
        output.flush();
        writer = new Thread(() -> write(output), "questheld-diagnostics");
        writer.start();
        Log.i("QuestHeldHost", "Timing diagnostics: " + file.getAbsolutePath());
    }

    void frame(String event, long id, long ptsUs, int bytes, long detail) {
        String row = event + "," + id + "," + ptsUs + "," + System.nanoTime() + "," + bytes + "," + detail + "\n";
        if (!closed && !rows.offer(row)) droppedRows.incrementAndGet();
    }

    private void write(BufferedWriter output) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        long flushAt = SystemClock.elapsedRealtime() + 1000;
        long characters = 0;
        try (BufferedWriter sink = output) {
            while (!closed || !rows.isEmpty()) {
                String row = rows.poll(250, TimeUnit.MILLISECONDS);
                if (row != null) {
                    if (characters < 16 * 1024 * 1024) {
                        sink.write(row);
                        characters += row.length();
                    } else {
                        droppedRows.incrementAndGet();
                    }
                }
                if (SystemClock.elapsedRealtime() >= flushAt) {
                    sink.flush();
                    flushAt = SystemClock.elapsedRealtime() + 1000;
                }
            }
            sink.write("# diagnostic_rows_dropped=" + droppedRows.get() + "\n");
        } catch (IOException e) {
            Log.w("QuestHeldHost", "Diagnostic writer failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void close() {
        closed = true;
        try {
            writer.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
