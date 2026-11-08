package dev.questheld.host;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

final class Pairing {
    private static final long WINDOW_MS = 90_000;
    private static final int SECRET_BYTES = 16;
    private final SharedPreferences prefs;
    private final HostIdentity identity;
    private final SecureRandom random = new SecureRandom();
    private Session session;
    private X509Certificate trusted;
    private boolean closed;

    private static final class Session {
        final InetAddress address;
        final String id;
        final X509Certificate client;
        final byte[] salt;
        final long expires = SystemClock.elapsedRealtime() + WINDOW_MS;
        byte[] key;
        byte[] secret;
        byte[] challenge;
        byte[] clientHash;
        boolean verified;

        Session(InetAddress address, String id, X509Certificate client, byte[] salt) {
            this.address = address;
            this.id = id;
            this.client = client;
            this.salt = salt;
        }
    }

    Pairing(SharedPreferences prefs, HostIdentity identity) throws GeneralSecurityException {
        this.prefs = prefs;
        this.identity = identity;
        String stored = prefs.getString("peer", null);
        if (stored != null) trusted = certificate(Base64.decode(stored, Base64.DEFAULT));
    }

    synchronized boolean enterPin(String pin) throws GeneralSecurityException {
        expire();
        if (session == null || session.key != null || !pin.matches("[0-9]{4}")) return false;
        session.key = Arrays.copyOf(hash(session.salt, pin.getBytes(StandardCharsets.US_ASCII)), SECRET_BYTES);
        Log.i("QuestHeldHost", "Local PIN accepted for pending request");
        notifyAll();
        return true;
    }

