package dev.questheld.host;

import java.util.ArrayList;

/** Bounded reuse of encoded access units. Ownership passes from capture to sender. */
final class VideoBufferPool {
    static final int MAX_RETAINED_BYTES = 2 * 1024 * 1024;
    static final int MAX_POOLED_BUFFER = 1024 * 1024;
    private static final int PRESSURE_RETAINED_BYTES = 512 * 1024;
    private final ArrayList<byte[]> free = new ArrayList<>(6);
    private int retained;
    private int retainedLimit = MAX_RETAINED_BYTES;

    synchronized byte[] acquire(int size) {
        if (size <= 0) throw new IllegalArgumentException("Invalid access unit size");
        int best = -1;
        for (int i = 0; i < free.size(); i++) {
            if (free.get(i).length >= size && (best < 0 || free.get(i).length < free.get(best).length)) best = i;
        }
        if (best >= 0) {
            byte[] buffer = free.remove(best);
            retained -= buffer.length;
            return buffer;
        }
        // Pool ordinary frames; exceptional keyframes must not permanently grow the pool.
        int capacity = size <= MAX_POOLED_BUFFER ? (size + 16383) & ~16383 : size;
        return new byte[capacity];
    }

    synchronized void release(byte[] buffer) {
        if (buffer.length > MAX_POOLED_BUFFER || buffer.length > retainedLimit) return;
        // Content changes can replace tiny menu frames with larger gameplay frames.
        // Keep recently returned buffers instead of letting six small ones pin the cache.
        while (!free.isEmpty() && (free.size() >= 6 || retained + buffer.length > retainedLimit)) {
            retained -= free.remove(0).length;
        }
        free.add(buffer);
        retained += buffer.length;
    }

    synchronized void trim() {
        retainedLimit = PRESSURE_RETAINED_BYTES;
        while (retained > retainedLimit) retained -= free.remove(0).length;
    }

    synchronized void clear() { free.clear(); retained = 0; }
    synchronized int retainedBytes() { return retained; }
}
