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
import com.graalmail.provider.proton.api.DefaultProtonHttpTransport;
import com.graalmail.provider.proton.api.ProtonApiClient;
import com.graalmail.provider.proton.api.ProtonAuth;
import com.graalmail.provider.proton.api.ProtonHttpRequest;
import com.graalmail.security.model.Credential;
import com.graalmail.security.model.TokenSet;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Proton Mail provider with direct API login.
 *
 * <p>The default transport talks to the Proton API with the Proton username
 * and password (SRP authentication, PGP bodies decrypted locally with the
 * mailbox password). The legacy Bridge transport is still available on desktop
 * Linux via the {@code proton.transport} setting.</p>
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
    /** Setting key for the Proton API base URL. */
    public static final String SETTING_API_BASE_URL = "proton.apiBaseUrl";
    /** Setting key for the client version sent to the Proton API. */
    public static final String SETTING_APP_VERSION = "proton.appVersion";
    /** Setting key for the Proton session UID. */
    public static final String SETTING_UID = "proton.uid";

    /** Message shown when Bridge mode is used on Android. */
    public static final String ANDROID_UNAVAILABLE_MESSAGE =
            "Proton Mail Bridge does not run on Android; sign in with the Proton "
                    + "username and password instead, which needs no Bridge";

    /** Message shown when the stored certificate no longer matches the bridge. */
    public static final String CERTIFICATE_CHANGED_MESSAGE =
            "The Proton Mail Bridge certificate changed. " + ProtonTrustDecision.CHANGE_EXPLANATION
                    + " If Proton Mail Bridge was reinstalled, remove the Proton account "
                    + "in GraalMail and add it again.";

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
        return "Proton Mail";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.builder(ProviderType.PROTON)
                .authMethods(AuthMethod.PASSWORD, AuthMethod.BRIDGE)
                .platform(ProtonPlatformSupport.LINUX, true)
                .platform(ProtonPlatformSupport.ANDROID, true)
                .labels(true)
                .flags(true)
                .idleSupport(false)
                .serverSideSearch(true)
                .serverSideFlags(true)
                .draftSync(false)
                .attachments(true)
                .sendCapable(false)
                .unifiedInbox(false)
                .offlineActions(false)
                .pushNotifications(false)
                .restriction("Sending mail and saving drafts with a Proton account are not "
                        + "supported yet; reading, flagging and moving mail works")
                .restriction("Accounts with two-password mode need the mailbox password in "
                        + "addition to the login password")
                .restriction("Proton Mail Bridge mode needs a paid Proton Mail plan and Bridge "
                        + "running on the same machine, and is unavailable on Android")
                .note("Sign in uses the Proton username and password directly; no Bridge is needed")
                .note("Message bodies are PGP-encrypted on the server and are decrypted locally "
                        + "with the mailbox password")
                .note("Proton labels appear as folders; contacts are not available")
                .documentationUrl("https://proton.me/support/proton-mail-bridge")
                .build();
    }

    @Override
    public ProviderSession openSession(AccountSetup setup) {
        ProtonTransportMode mode = ProtonTransportMode.fromSetting(
                setup.setting(ProtonTransportMode.SETTING));
        if (mode == ProtonTransportMode.BRIDGE) {
            if (!ProtonPlatformSupport.isAvailable(platform)) {
                throw new ProviderException(ErrorCode.FEATURE_UNSUPPORTED,
                        ANDROID_UNAVAILABLE_MESSAGE, false);
            }
            ProtonBridgeConfig config = toBridgeConfig(setup);
            return new ProtonProviderSession(setup, config);
        }
        return openApiSession(setup);
    }

    /** True when the provider can be used on the given platform. */
    public boolean supportsPlatform(String candidate) {
        return true;
    }

    @Override
    public void revoke(AccountSetup setup) {
        if (ProtonTransportMode.fromSetting(setup.setting(ProtonTransportMode.SETTING))
                != ProtonTransportMode.API) {
            return;
        }
        Credential credential = setup.credential();
        String accessToken = credential == null ? "" : credential.tokens().accessToken();
        String uid = setup.setting(SETTING_UID, "");
        if (accessToken.isBlank() || uid.isBlank()) {
            return;
        }
        try {
            String baseUrl = setup.setting(SETTING_API_BASE_URL,
                    DefaultProtonHttpTransport.DEFAULT_BASE_URL);
            String appVersion = setup.setting(SETTING_APP_VERSION, ProtonApiClient.DEFAULT_APP_VERSION);
            new DefaultProtonHttpTransport(baseUrl, appVersion, 10_000, 10_000).execute(
                    ProtonHttpRequest.delete("/auth").withHeaders(Map.of(
                            "X-Pm-Uid", uid, "Authorization", "Bearer " + accessToken)));
        } catch (RuntimeException | java.io.IOException ignored) {
            // Best effort: local deletion proceeds regardless.
        }
    }

    /** The platform this provider instance was created for. */
    public String platform() {
        return platform;
    }

    private ProviderSession openApiSession(AccountSetup setup) {
        String username = setup.email();
        if (username == null || username.isBlank()) {
            username = setup.setting(SETTING_USERNAME, "");
        }
        if (username.isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Enter the Proton username.", false);
        }
        Credential credential = setup.credential();
        String loginPassword = credential == null ? "" : credential.secret();
        if (loginPassword.isEmpty()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Enter the Proton password.", false);
        }
        String mailboxPassword = credential.keySecret();
        if (mailboxPassword.isEmpty()) {
            mailboxPassword = loginPassword;
        }
        String baseUrl = setup.setting(SETTING_API_BASE_URL,
                DefaultProtonHttpTransport.DEFAULT_BASE_URL);
        String appVersion = setup.setting(SETTING_APP_VERSION, ProtonApiClient.DEFAULT_APP_VERSION);
        DefaultProtonHttpTransport transport =
                new DefaultProtonHttpTransport(baseUrl, appVersion, 15_000, 30_000);
        ProtonAuth auth = new ProtonAuth(transport);
        ProtonAuth.Tokens tokens = storedTokens(setup, credential);
        if (tokens == null) {
            tokens = loginWithPassword(auth, username, loginPassword);
        }
        ProtonApiClient client = new ProtonApiClient(transport, auth, tokens);
        ProtonApiSession session = new ProtonApiSession(username, client,
                mailboxPassword.getBytes(StandardCharsets.UTF_8), username);
        session.unlock();
        return session;
    }

    private static ProtonAuth.Tokens storedTokens(AccountSetup setup, Credential credential) {
        if (credential == null) {
            return null;
        }
        TokenSet tokens = credential.tokens();
        if (tokens.accessToken().isBlank() && !tokens.hasRefreshToken()) {
            return null;
        }
        String uid = setup.setting(SETTING_UID, "");
        if (uid.isBlank()) {
            return null;
        }
        return new ProtonAuth.Tokens(uid, tokens.accessToken(), tokens.refreshToken(),
                tokens.expiresAt(), tokens.scope(), 1);
    }

    private static ProtonAuth.Tokens loginWithPassword(ProtonAuth auth, String username, String password) {
        try {
            return auth.login(username, password);
        } catch (ProtonAuth.TwoFactorRequired e) {
            throw new ProviderException(ErrorCode.CONSENT_REQUIRED,
                    "This Proton account uses two-factor authentication. Remove the account and "
                            + "add it again, entering the two-factor code when asked.", false, e);
        }
    }

    private ProtonBridgeConfig toBridgeConfig(AccountSetup setup) {
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
