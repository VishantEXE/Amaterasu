package dev.questheld.host;

/** Selects new source frames against a monotonic clock without inventing frames. */
final class FramePacer {
    private final long periodNs;
    private long nextNs;

    FramePacer(int fps) {
        if (fps != 60 && fps != 120) throw new IllegalArgumentException("Unsupported frame rate");
        periodNs = 1_000_000_000L / fps;
    }

    boolean due(long nowNs) {
        return nextNs == 0 || nowNs >= nextNs;
    }

    void submitted(long nowNs) {
        if (nextNs == 0 || nowNs - nextNs >= periodNs) {
            nextNs = nowNs + periodNs;
        } else {
            nextNs += periodNs;
        }
    }
}
