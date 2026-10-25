/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Detects whether Proton Mail Bridge is running on the local machine and
 * whether the certificate it presents is the one the user confirmed.
 */
public final class ProtonBridgeDetector {

    /** Message shown when no bridge is listening. */
    public static final String NOT_RUNNING_MESSAGE =
            "Proton Mail Bridge is not running. Start it and unlock your vault, then retry.";

    private final int connectTimeoutMillis;

    public ProtonBridgeDetector() {
        this(3_000);
    }

    public ProtonBridgeDetector(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    /** Detects the state of the bridge described by the configuration. */
    public Detection detect(ProtonBridgeConfig config) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.bridgeHost(), config.imapPort()),
                    connectTimeoutMillis);
        } catch (IOException e) {
            return new Detection(ProtonBridgeStatus.NOT_RUNNING, NOT_RUNNING_MESSAGE, "");
        } catch (RuntimeException e) {
            return new Detection(ProtonBridgeStatus.UNKNOWN,
                    "The state of Proton Mail Bridge could not be determined", "");
        }
        ProtonBridgeCertificateProbe.ProbeResult probe = new ProtonBridgeCertificateProbe()
                .probe(config.bridgeHost(), config.imapPort(), config.tlsMode(), config.sslContext());
        if (!probe.success() && probe.chain().length == 0) {
            if (probe.error().contains("IMAP greeting") || probe.error().contains("STARTTLS")) {
                return new Detection(ProtonBridgeStatus.UNKNOWN,
                        "Something is listening on " + config.bridgeHost() + ":" + config.imapPort()
                                + " but it does not look like Proton Mail Bridge", "");
            }
            return new Detection(ProtonBridgeStatus.RUNNING_WRONG_CERT,
                    "Proton Mail Bridge is running but its TLS certificate could not be verified: "
                            + probe.error(), "");
        }
        String observed = probe.sha256Fingerprint();
        ProtonTrustDecision decision = ProtonBridgeTrust.verify(
                config.pinnedCertificateFingerprint(), observed);
        switch (decision.verdict()) {
            case TRUSTED:
                return new Detection(ProtonBridgeStatus.RUNNING,
                        "Proton Mail Bridge is running and its certificate is confirmed",
                        observed);
            case MISMATCH:
                return new Detection(ProtonBridgeStatus.RUNNING_WRONG_CERT,
                        "Proton Mail Bridge is running with a different certificate. "
                                + ProtonTrustDecision.CHANGE_EXPLANATION,
                        observed);
            default:
                return new Detection(ProtonBridgeStatus.RUNNING_WRONG_CERT,
                        "Proton Mail Bridge is running but its certificate fingerprint has not been "
                                + "confirmed yet. Compare "
                                + (observed.isEmpty() ? "the certificate" : ProtonBridgeTrust.display(observed))
                                + " with the fingerprint shown by Bridge.",
                        observed);
        }
    }

    /** The outcome of a detection. */
    public static final class Detection {

        private final ProtonBridgeStatus status;
        private final String message;
        private final String observedFingerprint;

        Detection(ProtonBridgeStatus status, String message, String observedFingerprint) {
            this.status = status;
            this.message = message;
            this.observedFingerprint = observedFingerprint == null ? "" : observedFingerprint;
        }

        public ProtonBridgeStatus status() { return status; }
        public String message() { return message; }
        public String observedFingerprint() { return observedFingerprint; }

        public boolean running() {
            return status == ProtonBridgeStatus.RUNNING || status == ProtonBridgeStatus.RUNNING_WRONG_CERT;
        }
    }
}
