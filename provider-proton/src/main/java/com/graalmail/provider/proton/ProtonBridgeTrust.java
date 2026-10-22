/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Certificate pinning for a local Proton Mail Bridge.
 *
 * <p>Proton Mail Bridge presents a self signed certificate. The user has to
 * compare the SHA-256 fingerprint that Bridge displays with the fingerprint
 * observed by this client and confirm it. A pin that does not match is reported
 * as a failure; verification is never bypassed.</p>
 */
public final class ProtonBridgeTrust {

    private ProtonBridgeTrust() {
    }

    /**
     * Verifies an observed fingerprint against a confirmed pin. Pure function so
     * that it can be unit tested without a running bridge.
     *
     * @param pinnedFingerprint the fingerprint the user confirmed, may be blank
     * @param observedFingerprint the fingerprint currently presented by the bridge
     * @return the trust decision
     */
    public static ProtonTrustDecision verify(String pinnedFingerprint, String observedFingerprint) {
        String pinned = normalize(pinnedFingerprint);
        String observed = normalize(observedFingerprint);
        if (pinned.isEmpty()) {
            return new ProtonTrustDecision(ProtonTrustDecision.Verdict.NOT_PINNED,
                    "Confirm the Proton Mail Bridge certificate fingerprint before connecting",
                    pinned, observed);
        }
        if (observed.isEmpty()) {
            return new ProtonTrustDecision(ProtonTrustDecision.Verdict.NOT_PINNED,
                    "The Proton Mail Bridge certificate fingerprint could not be read",
                    pinned, observed);
        }
        if (pinned.equals(observed)) {
            return new ProtonTrustDecision(ProtonTrustDecision.Verdict.TRUSTED,
                    "The Proton Mail Bridge certificate matches the confirmed fingerprint",
                    pinned, observed);
        }
        return new ProtonTrustDecision(ProtonTrustDecision.Verdict.MISMATCH,
                "The Proton Mail Bridge certificate changed. "
                        + ProtonTrustDecision.CHANGE_EXPLANATION,
                pinned, observed);
    }

    /**
     * Canonical form of a fingerprint: upper case hex without separators.
     * Accepts hex (with or without separators) and base64 encodings.
     */
    public static String normalize(String fingerprint) {
        if (fingerprint == null) {
            return "";
        }
        String value = fingerprint.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.regionMatches(true, 0, "SHA256:", 0, "SHA256:".length())) {
            value = value.substring("SHA256:".length()).trim();
        }
        String compact = value.replace(":", "").replace(" ", "").replace("-", "");
        if (compact.length() == 64 && isHex(compact)) {
            return compact.toUpperCase(Locale.ROOT);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(compact);
            if (decoded.length == 32) {
                return toHex(decoded);
            }
        } catch (IllegalArgumentException ignored) {
            // not base64 either
        }
        return compact.toUpperCase(Locale.ROOT);
    }

    /** Formats a canonical fingerprint as {@code SHA256:AA:BB:...}. */
    public static String display(String fingerprint) {
        String canonical = normalize(fingerprint);
        if (canonical.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("SHA256:");
        for (int i = 0; i < canonical.length(); i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(canonical, i, Math.min(i + 2, canonical.length()));
        }
        return sb.toString();
    }

    /** SHA-256 fingerprint of a certificate, canonical hex form. */
    public static String fingerprintOf(X509Certificate certificate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(certificate.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute certificate fingerprint", e);
        }
    }

    /**
     * Builds a context that trusts exactly the pinned fingerprint.
     * Certificate and hostname verification remain enabled.
     */
    public static SSLContext sslContextForPinnedFingerprint(String pinnedFingerprint)
            throws Exception {
        String canonical = normalize(pinnedFingerprint);
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("A confirmed fingerprint is required");
        }
        TrustManager pinning = new FingerprintTrustManager(canonical);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{pinning}, null);
        return context;
    }

    /** Builds a context that trusts the given certificate file, using the shared TLS helper. */
    public static SSLContext sslContextForCertificate(Path certificate) throws Exception {
        return com.graalmail.security.tls.TlsContexts.withCertificate(certificate);
    }

    /** Enables hostname verification on a socket. */
    public static void enableHostnameVerification(SSLSocket socket) {
        javax.net.ssl.SSLParameters params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(params);
    }

    /** Reads an X.509 certificate from a PEM or DER file. */
    public static X509Certificate readCertificate(Path certificate) throws Exception {
        try (InputStream in = Files.newInputStream(certificate)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Trust manager that accepts a single pinned fingerprint. Any other
     * certificate is rejected; there is no trust-all path.
     */
    static final class FingerprintTrustManager implements X509TrustManager {

        private final String pinned;

        FingerprintTrustManager(String pinned) {
            this.pinned = pinned;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            throw new UnsupportedOperationException("Client certificates are not accepted");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            if (chain == null || chain.length == 0) {
                throw new java.security.cert.CertificateException("Empty certificate chain");
            }
            String observed = fingerprintOf(chain[0]);
            if (!pinned.equals(observed)) {
                throw new java.security.cert.CertificateException(
                        "The Proton Mail Bridge certificate does not match the confirmed fingerprint");
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
