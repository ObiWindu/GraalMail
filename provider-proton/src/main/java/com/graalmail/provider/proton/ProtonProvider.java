/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;

/**
 * Proton Mail provider based on the local Proton Mail Bridge.
 *
 * <p>Proton Mail publishes no IMAP or SMTP endpoints for third party clients.
 * The only supported integration is Proton Mail Bridge, a desktop application
 * that exposes local IMAP and SMTP endpoints with its own credentials. Bridge
 * runs on desktop systems only, so this provider reports itself as unavailable
 * on Android.</p>
 */
public final class ProtonProvider implements AccountProvider {

    /** Setting key for the bridge host. */
    public static final String SETTING_HOST = "bridge.host";
    /** Setting key for the bridge IMAP port. */
    public static final String SETTING_IMAP_PORT = "bridge.imapPort";
    /** Setting key for the bridge SMTP port. */
    public static final String SETTING_SMTP_PORT = "bridge.smtpPort";
    /** Setting key for the bridge username. */
    public static final String SETTING_USERNAME = "bridge.username";
    /** Setting key for the bridge password. */
    public static final String SETTING_PASSWORD = "bridge.password";
    /** Setting key for the confirmed bridge certificate fingerprint. */
    public static final String SETTING_FINGERPRINT = "bridge.certificateFingerprint";
    /** Setting key for the TLS mode, {@code STARTTLS} or {@code SSL}. */
    public static final String SETTING_TLS_MODE = "bridge.tlsMode";

    /** Message shown when the provider is used on Android. */
    public static final String ANDROID_UNAVAILABLE_MESSAGE =
            "Proton Mail integration on Android is not available; use the Proton Mail app or web client";

    /** Message shown when no certificate fingerprint has been confirmed. */
    public static final String MISSING_FINGERPRINT_MESSAGE =
            "Confirm the Proton Mail Bridge certificate fingerprint before connecting";

    /** Message shown when the stored certificate no longer matches the bridge. */
    public static final String CERTIFICATE_CHANGED_MESSAGE =
            "The Proton Mail Bridge certificate changed. " + ProtonTrustDecision.CHANGE_EXPLANATION
                    + " If Proton Mail Bridge was reinstalled, remove the Proton account "
                    + "in GraalMail and add it again with the login and the Bridge password.";

    private final String platform;

    /** Creates the provider for the current runtime platform. */
    public ProtonProvider() {
        this(ProtonPlatformSupport.currentPlatform());
    }

    /** Creates the provider for an explicit platform, used by the platform modules and tests. */
    public ProtonProvider(String platform) {
        this.platform = platform == null ? ProtonPlatformSupport.currentPlatform() : platform;
    }

    @Override
    public ProviderType type() {
        return ProviderType.PROTON;
    }

    @Override
    public String displayName() {
        return "Proton Mail (via Proton Mail Bridge)";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.builder(ProviderType.PROTON)
                .authMethods(AuthMethod.BRIDGE)
                .platform(ProtonPlatformSupport.LINUX, true)
                .platform(ProtonPlatformSupport.ANDROID, false)
                .labels(false)
                .flags(true)
                .idleSupport(true)
                .serverSideSearch(true)
                .serverSideFlags(true)
                .draftSync(true)
                .attachments(true)
                .sendCapable(true)
                .unifiedInbox(false)
                .offlineActions(false)
                .pushNotifications(false)
                .restriction("Requires a paid Proton Mail plan and Proton Mail Bridge running on "
                        + "the same machine")
                .restriction("Bridge-exposed mail is decrypted by Bridge; GraalMail does not "
                        + "provide end-to-end encryption")
                .restriction("Not available on Android")
                .note("Proton Mail Bridge exposes a plain IMAP view; Proton labels, contacts and "
                        + "encrypted search are not available")
                .documentationUrl("https://proton.me/support/comprehensive-guide-to-bridge-settings")
                .build();
    }

