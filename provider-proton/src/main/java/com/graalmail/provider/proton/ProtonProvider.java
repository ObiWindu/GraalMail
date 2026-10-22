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
import java.util.List;

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

    private static final List<String> REQUIRED_SETTINGS = List.of(
            SETTING_HOST, SETTING_IMAP_PORT, SETTING_SMTP_PORT, SETTING_USERNAME, SETTING_PASSWORD);

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
        for (String key : REQUIRED_SETTINGS) {
            if (setup.setting(key) == null || setup.setting(key).isBlank()) {
                throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                        "The Proton Mail Bridge setting " + key + " is missing", false);
            }
        }
        String fingerprint = setup.setting(SETTING_FINGERPRINT, "");
        if (fingerprint.isBlank()) {
            throw new ProviderException(ErrorCode.TLS_ERROR, MISSING_FINGERPRINT_MESSAGE, false);
        }
        String password = setup.setting(SETTING_PASSWORD);
        if (password.isEmpty() && setup.credential() != null) {
            password = setup.credential().secret();
        }
        ProtonBridgeConfig.Builder builder = ProtonBridgeConfig.builder()
                .bridgeHost(setup.setting(SETTING_HOST))
                .imapPort(parsePort(setup.setting(SETTING_IMAP_PORT)))
                .smtpPort(parsePort(setup.setting(SETTING_SMTP_PORT)))
                .bridgeUsername(setup.setting(SETTING_USERNAME))
                .bridgePassword(password)
                .pinnedCertificateFingerprint(fingerprint)
                .tlsMode(parseTlsMode(setup.setting(SETTING_TLS_MODE)));
        try {
            builder.sslContext(ProtonBridgeTrust.sslContextForPinnedFingerprint(fingerprint));
        } catch (Exception e) {
            throw new ProviderException(ErrorCode.TLS_ERROR,
                    "The Proton Mail Bridge certificate could not be prepared: " + e.getMessage(),
                    false, e);
        }
        return builder.build();
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The Proton Mail Bridge port is not a number", false, e);
        }
    }

    private static ProtonBridgeConfig.TlsMode parseTlsMode(String value) {
        if (value == null || value.isBlank()) {
            return ProtonBridgeConfig.TlsMode.STARTTLS;
        }
        if (value.trim().equalsIgnoreCase("SSL") || value.trim().equalsIgnoreCase("IMPLICIT")) {
            return ProtonBridgeConfig.TlsMode.SSL;
        }
        return ProtonBridgeConfig.TlsMode.STARTTLS;
    }
}
