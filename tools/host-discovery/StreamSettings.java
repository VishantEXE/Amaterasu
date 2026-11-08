package dev.questheld.host;

import android.content.Intent;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import java.io.IOException;

final class StreamSettings {
    static final int FPS = 60;
    static final int SCALE_720_TO_1080 = 0;
    static final int SCALE_1080_TO_4K = 1;
    static final int SCALE_1080_TO_1440 = 2;
    final boolean fsr;
    final int scale;
    final boolean hevc;
    final int bitrateMbps;
    final int fps;

    StreamSettings(boolean fsr, int scale, boolean hevc, int bitrateMbps) {
        this(fsr, scale, hevc, bitrateMbps, FPS);
    }

    StreamSettings(boolean fsr, int scale, boolean hevc, int bitrateMbps, int fps) {
        this.fsr = fsr;
        this.scale = scale == SCALE_1080_TO_4K || scale == SCALE_1080_TO_1440 ? scale : SCALE_720_TO_1080;
        this.hevc = hevc;
        this.bitrateMbps = Math.max(10, Math.min(200, bitrateMbps));
        this.fps = fps == 120 ? 120 : FPS;
    }

    static StreamSettings fixture() {
        return new StreamSettings(false, SCALE_720_TO_1080, false, 20);
    }

    static StreamSettings fromIntent(Intent intent) {
        return new StreamSettings(intent.getBooleanExtra("fsr", false), intent.getIntExtra("fsrScale", 0),
                intent.getBooleanExtra("hevc", false), intent.getIntExtra("bitrateMbps", 20),
                intent.getIntExtra("fps", FPS));
    }

    Intent putInto(Intent intent) {
        return intent.putExtra("fsr", fsr).putExtra("fsrScale", scale).putExtra("hevc", hevc)
                .putExtra("bitrateMbps", bitrateMbps).putExtra("fps", fps);
    }

    int outputWidth() {
        if (fsr && scale == SCALE_1080_TO_1440) return 2560;
        return fsr && scale == SCALE_1080_TO_4K ? 3840 : 1920;
    }

    int outputHeight() {
        return outputWidth() == 3840 ? 2160 : outputWidth() == 2560 ? 1440 : 1080;
    }

    int captureWidth() {
        if (!fsr) return outputWidth();
        return scale == SCALE_1080_TO_4K || scale == SCALE_1080_TO_1440 ? 1920 : 1280;
    }

    int captureHeight() {
        if (!fsr) return outputHeight();
        return scale == SCALE_1080_TO_4K || scale == SCALE_1080_TO_1440 ? 1080 : 720;
    }

    EncodingProfile profile(int codecFormat, int clientBitrateKbps) throws IOException {
        if (codecFormat != 0 && (codecFormat != 1 || !hevc)) {
            throw new IOException("Codec is not enabled in host settings");
        }
        String mime = codecFormat == 1 ? "video/hevc" : "video/avc";
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!codec.isEncoder() || !codec.isHardwareAccelerated() || codec.isAlias()) continue;
            for (String type : codec.getSupportedTypes()) {
                if (!mime.equalsIgnoreCase(type)) continue;
                MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(type);
                boolean surface = false;
                for (int color : caps.colorFormats) surface |= color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (!surface || video == null || !video.areSizeAndRateSupported(outputWidth(), outputHeight(), fps)) {
                    continue;
                }
                int requested = bitrateMbps * 1_000_000;
                int client = clientBitrateKbps > 0 ? (int) Math.min(200_000_000L, clientBitrateKbps * 1000L) : requested;
                int actual = video.getBitrateRange().clamp(Math.min(requested, client));
                int rateControl = caps.getEncoderCapabilities().isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                        ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                        : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
                if (!caps.getEncoderCapabilities().isBitrateModeSupported(rateControl)) continue;
                if (actual != requested) {
                    Log.i("QuestHeldHost", "Video bitrate adjusted from host target " + requested
                            + " to " + actual + " by client preference or encoder limits");
                }
                return new EncodingProfile(codec.getName(), mime, outputWidth(), outputHeight(), actual,
                        rateControl, fps);
            }
        }
        throw new IOException("No hardware encoder supports " + mime + " at "
                + outputWidth() + "x" + outputHeight() + " " + fps + " FPS");
    }

    boolean supportsHevc() {
        if (!hevc) return false;
        try {
            profile(1, 0);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static final class EncodingProfile {
        final String encoder;
        final String mime;
        final int width;
        final int height;
        final int bitrate;
        final int rateControl;
        final int fps;

        EncodingProfile(String encoder, String mime, int width, int height, int bitrate, int rateControl, int fps) {
            this.encoder = encoder;
            this.mime = mime;
            this.width = width;
            this.height = height;
            this.bitrate = bitrate;
            this.rateControl = rateControl;
            this.fps = fps;
        }

        MediaFormat format() {
            MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, rateControl);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            return format;
        }

        @Override public String toString() {
            return mime + " " + width + "x" + height + "@" + fps + " " + bitrate / 1_000_000 + " Mbps (" + encoder + ")";
        }
    }
}
