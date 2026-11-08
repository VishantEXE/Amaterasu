package dev.questheld.host;

/** Keep the audio clock's phase through short scheduling delays; cap catch-up at one packet. */
final class AudioPacer {
    private final long interval;
    private long due;

    AudioPacer(long interval) { this.interval = interval; }
    void reset(long now) { due = now; }
    long waitNs(long now) { return due - now; }
    void sent(long now) {
        due += interval;
        if (now - due >= interval) due = now;
    }
}
