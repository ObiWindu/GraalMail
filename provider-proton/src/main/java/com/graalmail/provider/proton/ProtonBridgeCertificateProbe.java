/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.security.cert.X509Certificate;
import java.time.Instant;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Reads the TLS certificate that Proton Mail Bridge presents so that the user
 * can compare its SHA-256 fingerprint with the one shown by Bridge.
 *
 * <p>The probe performs a normal TLS handshake. Verification failures are
 * reported, never bypassed: the probe never installs a trust-all trust manager.
 * When no pinned context is supplied, the handshake uses a trust manager that
 * accepts nothing; it only records the presented chain so that the fingerprint
 * can be displayed, and the failure is reported in the result.</p>
 */
public final class ProtonBridgeCertificateProbe {

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public ProtonBridgeCertificateProbe() {
        this(5_000, 10_000);
    }

    public ProtonBridgeCertificateProbe(int connectTimeoutMillis, int readTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /** Probes the bridge with the given trust configuration. */
    public ProbeResult probe(String host, int port, ProtonBridgeConfig.TlsMode tlsMode,
                              SSLContext sslContext) {
        CollectingTrustManager collector = new CollectingTrustManager();
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            SSLSocket sslSocket;
            if (tlsMode == ProtonBridgeConfig.TlsMode.SSL) {
                sslSocket = sslSocket(host, port, sslContext, collector);
            } else {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = socket.getOutputStream();
                String greeting = reader.readLine();
                if (greeting == null || !greeting.startsWith("* OK")) {
                    socket.close();
                    return ProbeResult.failure("The service on " + host + ":" + port
                            + " did not answer with an IMAP greeting");
                }
                out.write("a001 STARTTLS\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String response = reader.readLine();
                if (response == null || !response.toUpperCase().startsWith("A001 OK")) {
                    socket.close();
                    return ProbeResult.failure("Proton Mail Bridge did not accept STARTTLS");
                }
                sslSocket = sslSocket(socket, host, port, sslContext, collector);
            }
            ProtonBridgeTrust.enableHostnameVerification(sslSocket);
            sslSocket.startHandshake();
            X509Certificate[] chain = peerChain(sslSocket.getSession().getPeerCertificates());
            ProbeResult result = ProbeResult.success(chain);
            sslSocket.close();
            return result;
        } catch (SSLHandshakeException e) {
            ProbeResult failure = ProbeResult.failure(
                    "TLS verification failed: " + describe(e));
            if (collector.chain != null) {
                failure = failure.withChain(collector.chain);
            }
            return failure;
        } catch (Exception e) {
            return ProbeResult.failure(describe(e));
        }
    }

    private SSLSocket sslSocket(String host, int port, SSLContext context,
                                CollectingTrustManager collector) throws Exception {
        SSLContext effective = context != null ? context : rejectingContext(collector);
        SSLSocketFactory factory = effective.getSocketFactory();
        return (SSLSocket) factory.createSocket(host, port);
    }

    private SSLSocket sslSocket(Socket plain, String host, int port, SSLContext context,
                                CollectingTrustManager collector) throws Exception {
        SSLContext effective = context != null ? context : rejectingContext(collector);
        SSLSocketFactory factory = effective.getSocketFactory();
        return (SSLSocket) factory.createSocket(plain, host, port, true);
    }

    private SSLContext rejectingContext(CollectingTrustManager collector) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{collector}, null);
        return context;
    }

    private static X509Certificate[] peerChain(java.security.cert.Certificate[] certificates) {
        List<X509Certificate> chain = new ArrayList<>();
        for (java.security.cert.Certificate certificate : certificates) {
            if (certificate instanceof X509Certificate x509) {
                chain.add(x509);
            }
        }
        return chain.toArray(new X509Certificate[0]);
    }

    private static String describe(Exception e) {        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** The result of a certificate probe. */
    public static final class ProbeResult {

        private final boolean success;
        private final X509Certificate[] chain;
        private final String error;

        private ProbeResult(boolean success, X509Certificate[] chain, String error) {
            this.success = success;
            this.chain = chain;
            this.error = error == null ? "" : error;
        }

        static ProbeResult success(X509Certificate[] chain) {
            return new ProbeResult(true, chain, "");
        }

        static ProbeResult failure(String error) {
            return new ProbeResult(false, null, error);
        }

        ProbeResult withChain(X509Certificate[] collected) {
            return new ProbeResult(false, collected, error);
        }

        public boolean success() { return success; }
        public String error() { return error; }
        public X509Certificate[] chain() { return chain == null ? new X509Certificate[0] : chain; }

        /** SHA-256 fingerprint in canonical hex form, or an empty string. */
        public String sha256Fingerprint() {
            X509Certificate[] c = chain();
            return c.length == 0 ? "" : ProtonBridgeTrust.fingerprintOf(c[0]);
        }

        /** Fingerprint formatted as {@code SHA256:AA:BB:...}. */
        public String displayFingerprint() {
            return ProtonBridgeTrust.display(sha256Fingerprint());
        }

        public String subject() {
            X509Certificate[] c = chain();
            return c.length == 0 ? "" : c[0].getSubjectX500Principal().getName();
        }

        public String issuer() {
            X509Certificate[] c = chain();
            return c.length == 0 ? "" : c[0].getIssuerX500Principal().getName();
        }

        public Instant notBefore() {
            X509Certificate[] c = chain();
            return c.length == 0 ? null : c[0].getNotBefore().toInstant();
        }

        public Instant notAfter() {
            X509Certificate[] c = chain();
            return c.length == 0 ? null : c[0].getNotAfter().toInstant();
        }
    }

    /**
     * Trust manager that trusts nothing. It only records the presented chain so
     * that the fingerprint can be shown; the handshake still fails.
     */
    private static final class CollectingTrustManager implements X509TrustManager {

        private X509Certificate[] chain;

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("Client certificates are not accepted");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            this.chain = chain;
            throw new CertificateException(
                    "The Proton Mail Bridge certificate is not trusted by the platform");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
