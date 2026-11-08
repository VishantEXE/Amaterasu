package dev.questheld.host;

import android.content.res.AssetManager;
import android.media.projection.MediaProjection;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class FixtureMedia implements AutoCloseable {
    static final int VIDEO_PORT = 47998;
    static final int AUDIO_PORT = 48000;
    static final int CONTROL_PORT = 47999;
    private static final String TAG = "QuestHeldHost";
    private static final int DURATION_SECONDS = 18;
    private static final int VIDEO_HEADER = VideoBlock.HEADER;
    private static final int TAG_BYTES = 16;
    private static final int VIDEO_FEC_PERCENT = 20;
    private static final int MAX_DATA_SHARDS = 255 * 100 / (100 + VIDEO_FEC_PERCENT);
    private static final int MAX_UNPROTECTED_SHARDS = 1023;
    private static final int MAX_VIDEO_BLOCKS = 4;
    private static final AtomicInteger CONTROL_SEQUENCE = new AtomicInteger(new SecureRandom().nextInt());

    private final AssetManager assets;
    private final InetAddress peer;
    private final SecretKeySpec key;
    private final int keyId;
    private final DatagramSocket video;
    private final DatagramSocket audio;
    private final long control;
    private final byte[] videoPing;
    private final byte[] audioPing;
    private final int packetSize;
    private final int audioMs;
    private final MediaProjection projection;
    private final StreamSettings settings;
    private final StreamSettings.EncodingProfile profile;
    private final File diagnosticsDirectory;
    private final Runnable finished;
    private final long eventEpoch=SessionState.epoch();
    private volatile boolean stopped;
    private long videoNonce = new SecureRandom().nextLong();
    private int videoSequence;
    private ByteBuffer videoPlain;
    private ByteBuffer videoWire;
    private byte[] videoSealed;
    private Cipher videoCipher;
    private Cipher audioCipher;
    private final ByteBuffer videoIv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer audioIv = ByteBuffer.allocate(16);
    private final byte[] audioSealed = new byte[256];
    private final ByteBuffer audioWire = ByteBuffer.allocate(12 + audioSealed.length);
    private final byte[] audioShards = new byte[6 * audioSealed.length];
    private final ByteBuffer audioParityWire = ByteBuffer.allocate(24 + audioSealed.length);
    private volatile int liveAudioSent;

    static {
        System.loadLibrary("questheld_control");
    }

    FixtureMedia(AssetManager assets, InetAddress local, InetAddress peer, SecretKeySpec key,
                 int keyId, int connectData, String videoPing, String audioPing, int packetSize,
                 int audioMs, MediaProjection projection, StreamSettings settings,
                 StreamSettings.EncodingProfile profile, File diagnosticsDirectory, Runnable finished) throws IOException {
        this.assets = assets;
        this.peer = peer;
        this.key = key;
        this.keyId = keyId;
        this.videoPing = videoPing.getBytes(StandardCharsets.US_ASCII);
        this.audioPing = audioPing.getBytes(StandardCharsets.US_ASCII);
        this.packetSize = packetSize;
        this.audioMs = audioMs;
        this.projection = projection;
        this.settings = settings;
        this.profile = profile;
        this.diagnosticsDirectory = diagnosticsDirectory;
        this.finished = finished;
        video = new DatagramSocket(new InetSocketAddress(local, VIDEO_PORT));
        try {
            audio = new DatagramSocket(new InetSocketAddress(local, AUDIO_PORT));
        } catch (IOException e) {
            video.close();
            throw e;
        }
        control = openControl(local.getHostAddress(), peer.getHostAddress(), connectData);
        if (control == 0) {
            video.close();
            audio.close();
            throw new IOException("Could not open ENet control");
        }
    }

    void start() {
        new Thread(this::run, "questheld-fixture").start();
    }

    private void run() {
        int frames = 0;
        int audioPackets = 0;
        try {
            int plainSize = packetSize - 16 + VIDEO_HEADER;
            videoPlain = ByteBuffer.allocate(MAX_UNPROTECTED_SHARDS * plainSize);
            videoWire = ByteBuffer.allocate(32 + plainSize).order(ByteOrder.LITTLE_ENDIAN);
            videoSealed = new byte[plainSize + TAG_BYTES];
            videoCipher = Cipher.getInstance("AES/GCM/NoPadding");
            audioCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            video.setSoTimeout(2);
            audio.setSoTimeout(2);
            InetSocketAddress videoTarget = null;
            InetSocketAddress audioTarget = null;
            boolean startA = false;
            boolean startB = false;
            long deadline = SystemClock.elapsedRealtime() + 6000;
            while (!stopped && SystemClock.elapsedRealtime() < deadline) {
                byte[] message = pollControl(control, 1);
                if (message != null && message.length == 0) return;
                int type = controlType(message);
                startA |= type == 0x0302;
                startB |= type == 0x0307;
                if (videoTarget == null) videoTarget = receivePing(video, videoPing);
                if (audioTarget == null) audioTarget = receivePing(audio, audioPing);
                if (startA && startB && videoTarget != null && audioTarget != null) break;
            }
            if (stopped) return;
            if (!startA || !startB || videoTarget == null || audioTarget == null) {
                Log.w(TAG, "Fixture startup incomplete: startA=" + startA + " startB=" + startB
                        + " videoPing=" + (videoTarget != null) + " audioPing=" + (audioTarget != null));
                return;
            }
            Log.i(TAG, "Authenticated control and both UDP destinations established");
            if (projection != null) {
                runLive(videoTarget, audioTarget);
                return;
            }
            List<byte[]> pictures = records("video.bin", 60);
            List<byte[]> tone = records("audio-" + audioMs + ".bin", 1000 / audioMs);
            long start = System.nanoTime();
            long videoDue = start;
            long audioDue = start;
            long end = start + DURATION_SECONDS * 1_000_000_000L;
            while (!stopped && System.nanoTime() < end) {
                byte[] message = pollControl(control, 0);
                if (message != null && message.length == 0) return;
                long now = System.nanoTime();
                if (audioDue <= videoDue && now >= audioDue) {
                    sendAudio(audioTarget, tone.get(audioPackets % tone.size()), audioPackets);
                    audioPackets++;
                    audioDue = start + audioPackets * audioMs * 1_000_000L;
                } else if (now >= videoDue) {
                    int index = frames % pictures.size();
                    sendVideo(videoTarget, pictures.get(index), frames + 1, frames * 1500, index == 0);
                    frames++;
                    videoDue = start + frames * 1_000_000_000L / 60;
                } else {
                    LockSupport.parkNanos(Math.min(1_000_000L, Math.min(audioDue, videoDue) - now));
                }
            }
            if (!stopped) finishControl();
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            if (!stopped) { Log.w(TAG, "Media stream failed", e); SessionState.emit(eventEpoch, SessionState.Stage.INTERRUPTED); }
        } finally {
            if (projection == null) {
                Log.i(TAG, "Fixture sent videoFrames=" + frames + " audioPackets=" + audioPackets);
            }
            close();
            closeControl(control);
            SessionState.emit(eventEpoch, SessionState.Stage.DISCONNECTED);
            finished.run();
        }
    }

    private void runLive(InetSocketAddress videoTarget, InetSocketAddress audioTarget)
            throws IOException, GeneralSecurityException {
        int frames = 0;
        long firstPts = Long.MIN_VALUE;
        long lastPts = Long.MIN_VALUE;
        long lastReport = SystemClock.elapsedRealtime();
        int frameNumber = 0;
        int oversizedFrames = 0;
        int lateFrames = 0;
        int lateInWindow = 0;
        CongestionGovernor governor = new CongestionGovernor();
        boolean waitForKeyframe = true;
        try (SessionDiagnostics diagnostics = new SessionDiagnostics(diagnosticsDirectory,
                    profile + " fsr=" + settings.fsr);
             LiveCapture capture = new LiveCapture(projection, audioMs, assets, settings, profile, diagnostics,
                     MAX_VIDEO_BLOCKS * MAX_UNPROTECTED_SHARDS * (packetSize - 16) - 8)) {
            capture.start();
            Thread audioThread = new Thread(() -> sendLiveAudio(capture, audioTarget), "questheld-opus-send");
            audioThread.start();
            try {
                while (!stopped) {
                    byte[] message = pollControl(control, 0);
                    if (message != null && message.length == 0) break;
                    if (controlType(message) == 0x0302) capture.requestKeyframe();
                    LiveCapture.Frame frame = capture.pollVideo();
                    try {
                        if (frame != null && frame.ptsUs > lastPts && (!waitForKeyframe || frame.keyframe)) {
                            long ageUs = System.nanoTime() / 1000 - frame.ptsUs;
                            if (ageUs > 250_000 && ageUs < 30_000_000) {
                                lateFrames++;
                                diagnostics.frame("late_drop", frame.id, frame.ptsUs, frame.size, ageUs);
                                lateInWindow++;
                                frameNumber++;
                                waitForKeyframe = true;
                                capture.requestKeyframe();
                            } else if (!fitsVideoBlocks(frame.size)) {
                                oversizedFrames++;
                                frameNumber++;
                                waitForKeyframe = true;
                                Log.w(TAG, "Dropped oversized access unit, bytes=" + frame.size);
                                diagnostics.frame("oversize_drop", frame.id, frame.ptsUs, frame.size, 0);
                                capture.requestKeyframe();
                            } else {
                                if (frames == 0) firstPts = frame.ptsUs;
                                int ticks = (int) ((frame.ptsUs - firstPts) * 90 / 1000);
                                diagnostics.frame("send_start", frame.id, frame.ptsUs, frame.size, frameNumber + 1);
                                sendVideo(videoTarget, frame.annexB, frame.size, ++frameNumber, ticks, frame.keyframe);
                                diagnostics.frame("send_end", frame.id, frame.ptsUs, frame.size, frameNumber);
                                frames++;
                                if (frames == 1) SessionState.emit(eventEpoch, SessionState.Stage.STREAMING);
                                waitForKeyframe = false;
                                lastPts = frame.ptsUs;
                            }
                        }
                    } finally {
                        if (frame != null) capture.recycle(frame);
                    }
                    if (frame == null) LockSupport.parkNanos(1_000_000L);
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastReport >= 2000) {
                        Log.i(TAG, "Live sent videoFrames=" + frames + " audioPackets=" + liveAudioSent
                                + " audioQueueDrops=" + capture.audioDrops() + " oversizedFrames=" + (oversizedFrames + capture.oversizedFrames())
                                + " lateFrames=" + lateFrames + " videoQueueDrops=" + capture.videoDrops());
                        diagnostics.frame("queues", frames, 0, liveAudioSent, capture.videoDrops());
                        if (governor.shouldReduce(now, capture.videoDrops(),
                                oversizedFrames + capture.oversizedFrames(), lateInWindow)) {
                            capture.reduceBitrateForLatency();
                        }
                        lateInWindow = 0;
                        lastReport = now;
                    }
                }
            } finally {
                audioThread.interrupt();
                try {
                    audioThread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            Log.i(TAG, "Live ended videoFrames=" + frames + " audioPackets=" + liveAudioSent);
        }
    }

    private void sendLiveAudio(LiveCapture capture, InetSocketAddress target) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        long interval = audioMs * 1_000_000L;
        AudioPacer pacer = new AudioPacer(interval);
        boolean primed = false;
        try {
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                if (!primed) {
                    if (capture.audioQueued() < 2) {
                        LockSupport.parkNanos(500_000L);
                        continue;
                    }
                    primed = true;
                    pacer.reset(System.nanoTime());
                }
                long wait = pacer.waitNs(System.nanoTime());
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                    continue;
                }
                byte[] packet = capture.pollAudio();
                if (packet == null) {
                    primed = false;
                    LockSupport.parkNanos(500_000L);
                    continue;
                }
                sendAudio(target, packet, liveAudioSent);
                liveAudioSent++;
                long now = System.nanoTime();
                pacer.sent(now);
            }
        } catch (IOException | GeneralSecurityException e) {
            if (!stopped) Log.w(TAG, "Live audio sender failed", e);
            close();
        }
    }

    private List<byte[]> records(String name, int count) throws IOException {
        List<byte[]> data = new ArrayList<>(count);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(assets.open(name)))) {
            for (int i = 0; i < count; i++) {
                int length = input.readInt();
                if (length <= 0 || length > 512 * 1024) throw new EOFException("Invalid fixture record");
                byte[] record = new byte[length];
                input.readFully(record);
                data.add(record);
            }
            if (input.read() != -1) throw new IOException("Unexpected extra fixture records");
        }
        return data;
    }

    private InetSocketAddress receivePing(DatagramSocket socket, byte[] expected) throws IOException {
        DatagramPacket ping = new DatagramPacket(new byte[20], 20);
        try {
            socket.receive(ping);
        } catch (SocketTimeoutException ignored) {
            return null;
        }
        if (!peer.equals(ping.getAddress()) || ping.getLength() != 20) return null;
        byte[] value = new byte[16];
        System.arraycopy(ping.getData(), 0, value, 0, value.length);
        return MessageDigest.isEqual(expected, value)
                ? new InetSocketAddress(ping.getAddress(), ping.getPort()) : null;
    }

    private int controlType(byte[] message) {
        if (message == null || message.length < 28) return -1;
        ByteBuffer header = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getShort() != 1 || Short.toUnsignedInt(header.getShort()) != message.length - 4) return -1;
        byte[] iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(header.getInt()).array();
        iv[10] = 'C';
        iv[11] = 'C';
        byte[] sealed = new byte[message.length - 8];
        System.arraycopy(message, 24, sealed, 0, message.length - 24);
        System.arraycopy(message, 8, sealed, message.length - 24, TAG_BYTES);
        try {
            byte[] plain = gcm(Cipher.DECRYPT_MODE, iv).doFinal(sealed);
            ByteBuffer decoded = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
            int type = Short.toUnsignedInt(decoded.getShort());
            return Short.toUnsignedInt(decoded.getShort()) == decoded.remaining() ? type : -1;
        } catch (GeneralSecurityException ignored) {
            return -1;
        }
    }

    private void finishControl() throws GeneralSecurityException {
        int sequence = CONTROL_SEQUENCE.incrementAndGet();
        byte[] iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(sequence).array();
        iv[10] = 'H';
        iv[11] = 'C';
        ByteBuffer reason = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        reason.putShort((short) 0x0109).putShort((short) 4);
        reason.order(ByteOrder.BIG_ENDIAN).putInt(0x80030023);
        byte[] sealed = gcm(Cipher.ENCRYPT_MODE, iv).doFinal(reason.array());
        ByteBuffer packet = ByteBuffer.allocate(8 + sealed.length).order(ByteOrder.LITTLE_ENDIAN);
        packet.putShort((short) 1).putShort((short) (4 + sealed.length)).putInt(sequence);
        packet.put(sealed, reason.capacity(), TAG_BYTES).put(sealed, 0, reason.capacity());
        if (!sendControl(control, packet.array())) return;
        Log.i(TAG, "Sent fixture termination reason");
        long deadline = SystemClock.elapsedRealtime() + 300;
        while (!stopped && SystemClock.elapsedRealtime() < deadline) {
            byte[] reply = pollControl(control, 20);
            if (reply != null && reply.length == 0) return;
        }
    }

    private void sendVideo(InetSocketAddress target, byte[] picture, int frame, int pts, boolean idr)
            throws GeneralSecurityException, IOException {
        sendVideo(target, picture, picture.length, frame, pts, idr);
    }

    private void sendVideo(InetSocketAddress target, byte[] picture, int pictureLength, int frame, int pts, boolean idr)
            throws GeneralSecurityException, IOException {
        int payloadSize = packetSize - 16;
        int shardSize = VIDEO_HEADER + payloadSize;
        int sizeWithHeader = 8 + pictureLength;
        int count = (sizeWithHeader + payloadSize - 1) / payloadSize;
        boolean protectedFrame = count <= MAX_VIDEO_BLOCKS * MAX_DATA_SHARDS;
        int maxData = protectedFrame ? MAX_DATA_SHARDS : MAX_UNPROTECTED_SHARDS;
        int percentage = protectedFrame ? VIDEO_FEC_PERCENT : 0;
        int blocks = (count + maxData - 1) / maxData;
        if (pictureLength <= 0 || pictureLength > picture.length || !fitsVideoBlocks(pictureLength)) {
            throw new IOException("Invalid video frame size");
        }
        int sourceOffset = 0;
        DatagramPacket datagram = new DatagramPacket(videoWire.array(), videoWire.capacity(), target);
        long nextBatch = System.nanoTime();
        for (int block = 0; block < blocks; block++) {
            int data = Math.min(maxData, count - block * maxData);
            int parity = (data * percentage + 99) / 100;
            int blockFlags = (block << 4) | ((blocks - 1) << 6);
            byte[] shards = videoPlain.array();
            sourceOffset = VideoBlock.write(videoPlain, payloadSize, picture, pictureLength, sourceOffset,
                    videoSequence, frame, idr, block, blocks, data);
            if (parity != 0 && !encodeParity(shards, shardSize, data, parity, false)) {
                throw new IOException("Video parity failed");
            }
            for (int index = 0; index < data + parity; index++) {
                if (stopped) return;
                int offset = index * shardSize;
                videoPlain.position(offset);
                videoPlain.order(ByteOrder.BIG_ENDIAN);
                videoPlain.put((byte) 0x90).put((byte) 0).putShort((short) videoSequence++).putInt(pts);
                videoPlain.order(ByteOrder.LITTLE_ENDIAN);
                videoPlain.putInt(offset + 20, frame);
                videoPlain.put(offset + 27, (byte) blockFlags);
                videoPlain.putInt(offset + 28, (data << 22) | (index << 12) | (percentage << 4));
                byte[] iv = videoIv.putLong(0, videoNonce++).array();
                iv[11] = 'V';
                videoCipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
                videoCipher.doFinal(shards, offset, shardSize, videoSealed);
                videoWire.clear();
                videoWire.put(iv).putInt(frame);
                videoWire.put(videoSealed, shardSize, TAG_BYTES).put(videoSealed, 0, shardSize);
                video.send(datagram);
                if ((index & 7) == 7 && index + 1 < data + parity) {
                    nextBatch = Math.max(nextBatch + 300_000L, System.nanoTime());
                    LockSupport.parkNanos(nextBatch - System.nanoTime());
                }
            }
        }
    }

    private boolean fitsVideoBlocks(int payloadBytes) {
        return payloadBytes <= MAX_VIDEO_BLOCKS * MAX_UNPROTECTED_SHARDS * (packetSize - 16) - 8;
    }

    private void sendAudio(InetSocketAddress target, byte[] opus, int sequence)
            throws GeneralSecurityException, IOException {
        byte[] iv = audioIv.putInt(0, keyId + (sequence & 0xffff)).array();
        audioCipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        int length = audioCipher.doFinal(opus, 0, opus.length, audioSealed);
        ByteBuffer packet = audioWire;
        packet.clear();
        packet.put((byte) 0x80).put((byte) 97).putShort((short) sequence)
                .putInt(sequence * audioMs).putInt(0).put(audioSealed, 0, length);
        audio.send(new DatagramPacket(packet.array(), packet.position(), target));
        int slot = sequence & 3;
        System.arraycopy(audioSealed, 0, audioShards, slot * length, length);
        if (slot != 3) return;
        if (!encodeParity(audioShards, length, 4, 2, true)) throw new IOException("Audio parity failed");
        for (int parity = 0; parity < 2; parity++) {
            audioParityWire.clear();
            audioParityWire.put((byte) 0x80).put((byte) 127).putShort((short) (sequence + parity + 1));
            audioParityWire.putInt(0).putInt(0).put((byte) parity).put((byte) 97);
            audioParityWire.putShort((short) (sequence - 3)).putInt((sequence - 3) * audioMs).putInt(0);
            audioParityWire.put(audioShards, (4 + parity) * length, length);
            audio.send(new DatagramPacket(audioParityWire.array(), audioParityWire.position(), target));
        }
    }

    private Cipher gcm(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        return cipher;
    }

    @Override public void close() {
        stopped = true;
        video.close();
        audio.close();
    }

    private static native long openControl(String local, String peer, int token);
    private static native byte[] pollControl(long handle, int timeoutMs);
    private static native boolean sendControl(long handle, byte[] message);
    private static native boolean encodeParity(byte[] data, int size, int originals, int parity, boolean audio);
    private static native void closeControl(long handle);
}
