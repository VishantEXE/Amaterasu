package dev.questheld.host;

import android.content.res.AssetManager;
import android.media.projection.MediaProjection;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

final class DiscoveryServer implements AutoCloseable {
    static final int HTTP_PORT = 47989;
    private static final int HTTPS_PORT = 47984;
    private static final int MAX_REQUEST_BYTES = 8192;
    private final ServerSocket listener;
    private final SSLServerSocket tls;
    private final StreamSetup stream;
    private final Pairing pairing;
    private final long eventEpoch=SessionState.epoch();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore workers = new Semaphore(8);
    private final String serverInfo;

    DiscoveryServer(Inet4Address address, String name, String id, HostIdentity identity, Pairing pairing,
                    AssetManager assets, MediaProjection projection, StreamSettings settings,
                    File diagnosticsDirectory, Runnable finished)
            throws IOException, GeneralSecurityException {
        this.pairing = pairing;
        serverInfo = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<root status_code=\"200\">"
                + "<hostname>" + escapeXml(name) + "</hostname>"
                + "<appversion>7.1.431.-1</appversion>"
                + "<uniqueid>" + id + "</uniqueid>"
                + "<HttpsPort>47984</HttpsPort><ExternalPort>47989</ExternalPort>"
                + "<mac>00:00:00:00:00:00</mac><LocalIP>" + address.getHostAddress() + "</LocalIP>"
                + "<MaxLumaPixelsHEVC>" + (settings.supportsHevc() ? 1869449984 : 0) + "</MaxLumaPixelsHEVC>"
                + "<GfeVersion>3.23.0.0</GfeVersion>"
                + "<ServerCodecModeSupport>" + (settings.supportsHevc() ? 257 : 1) + "</ServerCodecModeSupport>";
        tls = (SSLServerSocket) identity.tls(pairing).getServerSocketFactory().createServerSocket();
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(address, HTTP_PORT));
            tls.setReuseAddress(true);
            tls.setNeedClientAuth(true);
            tls.setEnabledProtocols(new String[]{"TLSv1.2"});
            tls.setEnabledCipherSuites(new String[]{"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"});
            tls.bind(new InetSocketAddress(address, HTTPS_PORT));
            stream = new StreamSetup(address, assets, projection, settings, diagnosticsDirectory, finished);
        } catch (IOException | RuntimeException e) {
            listener.close();
            tls.close();
            throw e;
        }
    }

    void start() {
        stream.start();
        new Thread(() -> serve(listener), "questheld-http").start();
        new Thread(() -> serve(tls), "questheld-https").start();
    }

    private void serve(ServerSocket endpoint) {
        while (!endpoint.isClosed()) {
            Socket peer;
            try {
                peer = endpoint.accept();
            } catch (IOException e) {
                if (!endpoint.isClosed()) Log.e("QuestHeldHost", "Accept failed", e);
                return;
            }
            if (!workers.tryAcquire()) {
                closeSocket(peer);
                continue;
            }
            connections.add(peer);
            if (endpoint.isClosed()) closeSocket(peer);
            new Thread(() -> handle(peer), "questheld-request").start();
        }
    }

    private void handle(Socket peer) {
        try (Socket connection = peer) {
            respond(connection);
        } catch (javax.net.ssl.SSLException e) {
            Log.w("QuestHeldHost", "TLS connection failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (IOException ignored) {
            // A slow or disconnected peer only affects its own request.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            connections.remove(peer);
            workers.release();
        }
    }

    private void respond(Socket peer) throws IOException, InterruptedException {
        X509Certificate certificate = null;
        if (peer instanceof SSLSocket) {
            SSLSocket secured = (SSLSocket) peer;
            secured.setSoTimeout(3000);
            secured.startHandshake();
            certificate = (X509Certificate) secured.getSession().getPeerCertificates()[0];
        }
        String request = readRequest(peer);
        String[] parts = request.split(" ", 3);
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            write(peer, "400 Bad Request", "");
            return;
        }
        String[] target = parts[1].split("\\?", 2);
        if (!"GET".equals(parts[0])) {
            write(peer, "404 Not Found", "");
            return;
        }
        try {
            Map<String, String> query = target.length == 2 ? parseQuery(target[1]) : new HashMap<>();
            boolean trusted = pairing.isTrusted(certificate);
            switch (target[0]) {
                case "/serverinfo":
                    SessionState.emit(eventEpoch, trusted ? SessionState.Stage.PAIRED : SessionState.Stage.CLIENT_DISCOVERED);
                    if (certificate != null && !trusted) break;
                    boolean busy = trusted && stream.busy();
                    write(peer, "200 OK", serverInfo + "<PairStatus>" + (trusted ? 1 : 0) + "</PairStatus>"
                            + "<currentgame>" + (busy ? StreamSetup.SCREEN_ID : 0) + "</currentgame>"
                            + "<state>SUNSHINE_SERVER_" + (busy ? "BUSY" : "FREE") + "</state></root>");
                    return;
                case "/pair":
                    if (certificate == null) {
                        write(peer, "200 OK", pairing.http(peer.getInetAddress(), query));
                        return;
                    }
                    if ("pairchallenge".equals(query.get("phrase"))) {
                        write(peer, "200 OK", pairing.confirm(peer.getInetAddress(), query.get("uniqueid"), certificate));
                        return;
                    }
                    break;
                case "/unpair":
                    if (certificate != null) break;
                    pairing.cancel(peer.getInetAddress(), query.get("uniqueid"));
                    write(peer, "200 OK", "<root status_code=\"200\"><paired>0</paired></root>");
                    return;
                case "/applist":
                    if (!trusted) break;
                    write(peer, "200 OK", StreamSetup.xml("<App><AppTitle>Screen</AppTitle><ID>"
                            + StreamSetup.SCREEN_ID + "</ID><IsHdrSupported>0</IsHdrSupported></App>"));
                    return;
                case "/launch":
                case "/resume":
                    if (!trusted) break;
                    write(peer, "200 OK", stream.launch(peer.getInetAddress(), query, "/resume".equals(target[0])));
                    return;
                case "/cancel":
                    if (!trusted) break;
                    stream.cancel();
                    write(peer, "200 OK", StreamSetup.xml("<cancel>1</cancel>"));
                    return;
                default:
                    break;
            }
            write(peer, "404 Not Found", "");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            Log.w("QuestHeldHost", "Pairing request rejected: " + e.getClass().getSimpleName());
            write(peer, "200 OK", "<root status_code=\"200\"><paired>0</paired></root>");
        }
    }

    private static Map<String, String> parseQuery(String raw) throws IOException {
        Map<String, String> query = new HashMap<>();
        for (String field : raw.split("&")) {
            String[] pair = field.split("=", 2);
            if (pair.length == 2) {
                String key = URLDecoder.decode(pair[0], "UTF-8");
                String value = URLDecoder.decode(pair[1], "UTF-8");
                if (query.put(key, value) != null) throw new IllegalArgumentException("Repeated query field");
            }
        }
        return query;
    }

    private String readRequest(Socket peer) throws IOException {
        InputStream input = new BufferedInputStream(peer.getInputStream());
        ByteArrayOutputStream firstLine = new ByteArrayOutputStream();
        long deadline = SystemClock.elapsedRealtime() + 1500;
        boolean readingFirstLine = true;
        boolean emptyLine = true;
        for (int i = 0; i < MAX_REQUEST_BYTES; i++) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) throw new SocketTimeoutException();
            peer.setSoTimeout((int) remaining);
            int next = input.read();
            if (next == -1) throw new IOException("Incomplete HTTP headers");
            if (next == '\r') continue;
            if (next == '\n') {
                if (emptyLine) return firstLine.toString("US-ASCII");
                emptyLine = true;
                readingFirstLine = false;
            } else {
                emptyLine = false;
                if (readingFirstLine) firstLine.write(next);
            }
        }
        throw new IOException("HTTP headers exceeded the request limit");
    }

    private static void write(Socket peer, String status, String xml) throws IOException {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\nConnection: close\r\n"
                + "Content-Type: application/xml; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n";
        OutputStream output = peer.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override public void close() {
        pairing.close();
        closeSocket(listener);
        closeSocket(tls);
        try {
            stream.close();
        } catch (IOException e) {
            Log.w("QuestHeldHost", "RTSP listener close failed", e);
        }
        for (Socket socket : connections) closeSocket(socket);
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeSocket(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            Log.w("QuestHeldHost", "Listener close failed", e);
        }
    }
}
