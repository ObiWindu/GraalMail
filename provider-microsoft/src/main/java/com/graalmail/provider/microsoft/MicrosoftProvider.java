/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.domain.Account;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AttachmentService;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.MailboxService;
import com.graalmail.provider.api.MessageService;
import com.graalmail.provider.api.MessageSubmissionService;
import com.graalmail.provider.api.OAuthCapableProvider;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.provider.api.SearchService;
import com.graalmail.provider.api.SynchronizationService;
import com.graalmail.security.model.Credential;
import com.graalmail.security.model.TokenSet;
import com.graalmail.security.oauth.AuthorizationRequest;
import com.graalmail.security.oauth.OAuthClient;
import com.graalmail.security.oauth.OAuthConfig;
import java.time.Instant;
import org.slf4j.Logger;

/**
 * The provider for Outlook.com, Hotmail, Live.com and Microsoft 365.
 *
 * <p>Microsoft accounts are always authorized with the Microsoft identity
 * platform. The mailbox is then accessed either through the IMAP and SMTP
 * endpoints with XOAUTH2 or through the Microsoft Graph web API; both
 * transports implement the same provider-api interfaces.</p>
 */
public final class MicrosoftProvider implements AccountProvider, OAuthCapableProvider {

    private static final Logger LOG = Log.of(MicrosoftProvider.class);

    @Override
    public ProviderType type() {
        return ProviderType.MICROSOFT;
    }

    @Override
    public String displayName() {
        return "Outlook / Hotmail / Microsoft 365";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.builder(ProviderType.MICROSOFT)
                .authMethods(AuthMethod.OAUTH2)
                .serverSideSearch(true)
                .pushNotifications(false)
                .idleSupport(true)
                .draftSync(true)
                .labels(false)
                .flags(true)
                .attachments(true)
                .unifiedInbox(true)
                .offlineActions(true)
                .serverSideFlags(true)
                .sendCapable(true)
                .platform("linux", true)
                .platform("android", true)
                .note("Uses the Microsoft Graph API by default; the IMAP and SMTP endpoints are "
                        + "available for tenants that require the protocol based access.")
                .restriction("IMAP and SMTP may be disabled by the tenant administrator.")
                .documentationUrl("https://learn.microsoft.com/graph/api/resources/mail-api-overview")
                .build();
    }

    @Override
    public AuthorizationRequest beginAuthorization(AccountSetup setup) {
        OAuthConfig config = MicrosoftOAuthConfig.build(setup, MicrosoftTransportMode.GRAPH);
        return new OAuthClient(config).beginAuthorization();
    }

