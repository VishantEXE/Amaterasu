package dev.questheld.host;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

final class HostIdentity {
    private static final String ALIAS = "questheld-host-ec";
    final PrivateKey key;
    final X509Certificate certificate;

    HostIdentity() throws GeneralSecurityException, java.io.IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setCertificateSubject(new X500Principal("CN=QuestHeld Android"))
                    .setKeySize(256)
                    // Conscrypt passes an already hashed digest to the key.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .build());
            generator.generateKeyPair();
        }
        key = (PrivateKey) store.getKey(ALIAS, null);
        certificate = (X509Certificate) store.getCertificate(ALIAS);
    }

    SSLContext tls(Pairing pairing) throws GeneralSecurityException {
        X509KeyManager keys = new X509KeyManager() {
            @Override public String[] getClientAliases(String type, Principal[] issuers) { return null; }
            @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
            @Override public String[] getServerAliases(String type, Principal[] issuers) {
                return "EC".equals(type) ? new String[]{ALIAS} : null;
            }
            @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) {
                return "EC".equals(type) ? ALIAS : null;
            }
            @Override public X509Certificate[] getCertificateChain(String alias) {
                return ALIAS.equals(alias) ? new X509Certificate[]{certificate} : null;
            }
            @Override public PrivateKey getPrivateKey(String alias) {
                return ALIAS.equals(alias) ? key : null;
            }
        };
        X509TrustManager peers = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("Host-only TLS context");
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain.length == 0 || !pairing.allowsTls(chain[0])) {
                    throw new CertificateException("Unrecognized peer");
                }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{keys}, new TrustManager[]{peers}, null);
        return context;
    }
}
