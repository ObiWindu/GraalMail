/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.tls;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * TLS helpers. Certificate and hostname verification are never disabled.
 * A custom trust store can be supplied for providers that publish their own
 * certificate authority (for example a locally running Proton Mail Bridge).
 */
public final class TlsContexts {

    private TlsContexts() {
    }

    /** The default JVM trust configuration with full verification. */
    public static SSLContext systemDefault() throws Exception {
        return SSLContext.getDefault();
    }

    /** Builds a context trusting the given PEM or DER encoded certificate file. */
    public static SSLContext withCertificate(Path certificate) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        try (InputStream in = Files.newInputStream(certificate)) {
            keyStore.setCertificateEntry("provider", readCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    private static java.security.cert.Certificate readCertificate(InputStream in) throws CertificateException {
        try {
            return java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (CertificateException e) {
            throw e;
        } catch (Exception e) {
            throw new CertificateException("Unable to parse certificate", e);
        }
    }

    /** The default trust manager; exposed so callers can inspect accepted issuers. */
    public static X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("No X509 trust manager available");
    }

    /** True when the certificate chain is trusted by the platform. */
    public static boolean isTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        try {
            defaultTrustManager().checkServerTrusted(chain, authType);
            return true;
        } catch (CertificateException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static String describe(java.security.cert.X509Certificate[] chain) {
        return Arrays.toString(chain);
    }
}