    synchronized String http(InetAddress address, Map<String, String> query)
            throws GeneralSecurityException, InterruptedException {
        expire();
        String id = query.get("uniqueid");
        if (closed || id == null || id.isEmpty() || id.length() > 128) return reply(false, "");
        if ("getservercert".equals(query.get("phrase"))) {
            if (session != null) return reply(false, "");
            byte[] salt = unhex(query.get("salt"));
            if (salt.length != SECRET_BYTES) return reply(false, "");
            X509Certificate client = certificate(unhex(query.get("clientcert")));
            String algorithm = client.getPublicKey().getAlgorithm();
            if (!"RSA".equals(algorithm) && !"EC".equals(algorithm)) return reply(false, "");
            Session pending = new Session(address, id, client, salt);
            session = pending;
            Log.i("QuestHeldHost", "Waiting for local PIN");
            while (session == pending && pending.key == null) {
                long remaining = pending.expires - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    expire();
                    break;
                }
                wait(remaining);
            }
            if (session != pending) return reply(false, "");
            String pem = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.encodeToString(identity.certificate.getEncoded(), Base64.DEFAULT)
                    + "-----END CERTIFICATE-----\n";
            return reply(true, field("plaincert", pem.getBytes(StandardCharsets.US_ASCII)));
        }
        if (!matches(address, id) || session.key == null) return reply(false, "");
        Session pending = session;
        if (pending.challenge == null && query.containsKey("clientchallenge")) {
            Log.i("QuestHeldHost", "Client challenge received");
            byte[] challenge = unhex(query.get("clientchallenge"));
            if (challenge.length != SECRET_BYTES) return reject();
            pending.secret = randomSecret();
            pending.challenge = randomSecret();
            byte[] response = hash(crypt(Cipher.DECRYPT_MODE, pending.key, challenge),
                    identity.certificate.getSignature(), pending.secret);
            return reply(true, field("challengeresponse", crypt(Cipher.ENCRYPT_MODE, pending.key,
                    concat(response, pending.challenge))));
        }
        if (pending.challenge != null && pending.clientHash == null && query.containsKey("serverchallengeresp")) {
            Log.i("QuestHeldHost", "Server challenge response received");
            byte[] response = unhex(query.get("serverchallengeresp"));
            if (response.length != 32) return reject();
            pending.clientHash = crypt(Cipher.DECRYPT_MODE, pending.key, response);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(identity.key);
            signer.update(pending.secret);
            return reply(true, field("pairingsecret", concat(pending.secret, signer.sign())));
        }
        if (pending.clientHash != null && !pending.verified && query.containsKey("clientpairingsecret")) {
            Log.i("QuestHeldHost", "Client signed proof received");
            byte[] signed = unhex(query.get("clientpairingsecret"));
            if (signed.length <= SECRET_BYTES) return reject();
            byte[] secret = Arrays.copyOf(signed, SECRET_BYTES);
            if (!MessageDigest.isEqual(pending.clientHash,
                    hash(pending.challenge, pending.client.getSignature(), secret))) return reject();
            String algorithm = pending.client.getPublicKey().getAlgorithm();
            Signature verifier = Signature.getInstance("EC".equals(algorithm) ? "SHA256withECDSA" : "SHA256withRSA");
            verifier.initVerify(pending.client);
            verifier.update(secret);
            if (!verifier.verify(signed, SECRET_BYTES, signed.length - SECRET_BYTES)) return reject();
            pending.verified = true;
            Log.i("QuestHeldHost", "Client proof verified. Waiting for TLS confirmation");
            return reply(true, "");
        }
        return reject();
    }

    synchronized String confirm(InetAddress address, String id, X509Certificate peer)
            throws GeneralSecurityException {
        expire();
        if (matches(address, id) && session.verified && session.client.equals(peer)) {
            if (!prefs.edit().putString("peer", Base64.encodeToString(peer.getEncoded(), Base64.NO_WRAP)).commit()) {
                return reject();
            }
            trusted = peer;
            Log.i("QuestHeldHost", "Client paired over mutual TLS");
            clear();
        }
        return reply(isTrusted(peer), "");
    }

    synchronized boolean allowsTls(X509Certificate peer) {
        expire();
        return !closed && (isTrusted(peer) || (session != null && session.verified && session.client.equals(peer)));
    }

    synchronized boolean isTrusted(X509Certificate peer) {
        return !closed && trusted != null && trusted.equals(peer);
    }

    synchronized void cancel(InetAddress address, String id) {
        if (matches(address, id)) {
            Log.i("QuestHeldHost", "Pending pairing cancelled");
            clear();
        }
    }

    synchronized void close() {
        closed = true;
        clear();
    }

    private boolean matches(InetAddress address, String id) {
        return session != null && session.address.equals(address) && session.id.equals(id);
    }

    private void expire() {
        if (session != null && SystemClock.elapsedRealtime() >= session.expires) clear();
    }

    private String reject() {
        clear();
        return reply(false, "");
    }

    private void clear() {
        if (session != null && session.key != null) Arrays.fill(session.key, (byte) 0);
        session = null;
        notifyAll();
    }

    private byte[] randomSecret() {
        byte[] value = new byte[SECRET_BYTES];
        random.nextBytes(value);
        return value;
    }

    private static X509Certificate certificate(byte[] bytes) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(bytes));
    }

    private static byte[] hash(byte[]... parts) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] part : parts) digest.update(part);
        return digest.digest();
    }

    private static byte[] crypt(int mode, byte[] key, byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static String field(String name, byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            value.append(Character.forDigit((b & 255) >>> 4, 16));
            value.append(Character.forDigit(b & 15, 16));
        }
        return "<" + name + ">" + value + "</" + name + ">";
    }

    private static byte[] unhex(String value) throws GeneralSecurityException {
        if (value == null || (value.length() & 1) != 0) throw new GeneralSecurityException("Invalid hex");
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new GeneralSecurityException("Invalid hex");
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    private static String reply(boolean paired, String fields) {
        return "<root status_code=\"200\"><paired>" + (paired ? 1 : 0) + "</paired>" + fields + "</root>";
    }
}
