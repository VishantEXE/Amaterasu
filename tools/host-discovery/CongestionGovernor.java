package dev.questheld.host;

/** React to sender backlog as well as late frames, with a cooldown between bitrate cuts. */
final class CongestionGovernor {
    private long lastQueueDrops;
    private long lastOversized;
    private long nextReductionMs;

    boolean shouldReduce(long nowMs, long queueDrops, long oversized, int lateInWindow) {
        long dropped = Math.max(0, queueDrops - lastQueueDrops);
        long large = Math.max(0, oversized - lastOversized);
        lastQueueDrops = queueDrops;
        lastOversized = oversized;
        if (nowMs < nextReductionMs || (dropped < 4 && large == 0 && lateInWindow < 2)) return false;
        nextReductionMs = nowMs + 5000;
        return true;
    }
}
