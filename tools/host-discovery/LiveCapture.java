package dev.questheld.host;

import android.content.res.AssetManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class LiveCapture implements AutoCloseable {
    private static final String TAG = "QuestHeldHost";
    private final MediaProjection projection;
    private final int audioMs;
    private final AssetManager assets;
    private final StreamSettings settings;
    private final StreamSettings.EncodingProfile profile;
    private final ArrayBlockingQueue<byte[]> audio = new ArrayBlockingQueue<>(12);
    private final AtomicLong audioDrops = new AtomicLong();
    private final ArrayBlockingQueue<Frame> videoFrames = new ArrayBlockingQueue<>(4);
    private final AtomicLong videoQueueDrops = new AtomicLong();
    private final AtomicLong oversizedFrames = new AtomicLong();
    private final AtomicLong queuedVideoBytes = new AtomicLong();
    private final VideoBufferPool buffers = new VideoBufferPool();
    private static final int QUEUED_VIDEO_BUDGET = 4 * 1024 * 1024;
    private final int maxFrameBytes;
    private final AtomicBoolean keyframeRequested = new AtomicBoolean();
    private final AtomicBoolean bitrateReductionRequested = new AtomicBoolean();
    private final CountDownLatch initialized = new CountDownLatch(1);
    private final SessionDiagnostics diagnostics;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private MediaCodec encoder;
    private Surface input;
    private VirtualDisplay display;
    private FrameUpscaler upscaler;
    private volatile AudioRecord recorder;
    private volatile boolean stopped;
    private Thread audioThread;
    private Thread videoThread;
    private volatile IOException videoFailure;
    private byte[] config = new byte[0];
    private long lastKeyframeRequest;
    private volatile int activeBitrate;
    private volatile long encodedFrames;
    int activeBitrate() { return activeBitrate; }
    long encodedFrames() { return encodedFrames; }
    private boolean recovering;
    private final long memoryEpoch = SessionState.epoch();

    static final class Frame {
        final byte[] annexB;
        final int size;
        final long ptsUs;
        final boolean keyframe;
        final long id;

        Frame(byte[] annexB, int size, long ptsUs, boolean keyframe, long id) {
            this.annexB = annexB;
            this.size = size;
            this.ptsUs = ptsUs;
            this.keyframe = keyframe;
            this.id = id;
        }
    }

    LiveCapture(MediaProjection projection, int audioMs, AssetManager assets, StreamSettings settings,
                StreamSettings.EncodingProfile profile, SessionDiagnostics diagnostics, int maxFrameBytes) {
        this.projection = projection;
        this.audioMs = audioMs;
        this.assets = assets;
        this.settings = settings;
        this.profile = profile;
        this.diagnostics = diagnostics;
        this.maxFrameBytes = maxFrameBytes;
        activeBitrate = profile.bitrate;
    }

    void start() throws IOException {
        videoThread = new Thread(this::captureVideo, "questheld-video-capture");
        videoThread.start();
        try {
            if (!initialized.await(10, TimeUnit.SECONDS)) throw new IOException("Video capture startup timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Video capture startup interrupted", e);
        }
        if (videoFailure != null) throw videoFailure;
    }

    private void configureVideo() throws IOException {
        MediaFormat format = profile.format();
        format.removeKey(MediaFormat.KEY_MAX_FPS_TO_ENCODER);
        encoder = MediaCodec.createByCodecName(profile.encoder);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        input = encoder.createInputSurface();
        encoder.start();
        try {
            upscaler = new FrameUpscaler(assets, input, settings.captureWidth(), settings.captureHeight(),
                    profile.width, profile.height, settings.fsr, profile.fps, diagnostics);
        } catch (IOException | RuntimeException e) {
            throw new IOException("GPU capture initialization failed", e);
        }
        int width = settings.captureWidth();
        int height = settings.captureHeight();
        Surface source = upscaler.captureSurface();
        display = projection.createVirtualDisplay("QuestHeld live", width, height, 320,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, source, null, null);
        if (display == null) throw new IOException("Virtual display unavailable");
        audioThread = new Thread(this::captureAudio, "questheld-playback-audio");
        audioThread.start();
        Log.i(TAG, "Live MediaProjection started: " + profile + " FSR=" + settings.fsr
                + " capture=" + width + "x" + height);
    }

    Frame pollVideo() throws IOException {
        if (videoFailure != null) throw videoFailure;
        Frame frame = videoFrames.poll();
        if (frame != null) queuedVideoBytes.addAndGet(-frame.size);
        return frame;
    }

    private void captureVideo() {
        long id = 0;
        boolean memoryTrimmed = false;
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        try {
            configureVideo();
            initialized.countDown();
            while (!stopped) {
                if (!memoryTrimmed && SessionState.hasMemoryPressure(memoryEpoch)) {
                    buffers.trim();
                    memoryTrimmed = true;
                    Log.i(TAG, "Reduced the retained video buffer budget after Android memory pressure");
                }
                if (keyframeRequested.getAndSet(false)) updateKeyframe();
                if (bitrateReductionRequested.getAndSet(false)) updateBitrate();
                Frame frame = dequeueVideo(++id);
                if (frame == null) {
                    id--;
                    if (upscaler != null) upscaler.renderPending();
                    java.util.concurrent.locks.LockSupport.parkNanos(500_000L);
                    continue;
                }
                encodedFrames++;
                diagnostics.frame("encoded", frame.id, frame.ptsUs, frame.size, frame.keyframe ? 1 : 0);
                if (recovering && !frame.keyframe) {
                    videoQueueDrops.incrementAndGet();
                    recycle(frame);
                    continue;
                }
                long queued = queuedVideoBytes.addAndGet(frame.size);
                if ((queued > QUEUED_VIDEO_BUDGET && queued != frame.size) || !videoFrames.offer(frame)) {
                    queuedVideoBytes.addAndGet(-frame.size);
                    Frame old;
                    while ((old = videoFrames.poll()) != null) {
                        queuedVideoBytes.addAndGet(-old.size);
                        videoQueueDrops.incrementAndGet();
                        recycle(old);
                    }
                    // A fresh IDR is already a valid recovery point. Keep it after clearing
                    // old frames rather than discarding it and requesting another large IDR.
                    if (frame.keyframe) {
                        queuedVideoBytes.addAndGet(frame.size);
                        if (videoFrames.offer(frame)) {
                            recovering = false;
                            diagnostics.frame("queue_reset", frame.id, frame.ptsUs, frame.size, videoQueueDrops.get());
                            continue;
                        }
                        queuedVideoBytes.addAndGet(-frame.size);
                    }
                    videoQueueDrops.incrementAndGet();
                    recovering = true;
                    keyframeRequested.set(true);
                    diagnostics.frame("queue_reset", frame.id, frame.ptsUs, frame.size, videoQueueDrops.get());
                    recycle(frame);
                } else {
                    recovering = false;
                }
            }
        } catch (IOException | RuntimeException e) {
            if (!stopped) videoFailure = new IOException("Live video capture failed", e);
        } finally {
            initialized.countDown();
            release("virtual display", () -> { if (display != null) display.release(); });
            release("FSR/EGL", () -> { if (upscaler != null) upscaler.close(); });
            release("encoder", () -> { if (encoder != null) encoder.release(); });
            release("encoder surface", () -> { if (input != null) input.release(); });
            Frame pending;
            while ((pending = videoFrames.poll()) != null) recycle(pending);
            buffers.clear();
            Log.i(TAG, "Video resources released on owner thread");
        }
    }

    private Frame dequeueVideo(long id) throws IOException {
        int index = encoder.dequeueOutputBuffer(info, 0);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat format = encoder.getOutputFormat();
            setCodecConfig(concat(copy(format.getByteBuffer("csd-0")), copy(format.getByteBuffer("csd-1"))));
            return null;
        }
        if (index < 0) return null;
        try {
            if (info.size <= 0) return null;
            ByteBuffer data = encoder.getOutputBuffer(index);
            data.position(info.offset);
            data.limit(info.offset + info.size);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                setCodecConfig(copy(data));
                return null;
            }
            boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            boolean av1 = StreamSettings.AV1_MIME.equals(profile.mime);
            int insertion = av1 ? Av1Obu.headerInsertion(data, keyframe)
                    : keyframe && annexB(config) ? info.offset : -1;
            if (av1 && insertion >= 0 && config.length == 0) {
                throw new IOException("AV1 keyframe is missing its sequence header");
            }
            int prefix = insertion >= 0 ? config.length : 0;
            if (info.size > maxFrameBytes - prefix) {
                oversizedFrames.incrementAndGet();
                diagnostics.frame("oversize_drop", id, info.presentationTimeUs, info.size, maxFrameBytes);
                Log.w(TAG, "Rejected oversized encoder output before allocation, bytes=" + info.size);
                recovering = true;
                keyframeRequested.set(true);
                return null;
            }
            if (!av1 && (info.size <= 4 || data.get(info.offset) != 0 || data.get(info.offset + 1) != 0
                    || (data.get(info.offset + 2) != 1
                    && (data.get(info.offset + 2) != 0 || data.get(info.offset + 3) != 1)))) {
                throw new IOException("Unexpected MediaCodec access unit framing");
            }
            int size = prefix + info.size;
            byte[] payload = buffers.acquire(size);
            int leading = prefix == 0 ? 0 : insertion - info.offset;
            if (leading != 0) data.get(payload, 0, leading);
            if (prefix != 0) System.arraycopy(config, 0, payload, leading, prefix);
            data.get(payload, leading + prefix, info.size - leading);
            return new Frame(payload, size, info.presentationTimeUs, keyframe, id);
        } finally {
            encoder.releaseOutputBuffer(index, false);
        }
    }

    private void setCodecConfig(byte[] bytes) throws IOException {
        if (StreamSettings.AV1_MIME.equals(profile.mime)) {
            byte[] sequence = Av1Obu.sequenceHeader(bytes);
            if (sequence.length != 0) config = sequence;
        } else {
            config = bytes;
        }
    }

    void requestKeyframe() {
        keyframeRequested.set(true);
    }

    private void updateKeyframe() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastKeyframeRequest < 650) return;
        lastKeyframeRequest = now;
        Bundle update = new Bundle();
        update.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        encoder.setParameters(update);
    }

    byte[] pollAudio() {
        return audio.poll();
    }

    long audioDrops() {
        return audioDrops.get();
    }

    void reduceBitrateForLatency() {
        bitrateReductionRequested.set(true);
    }

    long videoDrops() {
        return videoQueueDrops.get();
    }

    long oversizedFrames() { return oversizedFrames.get(); }

    void recycle(Frame frame) { buffers.release(frame.annexB); }

    int audioQueued() {
        return audio.size();
    }

    private void updateBitrate() {
        int lower = Math.max(Math.min(10_000_000, profile.bitrate), activeBitrate * 4 / 5);
        if (lower >= activeBitrate) return;
        Bundle update = new Bundle();
        update.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, lower);
        try {
            encoder.setParameters(update);
            activeBitrate = lower;
            Log.i(TAG, "Reduced encoder bitrate to " + lower + " after video congestion");
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Encoder rejected the bitrate adjustment", e);
        }
    }

    private void captureAudio() {
        int samples = 48 * audioMs;
        short[] pcm = new short[samples * 2];
        long opus = openOpus();
        if (opus == 0) return;
        try {
            try {
                AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
                AudioFormat format = new AudioFormat.Builder().setSampleRate(48000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
                int minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT);
                recorder = new AudioRecord.Builder().setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(minimum, pcm.length * 8))
                        .setAudioPlaybackCaptureConfig(capture).build();
                if (!stopped) recorder.startRecording();
                Log.i(TAG, "Android playback capture opened at 48 kHz stereo");
            } catch (RuntimeException e) {
                Log.i(TAG, "Playback capture unavailable. Sending silence for the negotiated audio channel");
                releaseRecorder();
            }
            long nextSilence = SystemClock.elapsedRealtime();
            long packets = 0;
            long peak = 0;
            while (!stopped) {
                AudioRecord source = recorder;
                if (source == null) {
                    long wait = nextSilence - SystemClock.elapsedRealtime();
                    if (wait > 0) SystemClock.sleep(wait);
                    nextSilence = SystemClock.elapsedRealtime() + audioMs;
                    Arrays.fill(pcm, (short) 0);
                } else {
                    int offset = 0;
                    while (!stopped && offset < pcm.length) {
                        int read = source.read(pcm, offset, pcm.length - offset, AudioRecord.READ_BLOCKING);
                        if (read <= 0) break;
                        offset += read;
                    }
                    if (stopped) break;
                    if (offset != pcm.length) {
                        releaseRecorder();
                        continue;
                    }
                    for (short sample : pcm) peak = Math.max(peak, Math.abs((int) sample));
                }
                byte[] packet = encodeOpus(opus, pcm, samples);
                if (packet == null) break;
                if (!audio.offer(packet)) {
                    if (audio.poll() != null) audioDrops.incrementAndGet();
                    audio.offer(packet);
                }
                if (++packets % (2000 / audioMs) == 0) {
                    Log.i(TAG, "Playback capture packets=" + packets + " PCM peak=" + peak);
                    peak = 0;
                }
            }
        } finally {
            releaseRecorder();
            closeOpus(opus);
        }
    }

    private synchronized void releaseRecorder() {
        if (recorder == null) return;
        recorder.release();
        recorder = null;
    }

    @Override public void close() {
        stopped = true;
        synchronized (this) {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (IllegalStateException ignored) {
                }
            }
        }
        if (audioThread != null) {
            try {
                audioThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (videoThread != null) {
            try {
                videoThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (videoThread != null && videoThread.isAlive()) Log.w(TAG, "Video cleanup pending: GPU/codec owner exceeded 2 seconds");
        if (audioThread != null && audioThread.isAlive()) Log.w(TAG, "Audio cleanup pending after stop");
        audio.clear();
    }

    private static void release(String name, Runnable action) {
        try { action.run(); } catch (RuntimeException e) { Log.w(TAG, "Could not release " + name, e); }
    }

    private static boolean annexB(byte[] data) {
        return data.length > 4 && data[0] == 0 && data[1] == 0
                && (data[2] == 1 || (data[2] == 0 && data[3] == 1));
    }

    private static byte[] copy(ByteBuffer data) {
        if (data == null) return new byte[0];
        ByteBuffer view = data.duplicate();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static native long openOpus();
    private static native byte[] encodeOpus(long handle, short[] pcm, int samplesPerChannel);
    private static native void closeOpus(long handle);
}
