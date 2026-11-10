package dev.questheld.host;

import java.util.Locale;

/** One sender reporting window. Rates are measured on the host. */
final class LiveStats {
    final long sampledAtMs;
    private final long windowMs, encoded, sent, videoBytes, queueDrops, lateDrops, oversizedDrops, audioDrops;
    private final int encoderBitrate, bitrateLimit, targetFps;

    LiveStats(long sampledAtMs, long windowMs, long encoded, long sent, long videoBytes,
              long queueDrops, long lateDrops, long oversizedDrops, long audioDrops,
              int encoderBitrate, int bitrateLimit, int targetFps) {
        this.sampledAtMs = sampledAtMs;
        this.windowMs = windowMs;
        this.encoded = encoded;
        this.sent = sent;
        this.videoBytes = videoBytes;
        this.queueDrops = queueDrops;
        this.lateDrops = lateDrops;
        this.oversizedDrops = oversizedDrops;
        this.audioDrops = audioDrops;
        this.encoderBitrate = encoderBitrate;
        this.bitrateLimit = bitrateLimit;
        this.targetFps = targetFps;
    }

    String summary() {
        return String.format(Locale.getDefault(),
                "Video sent  %.1f / %d FPS\nEncoder output  %.1f FPS\n"
                + "Video payload  %.1f Mbps\nEncoder target  %.1f / %d Mbps host limit\n\n"
                + "Recent drops\nVideo queue  %d  ·  Late  %d  ·  Oversized  %d\nAudio queue  %d\n\n"
                + "Rates are measured on this host. Client playback FPS and network overhead are separate.",
                sent * 1000.0 / windowMs, targetFps, encoded * 1000.0 / windowMs,
                videoBytes * 0.008 / windowMs, encoderBitrate / 1_000_000.0, bitrateLimit,
                queueDrops, lateDrops, oversizedDrops, audioDrops);
    }
}
