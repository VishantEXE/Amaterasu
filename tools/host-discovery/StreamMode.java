package dev.questheld.host;

import java.io.IOException;

/** Validate the client mode before passing it to Android's encoder capability check. */
final class StreamMode {
    static void validate(int width, int height, int fps, int maxWidth, int maxHeight, int maxFps)
            throws IOException {
        if (width < 320 || height < 240 || (width & 1) != 0 || (height & 1) != 0
                || width > maxWidth || height > maxHeight || fps < 1 || fps > maxFps) {
            throw new IOException("Client requested " + width + "x" + height + "@" + fps
                    + "; host limit is " + maxWidth + "x" + maxHeight + "@" + maxFps
                    + " (even dimensions, at least 320x240 required)");
        }
    }
}
