package dev.questheld.host;

import android.content.res.AssetManager;
import android.media.projection.MediaProjection;
import android.os.SystemClock;
import android.util.Log;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class StreamSetup implements AutoCloseable {
    static final int PORT = 48010;
    static final int SCREEN_ID = 1;
    private static final int ENCRYPTED = 0x80000000;
    private static final int TAG_BYTES = 16;
    private static final int MAX_MESSAGE = 16384;
    private static final int ENCRYPTION_FLAGS = 7;
    private static final String TAG = "QuestHeldHost";
    private static final AtomicInteger RESPONSE_SEQUENCE = new AtomicInteger(new SecureRandom().nextInt());
    private final ServerSocket listener;
    private final String url;
    private final Inet4Address address;
    private final AssetManager assets;
    private final MediaProjection projection;
    private final StreamSettings settings;
    private final File diagnosticsDirectory;
    private final Runnable liveFinished;
    private boolean liveUsed;
    private final long eventEpoch=SessionState.epoch();
    private Session session;

    private static final class Session {
        final InetAddress peer;
        final SecretKeySpec key;
        final int keyId;
        final int connectData = new SecureRandom().nextInt();
        final String videoPing = randomPing();
        final String audioPing = randomPing();
        final String token = UUID.randomUUID().toString();
        final long expires = SystemClock.elapsedRealtime() + 30_000;
        long sequence;
        int step;
        int packetSize;
        int audioMs;
        FixtureMedia media;
        StreamSettings.EncodingProfile profile;

        Session(InetAddress peer, byte[] key, int keyId) {
            this.peer = peer;
            this.key = new SecretKeySpec(key, "AES");
            this.keyId = keyId;
        }
    }

    StreamSetup(Inet4Address address, AssetManager assets, MediaProjection projection, StreamSettings settings,
                File diagnosticsDirectory, Runnable liveFinished)
            throws IOException {
        this.address = address;
        this.assets = assets;
        this.projection = projection;
        this.settings = settings;
        this.diagnosticsDirectory = diagnosticsDirectory;
        this.liveFinished = liveFinished;
        url = "rtspenc://" + address.getHostAddress() + ":" + PORT;
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(address, PORT));
        } catch (IOException e) {
            listener.close();
            throw e;
        }
    }

    void start() {
        new Thread(this::serve, "questheld-rtsp").start();
    }

    synchronized String launch(InetAddress peer, Map<String, String> query, boolean resume) {
        String result = resume ? "resume" : "gamesession";
        byte[] key = parseKey(query.get("rikey"));
        if (!"1".equals(query.get("appid")) || !"1".equals(query.get("corever")) || key == null) {
            return xml("<" + result + ">0</" + result + ">");
        }
        if (active() != null || liveUsed) {
            Arrays.fill(key, (byte) 0);
            return xml("<" + result + ">0</" + result + ">");
        }
        int keyId;
        try {
            keyId = Integer.parseInt(query.get("rikeyid"));
        } catch (NumberFormatException e) {
            Arrays.fill(key, (byte) 0);
            return xml("<" + result + ">0</" + result + ">");
        }
        session = new Session(peer, key, keyId);
        Arrays.fill(key, (byte) 0);
        Log.i(TAG, "Authenticated Screen launch accepted");
        return xml("<" + result + ">1</" + result + "><sessionUrl0>" + url + "</sessionUrl0>");
    }

    synchronized boolean busy() {
        return active() != null;
    }

    synchronized void cancel() {
        if (session != null && session.media != null) session.media.close();
        session = null;
    }

    private Session active() {
        if (session != null && session.media == null && SystemClock.elapsedRealtime() >= session.expires) {
            session = null;
        }
        return session;
    }

    private void serve() {
        while (!listener.isClosed()) {
            try (Socket socket = listener.accept()) {
                socket.setSoTimeout(1500);
                handle(socket);
            } catch (IOException | GeneralSecurityException e) {
                if (!listener.isClosed()) Log.d(TAG, "RTSP request rejected: " + e.getClass().getSimpleName());
            }
        }
    }

    private void handle(Socket socket) throws IOException, GeneralSecurityException {
        Session candidate;
        synchronized (this) {
            candidate = active();
            if (candidate == null || !candidate.peer.equals(socket.getInetAddress())) return;
        }
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int header = input.readInt();
        int length = header & ~ENCRYPTED;
        int sequence = input.readInt();
        if ((header & ENCRYPTED) == 0 || length <= 0 || length > MAX_MESSAGE) return;
        byte[] encrypted = new byte[length + TAG_BYTES];
        input.readFully(encrypted, length, TAG_BYTES);
        input.readFully(encrypted, 0, length);
        byte[] plain = cipher(Cipher.DECRYPT_MODE, candidate, sequence, 'C').doFinal(encrypted);
        String response;
        synchronized (this) {
            long unsignedSequence = Integer.toUnsignedLong(sequence);
            if (active() != candidate || unsignedSequence <= candidate.sequence) return;
            candidate.sequence = unsignedSequence;
            response = respond(candidate, new String(plain, StandardCharsets.US_ASCII));
        }
        int responseSequence = RESPONSE_SEQUENCE.incrementAndGet();
        byte[] reply = response.getBytes(StandardCharsets.US_ASCII);
        byte[] sealed = cipher(Cipher.ENCRYPT_MODE, candidate, responseSequence, 'H').doFinal(reply);
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.writeInt(ENCRYPTED | reply.length);
        output.writeInt(responseSequence);
        output.write(sealed, reply.length, TAG_BYTES);
        output.write(sealed, 0, reply.length);
        output.flush();
    }

    private String respond(Session current, String request) {
        String[] message = request.split("\r\n\r\n", 2);
        String[] headers = message[0].split("\r\n");
        String[] command = headers[0].split(" ", 3);
        String cseq = header(headers, "CSeq");
        if (command.length != 3 || !"RTSP/1.0".equals(command[2]) || !cseq.matches("[0-9]{1,10}")) {
            return response("400 Bad Request", "0", "", "");
        }
        String method = command[0];
        String target = command[1];
        if (current.step == 0 && "OPTIONS".equals(method)) {
            current.step++;
            return response("200 OK", cseq, "Public: OPTIONS, DESCRIBE, SETUP, ANNOUNCE, PLAY\r\n", "");
        }
        if (current.step == 1 && "DESCRIBE".equals(method)) {
            current.step++;
            boolean av1 = projection != null && settings.supports(StreamSettings.AV1);
            String sdp = "v=0\r\ns=QuestHeld\r\nt=0 0\r\n"
                    + "a=x-ss-general.featureFlags:0\r\n"
                    + "a=x-ss-general.encryptionSupported:" + ENCRYPTION_FLAGS + "\r\n"
                    + "a=x-ss-general.encryptionRequested:" + ENCRYPTION_FLAGS + "\r\n"
                    + "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 opus/48000/2\r\n"
                    + "m=video 0 RTP/AVP 96" + (av1 ? " 98" : "") + "\r\na=rtpmap:96 H264/90000\r\n";
            if (projection != null && settings.supports(StreamSettings.HEVC)) {
                // Moonlight detects the HEVC capability from this GameStream SDP marker.
                sdp += "sprop-parameter-sets=AAAAAU\r\n";
            }
            if (av1) {
                sdp += "a=rtpmap:98 AV1/90000\r\n";
            }
            return response("200 OK", cseq, "Content-Type: application/sdp\r\n", sdp);
        }
        boolean tokenMatches = current.token.equals(header(headers, "Session"));
        if ("SETUP".equals(method)) {
            int port = 0;
            String extension = "";
            if (current.step == 2 && "streamid=audio/0/0".equals(target)) {
                port = FixtureMedia.AUDIO_PORT;
                extension = "X-SS-Ping-Payload: " + current.audioPing + "\r\n";
            }
            if (current.step == 3 && tokenMatches && "streamid=video/0/0".equals(target)) {
                port = FixtureMedia.VIDEO_PORT;
                extension = "X-SS-Ping-Payload: " + current.videoPing + "\r\n";
            }
            if (current.step == 4 && tokenMatches && target.startsWith("streamid=control")) {
                port = FixtureMedia.CONTROL_PORT;
                extension = "X-SS-Connect-Data: " + Integer.toUnsignedString(current.connectData) + "\r\n";
            }
            if (port != 0) {
                current.step++;
                return response("200 OK", cseq, "Session: " + current.token + "\r\n"
                        + "Transport: unicast;server_port=" + port + "\r\n" + extension, "");
            }
        }
        if (current.step == 5 && tokenMatches && "ANNOUNCE".equals(method)) {
            if (message.length != 2 || !requiresEncryption(message[1])) {
                session = null;
                Log.i(TAG, "Stream rejected because required encryption was not negotiated");
                return response("403 Forbidden", cseq, "", "");
            }
            String sdp = message[1];
            current.packetSize = attribute(sdp, "x-nv-video[0].packetSize");
            current.audioMs = attribute(sdp, "x-nv-aqos.packetDuration");
            int width = attribute(sdp, "x-nv-video[0].clientViewportWd");
            int height = attribute(sdp, "x-nv-video[0].clientViewportHt");
            int fps = attribute(sdp, "x-nv-video[0].maxFPS");
            int channels = attribute(sdp, "x-nv-audio.surround.numChannels");
            int format = attribute(sdp, "x-nv-vqos[0].bitStreamFormat");
            int expectedWidth = projection == null ? 1920 : settings.outputWidth();
            int expectedHeight = projection == null ? 1080 : settings.outputHeight();
            int expectedFps = projection == null ? 60 : settings.fps;
            if (current.packetSize < 256 || current.packetSize > 1392
                    || (current.audioMs != 5 && current.audioMs != 10) || channels != 2
                    || width != expectedWidth || height != expectedHeight || fps != expectedFps
                    || (projection == null && format != 0)) {
                session = null;
                Log.i(TAG, "Host requires " + expectedWidth + "x" + expectedHeight
                        + " " + expectedFps + " FPS stereo. Client requested "
                        + width + "x" + height + " fps=" + fps + " channels=" + channels
                        + " format=" + format + " packetSize=" + current.packetSize
                        + " audioMs=" + current.audioMs);
                return response("461 Unsupported Transport", cseq, "", "");
            }
            if (projection != null) {
                int clientBitrate = attribute(sdp, "x-ml-video.configuredBitrateKbps");
                if (clientBitrate <= 0) clientBitrate = attribute(sdp, "x-nv-vqos[0].bw.maximumBitrateKbps");
                try {
                    current.profile = settings.profile(format, clientBitrate);
                    Log.i(TAG, "Negotiated " + current.profile);
                } catch (IOException e) {
                    session = null;
                    Log.w(TAG, e.getMessage());
                    return response("461 Unsupported Transport", cseq, "", "");
                }
            }
            current.step++;
            Log.i(TAG, "Stock client negotiated encrypted RTSP, control, video and audio");
            return response("200 OK", cseq, "", "");
        }
        if (current.step == 6 && tokenMatches && "PLAY".equals(method)) {
            try {
                current.media = new FixtureMedia(assets, address, current.peer, current.key,
                        current.keyId, current.connectData, current.videoPing, current.audioPing,
                        current.packetSize, current.audioMs, projection, settings, current.profile,
                        diagnosticsDirectory, () -> finish(current));
                liveUsed = projection != null;
                current.step++;
                SessionState.emit(eventEpoch, SessionState.Stage.NEGOTIATED);
                current.media.start();
                Log.i(TAG, projection == null ? "Fixture PLAY accepted" : "Live PLAY accepted");
                return response("200 OK", cseq, "", "");
            } catch (IOException | LinkageError e) {
                session = null;
                Log.w(TAG, "Could not start fixture media", e);
                return response("503 Media Sender Unavailable", cseq, "", "");
            }
        }
        return response("455 Method Not Valid in This State", cseq, "", "");
    }

    private static String header(String[] lines, String name) {
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && name.equalsIgnoreCase(lines[i].substring(0, colon))) {
                return lines[i].substring(colon + 1).trim();
            }
        }
        return "";
    }

    private static boolean requiresEncryption(String sdp) {
        int flags = attribute(sdp, "x-ss-general.encryptionEnabled");
        return flags >= 0 && (flags & ENCRYPTION_FLAGS) == ENCRYPTION_FLAGS;
    }

    private static int attribute(String sdp, String name) {
        String prefix = "a=" + name + ":";
        for (String line : sdp.split("\r?\n")) {
            if (!line.startsWith(prefix)) continue;
            try {
                return Integer.parseInt(line.substring(prefix.length()).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static String randomPing() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private synchronized void finish(Session ended) {
        if (session == ended) session = null;
        if (liveFinished != null) liveFinished.run();
    }

    private static Cipher cipher(int mode, Session session, int sequence, char origin)
            throws GeneralSecurityException {
        byte[] iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(sequence).array();
        iv[10] = (byte) origin;
        iv[11] = 'R';
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, session.key, new GCMParameterSpec(128, iv));
        return cipher;
    }

    private static byte[] parseKey(String hex) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{32}")) return null;
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String response(String status, String cseq, String headers, String body) {
        return "RTSP/1.0 " + status + "\r\nCSeq: " + cseq + "\r\n" + headers
                + "Content-Length: " + body.length() + "\r\n\r\n" + body;
    }

    static String xml(String fields) {
        return "<root status_code=\"200\">" + fields + "</root>";
    }

    @Override public void close() throws IOException {
        listener.close();
        cancel();
    }
}
