package dev.questheld.host;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** AV1 low-overhead OBUs from MediaCodec. Moonlight receives complete temporal units. */
final class Av1Obu {
    private static final int SEQUENCE_HEADER = 1;
    private static final int TEMPORAL_DELIMITER = 2;
    private static final int FRAME_HEADER = 3;
    private static final int FRAME = 6;

    static byte[] sequenceHeader(byte[] config) throws IOException {
        // Some encoders expose an AV1CodecConfigurationRecord before the config OBUs.
        int start = config.length >= 4 && config[0] == (byte) 0x81 ? 4 : 0;
        ByteBuffer data = ByteBuffer.wrap(config);
        for (int offset = start; offset < data.limit();) {
            int next = endOfObu(data, offset);
            if (type(data, offset) == SEQUENCE_HEADER) return Arrays.copyOfRange(config, offset, next);
            offset = next;
        }
        return new byte[0];
    }

    /** Return the position to insert a missing sequence header, or -1 for no insertion. */
    static int headerInsertion(ByteBuffer data, boolean keyframe) throws IOException {
        boolean hasSequence = false;
        boolean hasPicture = false;
        int insertAt = data.position();
        for (int offset = data.position(); offset < data.limit();) {
            int type = type(data, offset);
            int next = endOfObu(data, offset);
            if (offset == data.position() && type == TEMPORAL_DELIMITER) insertAt = next;
            if (!hasPicture && type == SEQUENCE_HEADER) hasSequence = true;
            if (type == FRAME_HEADER || type == FRAME) hasPicture = true;
            offset = next;
        }
        if (!hasPicture) throw new IOException("AV1 encoder output contains no picture OBU");
        return keyframe && !hasSequence ? insertAt : -1;
    }

    private static int type(ByteBuffer data, int offset) {
        return (data.get(offset) >>> 3) & 15;
    }

    private static int endOfObu(ByteBuffer data, int offset) throws IOException {
        int header = data.get(offset++) & 255;
        if ((header & 0x83) != 2) throw new IOException("Expected a sized AV1 OBU");
        if ((header & 4) != 0) offset++;
        long size = 0;
        for (int shift = 0; shift < 56 && offset < data.limit(); shift += 7) {
            int next = data.get(offset++) & 255;
            size |= (long) (next & 127) << shift;
            if ((next & 128) == 0) {
                if (size > data.limit() - offset) break;
                return offset + (int) size;
            }
        }
        throw new IOException("Truncated AV1 OBU");
    }
}