    @Override
    public AccountSetup completeAuthorization(AccountSetup setup, String code, String state,
                                              String receivedState, String codeVerifier) {
        if (state == null || receivedState == null || !state.equals(receivedState)) {
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "The authorization response did not match the request. Please try again.", false);
        }
        OAuthConfig config = MicrosoftOAuthConfig.build(setup, MicrosoftTransportMode.GRAPH);
        TokenSet tokens = new OAuthClient(config).exchangeCode(code, codeVerifier);
        LOG.debug("Microsoft authorization completed for {}", setup.email());
        return AccountSetup.builder(AuthMethod.OAUTH2)
                .email(setup.email())
                .displayName(setup.displayName())
                .credential(Credential.oauth(setup.email(), tokens))
                .settings(setup.settings())
                .build();
    }

    @Override
    public ProviderSession openSession(AccountSetup setup) {
        return openSession(null, setup);
    }

    @Override
    public ProviderSession openSession(Account account, AccountSetup setup) {
        if (setup.authMethod() != AuthMethod.OAUTH2) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Microsoft accounts require an OAuth sign in.", false);
        }
        TokenSet tokens = setup.credential() == null ? TokenSet.empty() : setup.credential().tokens();
        OAuthConfig config = MicrosoftOAuthConfig.build(setup, MicrosoftTransportMode.GRAPH);
        MicrosoftTokenManager tokenManager = new MicrosoftTokenManager(config, tokens);
        String accountId = account == null ? "" : account.id();
        MicrosoftTransportMode mode = MicrosoftTransportMode.fromSetting(
                setup.setting(MicrosoftTransportMode.SETTING, "graph"));

        if (mode == MicrosoftTransportMode.IMAP) {
            return new MicrosoftImapSession(accountId, setup.email(), tokenManager, setup.settings());
        }
        MicrosoftGraphClient client = new MicrosoftGraphClient(tokenManager::accessToken,
                new DefaultGraphHttpTransport());
        MailboxService mailboxes = new GraphBackedMailboxService(client, accountId);
        MessageService messages = new GraphBackedMessageService(client, mailboxes, accountId);
        MessageSubmissionService submission =
                new GraphBackedMessageSubmissionService(client, setup.email());
        AttachmentService attachments = new GraphBackedAttachmentService(client);
        SearchService search = new GraphBackedSearchService(client, accountId);
        SynchronizationService synchronization = new GraphBackedSynchronizationService(client);
        return new SimpleProviderSession(mailboxes, messages, submission, attachments, search,
                synchronization);
    }

    @Override
    public void revoke(AccountSetup setup) {
        if (setup.credential() == null) {
            return;
        }
        TokenSet tokens = setup.credential().tokens();
        if (!tokens.hasRefreshToken()) {
            return;
        }
        try {
            new OAuthClient(MicrosoftOAuthConfig.build(setup, MicrosoftTransportMode.GRAPH))
                    .revoke(tokens.refreshToken());
        } catch (RuntimeException e) {
            LOG.warn("The Microsoft token could not be revoked remotely: {}", e.getMessage());
        }
    }

    /**
     * A session that only bundles the services of a provider.
     */
    public static final class SimpleProviderSession implements ProviderSession {

        private final MailboxService mailboxes;
        private final MessageService messages;
        private final MessageSubmissionService submission;
        private final AttachmentService attachments;
        private final SearchService search;
        private final SynchronizationService synchronization;

        /**
         * Creates a session.
         *
         * @param mailboxes the mailbox service
         * @param messages the message service
         * @param submission the submission service
         * @param attachments the attachment service
         * @param search the search service
         * @param synchronization the synchronization service
         */
        public SimpleProviderSession(MailboxService mailboxes, MessageService messages,
                                     MessageSubmissionService submission,
                                     AttachmentService attachments, SearchService search,
                                     SynchronizationService synchronization) {
            this.mailboxes = mailboxes;
            this.messages = messages;
            this.submission = submission;
            this.attachments = attachments;
            this.search = search;
            this.synchronization = synchronization;
        }

        @Override
        public MailboxService mailboxes() { return mailboxes; }

        @Override
        public MessageService messages() { return messages; }

        @Override
        public MessageSubmissionService submission() { return submission; }

        @Override
        public AttachmentService attachments() { return attachments; }

        @Override
        public SearchService search() { return search; }

        @Override
        public SynchronizationService synchronization() { return synchronization; }

        @Override
        public void close() {
            // the Graph client is stateless
        }
    }

    /**
     * A session that uses the IMAP and SMTP endpoints of Microsoft with XOAUTH2.
     */
    public static final class MicrosoftImapSession implements ProviderSession {

        private final String accountId;
        private final String email;
        private final MicrosoftTokenManager tokenManager;
        private final java.util.Map<String, String> settings;
        private com.graalmail.provider.imap.ImapProviderSession delegate;

        /**
         * Creates the session.
         *
         * @param accountId the account id
         * @param email the account email address
         * @param tokenManager supplies the access token
         * @param settings the account settings
         */
        public MicrosoftImapSession(String accountId, String email,
                                    MicrosoftTokenManager tokenManager,
                                    java.util.Map<String, String> settings) {
            this.accountId = accountId == null ? "" : accountId;
            this.email = email == null ? "" : email;
            this.tokenManager = tokenManager;
            this.settings = settings == null ? java.util.Map.of() : java.util.Map.copyOf(settings);
        }

        private com.graalmail.provider.imap.ImapProviderSession delegate() {
            if (delegate == null) {
                com.graalmail.provider.imap.ImapServerConfig imapConfig =
                        com.graalmail.provider.imap.ImapServerConfig.builder()
                                .host(MicrosoftHosts.IMAP_HOST)
                                .port(MicrosoftHosts.IMAP_PORT)
                                .securityMode(com.graalmail.provider.imap.SecurityMode.SSL_TLS)
                                .username(email)
                                .authMechanism(AuthMethod.OAUTH2)
                                .build();
                com.graalmail.provider.imap.SmtpServerConfig smtpConfig =
                        com.graalmail.provider.imap.SmtpServerConfig.builder()
                                .host(MicrosoftHosts.SMTP_HOST)
                                .port(MicrosoftHosts.SMTP_PORT_STARTTLS)
                                .securityMode(com.graalmail.provider.imap.SecurityMode.STARTTLS)
                                .username(email)
                                .authMechanism(AuthMethod.OAUTH2)
                                .build();
                delegate = new com.graalmail.provider.imap.ImapProviderSession(accountId, email, null,
                        imapConfig, smtpConfig,
                        com.graalmail.provider.imap.ImapFolderTypes.DEFAULT, settings,
                        tokenManager::accessToken);
                delegate.connect();
            }
            return delegate;
        }

        @Override
        public MailboxService mailboxes() { return delegate().mailboxes(); }

        @Override
        public MessageService messages() { return delegate().messages(); }

        @Override
        public MessageSubmissionService submission() { return delegate().submission(); }

        @Override
        public AttachmentService attachments() { return delegate().attachments(); }

        @Override
        public SearchService search() { return delegate().search(); }

        @Override
        public SynchronizationService synchronization() { return delegate().synchronization(); }

        @Override
        public void close() {
            if (delegate != null) {
                delegate.close();
                delegate = null;
            }
        }
    }

    /**
     * The moment the token expires, used by the diagnostics screen.
     *
     * @param setup the account setup
     * @return the expiry, or {@code null} when unknown
     */
    public static Instant expiresAt(AccountSetup setup) {
        if (setup.credential() == null) {
            return null;
        }
        return setup.credential().tokens().expiresAt();
    }
}
