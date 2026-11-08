package dev.questheld.host;
import android.graphics.Canvas;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Looper;
import android.view.Surface;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FsrEncodeProbe {
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        new Thread(() -> {
            try {
                int[] widths=args.length>1?new int[]{Integer.parseInt(args[1])}:new int[]{1920,2560,3840};
                int seconds=args.length>2?Integer.parseInt(args[2]):3;
                for (int width : widths) run(args[0], width, seconds);
                System.out.println("GPU capture bridge probe passed");
                System.exit(0);
            } catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }
        }, "gpu-probe").start();
        Looper.loop();
    }
    private static void run(String assetPath, int width, int seconds) throws Exception {
        int height = width == 3840 ? 2160 : width == 2560 ? 1440 : 1080;
        AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
        AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, assetPath);
        MediaFormat format = MediaFormat.createVideoFormat("video/hevc", width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec encoder = MediaCodec.createByCodecName("c2.qti.hevc.encoder");
        Surface output = null;
        Thread producer = null;
        AtomicBoolean producing = new AtomicBoolean(true);
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            output = encoder.createInputSurface();
            encoder.start();
            try (SessionDiagnostics log = new SessionDiagnostics(new File("/data/local/tmp/questheld-gpu-probe"), "GPU probe");
                 FrameUpscaler bridge = new FrameUpscaler(assets, output, width==1920?1280:1920, width==1920?720:1080, width, height, true, 60, log)) {
                producer = new Thread(() -> {
                    try {
                        long due = System.nanoTime();
                        int frame = 0;
                        while (producing.get()) {
                            if (System.nanoTime() < due) {
                                java.util.concurrent.locks.LockSupport.parkNanos(200000);
                                continue;
                            }
                            Canvas canvas = bridge.captureSurface().lockCanvas(null);
                            try { canvas.drawColor((frame++ & 1) == 0 ? Color.BLUE : Color.GREEN); }
                            finally { bridge.captureSurface().unlockCanvasAndPost(canvas); }
                            due += 16666667L;
                        }
                    } catch (Throwable e) {
                        if (producing.get()) e.printStackTrace();
                    }
                }, "pattern-producer");
                producer.start();
                long end = System.nanoTime() + seconds * 1000000000L;
                int frames = 0;
                long first = 0;
                long last = 0;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (System.nanoTime() < end) {
                    int index = encoder.dequeueOutputBuffer(info, 0);
                    if (index >= 0) {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (frames++ == 0) first = info.presentationTimeUs;
                            last = info.presentationTimeUs;
                        }
                        encoder.releaseOutputBuffer(index, false);
                    }
                    if(index<0) bridge.renderPending();
                    java.util.concurrent.locks.LockSupport.parkNanos(200000);
                }
                producing.set(false);
                producer.join(1000);
                if (frames < seconds * 45 || last <= first) throw new AssertionError("Insufficient sustained encoder output");
                System.out.println("FSR HEVC output=" + width + "x" + height + " encoded frames=" + frames + " spanUs=" + (last-first));
            }
        } finally {
            producing.set(false);
            if (producer != null) producer.join(1000);
            if (output != null) output.release();
            encoder.release();
        }
    }
}
