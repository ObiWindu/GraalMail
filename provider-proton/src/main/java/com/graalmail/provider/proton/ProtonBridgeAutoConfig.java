/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.proton.internal.ImapClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Automatic configuration of a local Proton Mail Bridge connection.
 *
 * <p>Only the login (the Proton address) and the Bridge password are required
 * from the user. Everything else is detected: the IMAP and SMTP ports, the TLS
 * mode and the certificate fingerprint.</p>
 *
 * <p>Trust policy: Bridge presents a self signed certificate. The observed
 * fingerprint is pinned automatically, but only together with a successful
 * IMAP login using the Bridge password (trust on first use bound to
 * authentication). Detection is restricted to loopback hosts, so the pin can
 * never be learned from a remote endpoint. A previously stored pin is always
 * verified strictly: a changed certificate is reported as an error and is
 * never re-pinned silently.</p>
 */
public final class ProtonBridgeAutoConfig {

    /** Hosts a local Bridge may listen on. Anything else is refused. */
    private static final List<String> LOOPBACK_HOSTS = List.of("127.0.0.1", "::1", "localhost");

    private static final int IMAP_SCAN_MIN = 1024;
    private static final int IMAP_SCAN_MAX = 1150;
    private static final int SMTP_SCAN_MIN = 1024;
    private static final int SMTP_SCAN_MAX = 1100;
    private static final int MAX_EHLO_LINES = 20;

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public ProtonBridgeAutoConfig() {
        this(1_000, 4_000);
    }

