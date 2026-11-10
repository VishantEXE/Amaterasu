package dev.questheld.host;

/** Process-local session events. Controls react to media, never merely discovery. */
final class SessionState {
    enum Stage { LAUNCHED, SERVER_READY, CLIENT_DISCOVERED, PAIRED, NEGOTIATED,
        STREAMING, INTERRUPTED, DISCONNECTED, STOPPED, UNEXPECTED_TERMINATION }
    interface Listener { void changed(Stage stage); }
    private static Listener listener;
    private static long epoch;
    private static volatile long memoryPressureEpoch = -1;
    private static Stage stage = Stage.LAUNCHED;
    private static LiveStats stats;
    static synchronized void attach(Listener value) { listener=value; epoch++; stage=Stage.LAUNCHED; stats=null; }
    static synchronized void detach() { listener=null; epoch++; stage=Stage.STOPPED; stats=null; }
    static synchronized long epoch() { return epoch; }
    static synchronized void noteMemoryPressure() {
        if (listener != null) memoryPressureEpoch = epoch;
    }
    static boolean hasMemoryPressure(long owner) { return memoryPressureEpoch == owner; }
    static synchronized void emit(long owner, Stage next) { if(owner==epoch && listener!=null) emit(next); }
    static synchronized Stage current() { return stage; }
    static synchronized LiveStats stats() { return stats; }
    static synchronized void report(long owner, LiveStats sample) {
        if(owner==epoch && stage==Stage.STREAMING) stats=sample;
    }
    static synchronized void emit(Stage next) {
        // Discovery and old transport callbacks must not revive a terminal session.
        if (next.ordinal()<stage.ordinal()) return;
        if (stage==next) return;
        stage=next;
        if(stage!=Stage.STREAMING) stats=null;
        if(listener!=null) listener.changed(next);
    }
}
