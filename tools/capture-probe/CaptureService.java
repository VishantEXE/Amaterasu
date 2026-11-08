package dev.questheld.captureprobe;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.io.File;

public final class CaptureService extends Service {
    private static final String TAG = "QuestHeldProbe";
    private static final String CHANNEL = "capture";
    private static final int DURATION_MS = 8000;
    private volatile boolean stopped;

    private static boolean measureDraws;
    private static int drawCount;
    private static int slowDrawIntervals;
    private static long firstDrawNs;
    private static long lastDrawNs;

    static synchronized void noteDraw(long timeNs) {
        if (!measureDraws) return;
        if (drawCount == 0) {
            firstDrawNs = timeNs;
        } else if (timeNs - lastDrawNs > 12_000_000) {
            slowDrawIntervals++;
        }
        lastDrawNs = timeNs;
        drawCount++;
    }

    private static synchronized void beginDrawMeasurement() {
        drawCount = 0;
        slowDrawIntervals = 0;
        firstDrawNs = 0;
        lastDrawNs = 0;
        measureDraws = true;
    }

    private static synchronized void finishDrawMeasurement() {
        if (!measureDraws) return;
        measureDraws = false;
        Log.i(TAG, "Source draws=" + drawCount + " spanNs=" + (lastDrawNs - firstDrawNs)
                + " intervalsOver12ms=" + slowDrawIntervals);
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Capture probe", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("QuestHeld capture probe")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        new Thread(() -> capture(intent), "capture-probe").start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        stopped = true;
        super.onDestroy();
    }

    private void capture(Intent intent) {
        MediaProjection projection = null;
        VirtualDisplay display = null;
        MediaCodec encoder = null;
        Surface input = null;
        MediaMuxer muxer = null;
        File partial = new File(getFilesDir(), "capture.partial");
        File result = new File(getFilesDir(), "capture.mp4");
        result.delete();
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(Activity.RESULT_OK,
                    intent.getParcelableExtra("grant", Intent.class));
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    stopped = true;
                }
            }, new Handler(Looper.getMainLooper()));
            boolean avc = intent.getBooleanExtra("avc", false);
            int fps = intent.getIntExtra("fps", 120);
            MediaFormat format = MediaFormat.createVideoFormat(avc ? "video/avc" : "video/hevc", 1920, 1080);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            boolean uncapped = intent.getBooleanExtra("uncapped", false);
            if (!uncapped) format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            encoder = MediaCodec.createByCodecName(avc ? "c2.qti.avc.encoder" : "c2.qti.hevc.encoder");
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = encoder.createInputSurface();
            encoder.start();
            muxer = new MediaMuxer(partial.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            display = projection.createVirtualDisplay("QuestHeld capture probe", 1920, 1080,
                    getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null);
            if (display == null) throw new IllegalStateException("Virtual display unavailable");
            Log.i(TAG, "Started " + encoder.getName() + " at requested fps=" + fps
                    + " cap=" + (uncapped ? "off" : fps) + " virtual refresh=" + display.getDisplay().getRefreshRate());
            beginDrawMeasurement();
            int frames = drain(encoder, muxer);
            muxer.stop();
            if (!partial.renameTo(result)) throw new IllegalStateException("Could not publish capture");
            Log.i(TAG, "Complete: " + frames + " encoded samples");
        } catch (Exception e) {
            Log.e(TAG, "Capture failed", e);
        } finally {
            finishDrawMeasurement();
            if (display != null) display.release();
            if (projection != null) projection.stop();
            if (encoder != null) encoder.release();
            if (input != null) input.release();
            if (muxer != null) muxer.release();
            partial.delete();
            stopSelf();
        }
    }

    private int drain(MediaCodec encoder, MediaMuxer muxer) {
        long end = SystemClock.elapsedRealtime() + DURATION_MS;
        int track = -1;
        int frames = 0;
        boolean sentEos = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (SystemClock.elapsedRealtime() < end + 2000) {
            if (!sentEos && (stopped || SystemClock.elapsedRealtime() >= end)) {
                finishDrawMeasurement();
                encoder.signalEndOfInputStream();
                sentEos = true;
            }
            int index = encoder.dequeueOutputBuffer(info, 10_000);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
            } else if (index >= 0) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    muxer.writeSampleData(track, encoder.getOutputBuffer(index), info);
                    frames++;
                }
                encoder.releaseOutputBuffer(index, false);
                if (eos) return frames;
            }
        }
        throw new IllegalStateException("Encoder did not finish draining");
    }

}
