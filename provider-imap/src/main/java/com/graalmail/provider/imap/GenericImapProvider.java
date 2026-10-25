/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.core.domain.Account;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;

/**
 * The generic provider for any standards compliant IMAP and SMTP server.
 *
 * <p>The user configures the hosts, ports, security modes and the credentials.
 * Nothing is assumed about the server beyond the protocol itself.</p>
 */
public final class GenericImapProvider implements AccountProvider {

    private final FolderTypeResolver folderTypes;

    /** Creates a provider that uses the default folder role detection. */
    public GenericImapProvider() {
        this(ImapFolderTypes.DEFAULT);
    }

    /**
     * Creates a provider with a custom folder role resolver.
     *
     * @param folderTypes the resolver used to classify mailboxes
     */
    public GenericImapProvider(FolderTypeResolver folderTypes) {
        this.folderTypes = folderTypes == null ? ImapFolderTypes.DEFAULT : folderTypes;
    }

    @Override
    public ProviderType type() {
        return ProviderType.GENERIC;
    }

    @Override
    public String displayName() {
        return "Other IMAP/SMTP provider";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.builder(ProviderType.GENERIC)
                .authMethods(AuthMethod.PASSWORD, AuthMethod.APP_PASSWORD)
                .serverSideSearch(true)
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
                .note("Search, flags and drafts depend on the extensions the server supports.")
                .build();
    }

    @Override
    public ProviderSession openSession(AccountSetup setup) {
        ImapAccountFactory.validate(setup);
        ImapServerConfig imapConfig = ImapAccountFactory.imapConfig(setup);
        SmtpServerConfig smtpConfig = ImapAccountFactory.smtpConfig(setup);
        ImapProviderSession session = new ImapProviderSession("", setup.email(),
                ImapAccountFactory.password(setup), imapConfig, smtpConfig, folderTypes,
                setup.settings());
        session.connect();
        return session;
    }

    @Override
    public ProviderSession openSession(Account account, AccountSetup setup) {
        ImapAccountFactory.validate(setup);
        ImapServerConfig imapConfig = ImapAccountFactory.imapConfig(setup);
        SmtpServerConfig smtpConfig = ImapAccountFactory.smtpConfig(setup);
        ImapProviderSession session = new ImapProviderSession(account.id(), setup.email(),
                ImapAccountFactory.password(setup), imapConfig, smtpConfig, folderTypes,
                setup.settings());
        session.connect();
        return session;
    }
}