    @Override
    public ProviderSession openSession(AccountSetup setup) {
        if (!ProtonPlatformSupport.isAvailable(platform)) {
            throw new ProviderException(ErrorCode.FEATURE_UNSUPPORTED, ANDROID_UNAVAILABLE_MESSAGE,
                    false);
        }
        ProtonBridgeConfig config = toConfig(setup);
        return new ProtonProviderSession(setup, config);
    }

    /** True when the provider can be used on the given platform. */
    public boolean supportsPlatform(String candidate) {
        return ProtonPlatformSupport.isAvailable(candidate);
    }

    /** The platform this provider instance was created for. */
    public String platform() {
        return platform;
    }

    private ProtonBridgeConfig toConfig(AccountSetup setup) {
        String username = setup.setting(SETTING_USERNAME);
        if (username == null || username.isBlank()) {
            username = setup.email();
        }
        if (username == null || username.isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Enter the Proton login (the Proton Mail address).", false);
        }
        String password = setup.setting(SETTING_PASSWORD);
        if ((password == null || password.isEmpty()) && setup.credential() != null) {
            password = setup.credential().secret();
        }
        if (password == null || password.isEmpty()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Enter the Bridge password shown in Proton Mail Bridge.", false);
        }
        // Only the login and the Bridge password are required. Host, ports and
        // the TLS mode default to the local Bridge and are auto-detected when
        // the defaults do not answer.
        String host = setup.setting(SETTING_HOST, "");
        Integer imapPort = parsePortOrNull(setup.setting(SETTING_IMAP_PORT));
        Integer smtpPort = parsePortOrNull(setup.setting(SETTING_SMTP_PORT));
        ProtonBridgeConfig.TlsMode tlsMode = parseTlsModeOrNull(setup.setting(SETTING_TLS_MODE));
        String storedFingerprint = ProtonBridgeTrust.normalize(setup.setting(SETTING_FINGERPRINT, ""));
        ProtonBridgeAutoConfig auto = new ProtonBridgeAutoConfig();
        ProtonBridgeAutoConfig.Endpoint endpoint;
        String fingerprint;
        if (storedFingerprint.isEmpty()) {
            // First use: detect the endpoint, verify the login and pin the
            // observed certificate for this session.
            endpoint = auto.resolveAndVerify(host, imapPort, smtpPort, tlsMode, username, password);
            fingerprint = endpoint.fingerprint();
        } else {
            endpoint = auto.detect(host, imapPort, smtpPort, tlsMode);
            fingerprint = ProtonBridgeTrust.normalize(endpoint.fingerprint());
            if (!storedFingerprint.equals(fingerprint)) {
                throw new ProviderException(ErrorCode.TLS_ERROR, CERTIFICATE_CHANGED_MESSAGE, false);
            }
        }
        ProtonBridgeConfig.Builder builder = ProtonBridgeConfig.builder()
                .bridgeHost(endpoint.host())
                .imapPort(endpoint.imapPort())
                .smtpPort(endpoint.smtpPort())
                .bridgeUsername(username)
                .bridgePassword(password)
                .pinnedCertificateFingerprint(fingerprint)
                .tlsMode(endpoint.tlsMode());
        try {
            builder.sslContext(ProtonBridgeTrust.sslContextForPinnedFingerprint(fingerprint));
        } catch (Exception e) {
            throw new ProviderException(ErrorCode.TLS_ERROR,
                    "The Proton Mail Bridge certificate could not be prepared: " + e.getMessage(),
                    false, e);
        }
        return builder.build();
    }

    private static Integer parsePortOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException("port out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The Proton Mail Bridge port is not a number", false, e);
        }
    }

    private static ProtonBridgeConfig.TlsMode parseTlsModeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.trim().equalsIgnoreCase("SSL") || value.trim().equalsIgnoreCase("IMPLICIT")) {
            return ProtonBridgeConfig.TlsMode.SSL;
        }
        if (value.trim().equalsIgnoreCase("STARTTLS")) {
            return ProtonBridgeConfig.TlsMode.STARTTLS;
        }
        return null;
    }
}
