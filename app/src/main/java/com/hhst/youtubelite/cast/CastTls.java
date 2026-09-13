package com.hhst.youtubelite.cast;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.util.Date;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;

public final class CastTls {
    private static final String ALIAS = "litube-cast-receiver-ec-v2";
    public record Identity(SSLContext context, String fingerprint) {}
    public static Identity identity() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            gen.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(new X500Principal("CN=LiTube TV"))
                    .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(new Date(0))
                    .setCertificateNotAfter(new Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000)).build());
            gen.generateKeyPair(); store.load(null);
        }
        PrivateKey privateKey = (PrivateKey) store.getKey(ALIAS, null);
        X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
        X509ExtendedKeyManager key = new X509ExtendedKeyManager() {
            public String[] getClientAliases(String type, Principal[] issuers) { return null; }
            public String chooseClientAlias(String[] types, Principal[] issuers, java.net.Socket socket) { return null; }
            public String[] getServerAliases(String type, Principal[] issuers) { return "EC".equals(type) ? new String[]{ALIAS} : null; }
            public String chooseServerAlias(String type, Principal[] issuers, java.net.Socket socket) { return "EC".equals(type) ? ALIAS : null; }
            public String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine engine) { return "EC".equals(type) ? ALIAS : null; }
            public X509Certificate[] getCertificateChain(String alias) { return ALIAS.equals(alias) ? new X509Certificate[]{certificate} : null; }
            public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? privateKey : null; }
        };
        SSLContext context = SSLContext.getInstance("TLS"); context.init(new KeyManager[]{key}, null, new SecureRandom());
        return new Identity(context, CastProtocol.hash(store.getCertificate(ALIAS).getEncoded()));
    }
    public static OkHttpClient client(String suppliedPin) throws Exception {
        String pin = CastProtocol.normalizeCode(suppliedPin);
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException(); }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing TV certificate");
                String actual = CastProtocol.hash(chain[0].getEncoded());
                if (!actual.startsWith(pin)) throw new CertificateException("TV code does not match; check the code shown on the TV");
            }
        };
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{trust}, new SecureRandom());
        // Host identity is verified by the physically confirmed certificate pin, not a public DNS name.
        return new OkHttpClient.Builder().sslSocketFactory(context.getSocketFactory(), trust).hostnameVerifier((host, session) -> true)
                .followRedirects(false).followSslRedirects(false).connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS).build();
    }
}