    public ProtonBridgeAutoConfig(int connectTimeoutMillis, int readTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /** A detected Bridge endpoint, before authentication. */
    public record Endpoint(String host, int imapPort, int smtpPort,
                           ProtonBridgeConfig.TlsMode tlsMode, String fingerprint) {
        /** Settings map that reproduces this endpoint, without secrets. */
        public Map<String, String> settings(String username) {
            Map<String, String> settings = new LinkedHashMap<>();
            settings.put(ProtonProvider.SETTING_HOST, host);
            settings.put(ProtonProvider.SETTING_IMAP_PORT, Integer.toString(imapPort));
            settings.put(ProtonProvider.SETTING_SMTP_PORT, Integer.toString(smtpPort));
            settings.put(ProtonProvider.SETTING_USERNAME, username);
            settings.put(ProtonProvider.SETTING_TLS_MODE, tlsMode.name());
            settings.put(ProtonProvider.SETTING_FINGERPRINT, fingerprint);
            return settings;
        }
    }

    /**
     * Detects the Bridge endpoint and verifies the login, returning the fully
     * resolved endpoint including the pinned fingerprint.
     *
     * @param host blank for the default local host; must be loopback otherwise
     * @param imapPort explicit IMAP port, or {@code null} to auto-detect
     * @param smtpPort explicit SMTP port, or {@code null} to auto-detect
     * @param tlsMode explicit TLS mode, or {@code null} to auto-detect
     * @param username the Bridge username, normally the Proton address
     * @param password the Bridge password
     * @return the resolved endpoint; the fingerprint is pinned by the caller
     */
    public Endpoint resolveAndVerify(String host, Integer imapPort, Integer smtpPort,
                                     ProtonBridgeConfig.TlsMode tlsMode,
                                     String username, String password) {
        String effectiveHost = requireLoopback(host);
        if (username == null || username.isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Enter the Proton login (the Proton Mail address).", false);
        }
        if (password == null || password.isEmpty()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Enter the Bridge password shown in Proton Mail Bridge.", false);
        }
        ImapEndpoint imap = findImapEndpoint(effectiveHost, imapPort, tlsMode);
        String fingerprint = imap.fingerprint();
        verifyLogin(effectiveHost, imap.port(), imap.tlsMode(), fingerprint, username, password);
        int smtp = findSmtpPort(effectiveHost, smtpPort, imap.tlsMode());
        return new Endpoint(effectiveHost, imap.port(), smtp, imap.tlsMode(), fingerprint);
    }

    /**
     * Detects the Bridge endpoint without credentials. Used to refresh stored
     * settings when the password is not at hand; the fingerprint is observed
     * but not trusted by this call alone.
     */
    public Endpoint detect(String host, Integer imapPort, Integer smtpPort,
                           ProtonBridgeConfig.TlsMode tlsMode) {
        String effectiveHost = requireLoopback(host);
        ImapEndpoint imap = findImapEndpoint(effectiveHost, imapPort, tlsMode);
        int smtp = findSmtpPort(effectiveHost, smtpPort, imap.tlsMode());
        return new Endpoint(effectiveHost, imap.port(), smtp, imap.tlsMode(), imap.fingerprint());
    }

    /**
     * Verifies the login against a resolved endpoint. On success the caller may
     * pin the endpoint fingerprint: authentication with the Bridge password
     * proves the endpoint is the user's Bridge.
     */
    public void verifyLogin(String host, int imapPort, ProtonBridgeConfig.TlsMode tlsMode,
                            String fingerprint, String username, String password) {
        SSLContext pinned;
        try {
            pinned = ProtonBridgeTrust.sslContextForPinnedFingerprint(fingerprint);
        } catch (Exception e) {
            throw new ProviderException(ErrorCode.TLS_ERROR,
                    "The Proton Mail Bridge certificate could not be prepared: " + e.getMessage(),
                    false, e);
        }
        ImapClient client = new ImapClient(host, imapPort, pinned,
                tlsMode == ProtonBridgeConfig.TlsMode.STARTTLS,
                connectTimeoutMillis, readTimeoutMillis);
        try {
            client.connect();
            client.login(username, password);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("rejected")) {
                throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                        "Proton Mail Bridge rejected the login. Check the login and the Bridge "
                                + "password shown in Proton Mail Bridge.", false, e);
            }
            throw new ProviderException(ErrorCode.CONNECTION_REFUSED,
                    "Proton Mail Bridge could not be reached: " + e.getMessage(), true, e);
        } finally {
            client.close();
        }
    }

    /** Rejects non-loopback hosts so auto-trust can never learn from a remote endpoint. */
    static String requireLoopback(String host) {
        String effective = host == null || host.isBlank()
                ? ProtonBridgeConfig.DEFAULT_HOST : host.trim();
        String canonical = effective.toLowerCase(Locale.ROOT);
        if (canonical.startsWith("[") && canonical.endsWith("]")) {
            canonical = canonical.substring(1, canonical.length() - 1);
        }
        if (!LOOPBACK_HOSTS.contains(canonical)) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Proton Mail Bridge must run on this machine (" + effective
                            + " is not a local address).", false);
        }
        return effective;
    }

    private record ImapEndpoint(int port, ProtonBridgeConfig.TlsMode tlsMode, String fingerprint) { }

    private ImapEndpoint findImapEndpoint(String host, Integer explicitPort,
                                          ProtonBridgeConfig.TlsMode explicitTls) {
        List<Integer> ports = explicitPort != null
                ? List.of(explicitPort)
                : prefixed(ProtonBridgeConfig.DEFAULT_IMAP_PORT, IMAP_SCAN_MIN, IMAP_SCAN_MAX);
        List<ProtonBridgeConfig.TlsMode> modes = explicitTls != null
                ? List.of(explicitTls)
                : List.of(ProtonBridgeConfig.TlsMode.STARTTLS, ProtonBridgeConfig.TlsMode.SSL);
        for (int port : ports) {
            for (ProtonBridgeConfig.TlsMode mode : modes) {
                String fingerprint = probeImapFingerprint(host, port, mode);
                if (fingerprint != null) {
                    return new ImapEndpoint(port, mode, fingerprint);
                }
            }
        }
        if (explicitPort != null) {
            // Stored ports may be stale (Bridge reassigns them); fall back to a full scan.
            for (int port : prefixed(ProtonBridgeConfig.DEFAULT_IMAP_PORT, IMAP_SCAN_MIN, IMAP_SCAN_MAX)) {
                if (port == explicitPort) continue;
                for (ProtonBridgeConfig.TlsMode mode : modes) {
                    String fingerprint = probeImapFingerprint(host, port, mode);
                    if (fingerprint != null) {
                        return new ImapEndpoint(port, mode, fingerprint);
                    }
                }
            }
        }
        throw new ProviderException(ErrorCode.CONNECTION_REFUSED,
                ProtonBridgeDetector.NOT_RUNNING_MESSAGE, true);
    }

    private int findSmtpPort(String host, Integer explicitPort, ProtonBridgeConfig.TlsMode tlsMode) {
        List<Integer> ports = explicitPort != null
                ? List.of(explicitPort)
                : prefixed(ProtonBridgeConfig.DEFAULT_SMTP_PORT, SMTP_SCAN_MIN, SMTP_SCAN_MAX);
        boolean startTls = tlsMode == ProtonBridgeConfig.TlsMode.STARTTLS;
        for (int port : ports) {
            if (isSmtpEndpoint(host, port, startTls)) {
                return port;
            }
        }
        if (explicitPort != null) {
            for (int port : prefixed(ProtonBridgeConfig.DEFAULT_SMTP_PORT, SMTP_SCAN_MIN, SMTP_SCAN_MAX)) {
                if (port == explicitPort) continue;
                if (isSmtpEndpoint(host, port, startTls)) {
                    return port;
                }
            }
        }
        throw new ProviderException(ErrorCode.CONNECTION_REFUSED,
                "Proton Mail Bridge is reachable for mail but its SMTP endpoint was not found. "
                        + "Enable SMTP in the Bridge settings and retry.", true);
    }

    /**
     * Probes one IMAP candidate. Returns the observed certificate fingerprint
     * when the endpoint speaks IMAP with TLS, {@code null} otherwise. A
     * verification failure with a collected chain is the expected outcome for
     * the self signed Bridge certificate and still counts as a hit.
     */
    private String probeImapFingerprint(String host, int port, ProtonBridgeConfig.TlsMode tlsMode) {
        ProtonBridgeCertificateProbe.ProbeResult probe = new ProtonBridgeCertificateProbe(
                connectTimeoutMillis, readTimeoutMillis).probe(host, port, tlsMode, null);
        if (probe.chain().length == 0) {
            return null;
        }
        return probe.sha256Fingerprint();
    }

    /** True when the port answers like an SMTP server with the expected TLS mode. */
    boolean isSmtpEndpoint(String host, int port, boolean startTls) {
        if (startTls) {
            return smtpSupportsStartTls(host, port);
        }
        return tlsHandshakePresentsChain(host, port);
    }

    private boolean smtpSupportsStartTls(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String greeting = reader.readLine();
            if (greeting == null || !greeting.startsWith("220")) {
                return false;
            }
            out.write("EHLO graalmail\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String line;
            boolean startTlsOffered = false;
            for (int i = 0; i < MAX_EHLO_LINES; i++) {
                line = reader.readLine();
                if (line == null) {
                    return false;
                }
                if (line.toUpperCase(Locale.ROOT).contains("STARTTLS")) {
                    startTlsOffered = true;
                }
                if (line.startsWith("250 ")) {
                    break;
                }
                if (!line.startsWith("250-")) {
                    return false;
                }
            }
            return startTlsOffered;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean tlsHandshakePresentsChain(String host, int port) {
        CollectingTrustManager collector = new CollectingTrustManager();
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{collector}, null);
            SSLSocketFactory factory = context.getSocketFactory();
            try (Socket plain = factory.createSocket()) {
                plain.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
                plain.setSoTimeout(readTimeoutMillis);
                if (plain instanceof SSLSocket ssl) {
                    try {
                        ssl.startHandshake();
                    } catch (IOException ignored) {
                        // Expected: the collecting trust manager trusts nothing.
                    }
                }
            } catch (IOException ignored) {
                // Any failure still counts when a chain was presented.
            }
        } catch (Exception ignored) {
            // No TLS context available; treat as no endpoint.
        }
        return collector.chain != null && collector.chain.length > 0;
    }

    private static List<Integer> prefixed(int first, int min, int max) {
        List<Integer> ports = new ArrayList<>();
        ports.add(first);
        for (int port = min; port <= max; port++) {
            if (port != first) {
                ports.add(port);
            }
        }
        return ports;
    }

    /** Trust manager that records the chain and trusts nothing. */
    private static final class CollectingTrustManager implements X509TrustManager {
        private X509Certificate[] chain;

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            throw new UnsupportedOperationException("Client certificates are not accepted");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            this.chain = chain;
            throw new java.security.cert.CertificateException("Collection only");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
