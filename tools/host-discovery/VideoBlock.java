package dev.questheld.host;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Build GameStream data shards without transmitting unused capacity in a pooled buffer. */
final class VideoBlock {
    static final int HEADER = 32;

    static int write(ByteBuffer target, int payloadSize, byte[] picture, int length, int sourceOffset,
                     int sequence, int frame, boolean idr, int block, int blocks, int data) {
        if (length <= 0 || length > picture.length || sourceOffset < 0 || sourceOffset > length) {
            throw new IllegalArgumentException("Invalid access unit bounds");
        }
        int shardSize = HEADER + payloadSize;
        int blockFlags = (block << 4) | ((blocks - 1) << 6);
        Arrays.fill(target.array(), 0, data * shardSize, (byte) 0);
        target.order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < data; index++) {
            int offset = index * shardSize;
            target.position(offset + 16);
            target.putInt((sequence + index) << 8).putInt(frame);
            int flags = 1 | (index == 0 ? 4 : 0) | (index + 1 == data ? 2 : 0);
            target.put((byte) flags).put((byte) 0).put((byte) 0x10).put((byte) blockFlags);
            target.position(offset + HEADER);
            if (block == 0 && index == 0) {
                int last = (8 + length - 1) % payloadSize + 1;
                target.put((byte) 1).putShort((short) 0).put((byte) (idr ? 2 : 1))
                        .putShort((short) last).putShort((short) 0);
            }
            int bytes = Math.min(length - sourceOffset, offset + shardSize - target.position());
            target.put(picture, sourceOffset, bytes);
            sourceOffset += bytes;
        }
        return sourceOffset;
    }
}
