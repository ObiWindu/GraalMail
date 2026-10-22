/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test of the generic IMAP provider against a local GreenMail
 * server. The test uses a real IMAP and SMTP conversation, so it verifies the
 * protocol handling, not a mock.
 */
class GenericImapProviderTest {

    private static final String PASSWORD = "test-password";

    private GreenMail server;

    @BeforeEach
    void startServer() throws Exception {
        server = new GreenMail(ServerSetup.dynamicPort(
                new ServerSetup[]{ServerSetup.SMTP, ServerSetup.IMAP}));
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void listsMailboxesAndReadsMessages() throws Exception {
        String email = "user@example.com";
        server.setUser(email, PASSWORD);
        seedMessage(email, "Hello", "The body of the message.");

        GenericImapProvider provider = new GenericImapProvider();
        var setup = com.graalmail.provider.api.AccountSetup
                .builder(com.graalmail.provider.api.AuthMethod.PASSWORD)
                .email(email)
                .password(email, PASSWORD)
                .setting(ImapSettings.IMAP_HOST, "127.0.0.1")
                .setting(ImapSettings.IMAP_PORT, String.valueOf(server.getSmtp().getPort() + 1))
                .setting(ImapSettings.IMAP_SECURITY, "none")
                .setting(ImapSettings.SMTP_HOST, "127.0.0.1")
                .setting(ImapSettings.SMTP_PORT, String.valueOf(server.getSmtp().getPort()))
                .setting(ImapSettings.SMTP_SECURITY, "none")
                .setting(ImapSettings.USERNAME, email)
                .build();

        // GreenMail allocates the IMAP port separately from the SMTP port.
        int imapPort = server.getImap().getPort();
        setup = com.graalmail.provider.api.AccountSetup
                .builder(com.graalmail.provider.api.AuthMethod.PASSWORD)
                .email(email)
                .password(email, PASSWORD)
                .setting(ImapSettings.IMAP_HOST, "127.0.0.1")
                .setting(ImapSettings.IMAP_PORT, String.valueOf(imapPort))
                .setting(ImapSettings.IMAP_SECURITY, "none")
                .setting(ImapSettings.SMTP_HOST, "127.0.0.1")
                .setting(ImapSettings.SMTP_PORT, String.valueOf(server.getSmtp().getPort()))
                .setting(ImapSettings.SMTP_SECURITY, "none")
                .setting(ImapSettings.USERNAME, email)
                .build();

        try (var session = provider.openSession(setup)) {
            List<Folder> folders = session.mailboxes().listFolders();
            assertFalse(folders.isEmpty(), "the server must expose at least the inbox");

            var page = session.mailboxes().listMessages("INBOX", 0, 20);
            assertEquals(1, page.messages().size());
            Message listed = page.messages().get(0);
            assertEquals("Hello", listed.subject());
            assertTrue(listed.unread(), "a freshly delivered message is unread");

            Message full = session.messages().getMessage(listed.id(), true);
            assertTrue(full.bodyText().contains("The body of the message."));
        }
    }

    @Test
    void marksMessagesAsReadAndFlagged() throws Exception {
        String email = "user@example.com";
        server.setUser(email, PASSWORD);
        seedMessage(email, "Flags", "Body");

        GenericImapProvider provider = new GenericImapProvider();
        var setup = com.graalmail.provider.api.AccountSetup
                .builder(com.graalmail.provider.api.AuthMethod.PASSWORD)
                .email(email)
                .password(email, PASSWORD)
                .setting(ImapSettings.IMAP_HOST, "127.0.0.1")
                .setting(ImapSettings.IMAP_PORT, String.valueOf(server.getImap().getPort()))
                .setting(ImapSettings.IMAP_SECURITY, "none")
                .setting(ImapSettings.SMTP_HOST, "127.0.0.1")
                .setting(ImapSettings.SMTP_PORT, String.valueOf(server.getSmtp().getPort()))
                .setting(ImapSettings.SMTP_SECURITY, "none")
                .setting(ImapSettings.USERNAME, email)
                .build();

        try (var session = provider.openSession(setup)) {
            var page = session.mailboxes().listMessages("INBOX", 0, 20);
            String messageId = page.messages().get(0).id();
            session.messages().setRead(messageId, true);
            session.messages().setFlagged(messageId, true);
            Message updated = session.messages().getMessage(messageId, false);
            assertFalse(updated.unread());
            assertTrue(updated.flagged());
        }
    }

    @Test
    void sendsAMessageThroughSmtp() throws Exception {
        String email = "user@example.com";
        server.setUser(email, PASSWORD);

        GenericImapProvider provider = new GenericImapProvider();
        var setup = com.graalmail.provider.api.AccountSetup
                .builder(com.graalmail.provider.api.AuthMethod.PASSWORD)
                .email(email)
                .password(email, PASSWORD)
                .setting(ImapSettings.IMAP_HOST, "127.0.0.1")
                .setting(ImapSettings.IMAP_PORT, String.valueOf(server.getImap().getPort()))
                .setting(ImapSettings.IMAP_SECURITY, "none")
                .setting(ImapSettings.SMTP_HOST, "127.0.0.1")
                .setting(ImapSettings.SMTP_PORT, String.valueOf(server.getSmtp().getPort()))
                .setting(ImapSettings.SMTP_SECURITY, "none")
                .setting(ImapSettings.USERNAME, email)
                .build();

        com.graalmail.core.domain.MessageDraft draft = com.graalmail.core.domain.MessageDraft
                .builder()
                .subject("Sent by GraalMail")
                .bodyText("Hello from the test")
                .to(List.of(new com.graalmail.core.domain.Address("", "user@example.com")))
                .build();

        try (var session = provider.openSession(setup)) {
            var result = session.submission().send(draft);
            assertTrue(result.accepted());
        }
        assertEquals(1, server.getReceivedMessages().length);
        assertEquals("Sent by GraalMail", server.getReceivedMessages()[0].getSubject());
    }

    @Test
    void rejectsUnencryptedConfigurationOnTheImplicitTlsPort() {
        ProviderException failure = assertThrows(ProviderException.class,
                () -> ImapServerConfig.builder()
                        .host("imap.example.com")
                        .port(993)
                        .securityMode(SecurityMode.NONE)
                        .username("user")
                        .build());
        assertEquals(ErrorCode.CONFIGURATION_ERROR, failure.code());
    }

    @Test
    void reportsTheFolderRolesOfWellKnownMailboxes() {
        assertEquals(FolderType.INBOX, ImapFolderTypes.fromName("INBOX"));
        assertEquals(FolderType.SENT, ImapFolderTypes.fromName("Sent"));
        assertEquals(FolderType.SENT, ImapFolderTypes.fromName("[Gmail]/Sent Mail"));
        assertEquals(FolderType.DRAFTS, ImapFolderTypes.fromName("Drafts"));
        assertEquals(FolderType.TRASH, ImapFolderTypes.fromName("Trash"));
        assertEquals(FolderType.SPAM, ImapFolderTypes.fromName("Junk"));
        assertEquals(FolderType.CUSTOM, ImapFolderTypes.fromName("Projects"));
    }

    @Test
    void exposesTheGenericProviderCapabilities() {
        GenericImapProvider provider = new GenericImapProvider();
        assertEquals(com.graalmail.provider.api.ProviderType.GENERIC, provider.type());
        assertTrue(provider.capabilities().serverSideSearch());
        assertTrue(provider.capabilities().sendCapable());
        assertNotNull(provider.capabilities().platformSupport());
    }

    private void seedMessage(String email, String subject, String body) throws Exception {
        Session session = Session.getInstance(new java.util.Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(email);
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, email);
        message.setSubject(subject);
        message.setText(body);
        message.saveChanges();
        Properties properties = new Properties();
        properties.put("mail.smtp.host", "127.0.0.1");
        properties.put("mail.smtp.port", String.valueOf(server.getSmtp().getPort()));
        properties.put("mail.smtp.starttls.enable", "false");
        Session smtpSession = Session.getInstance(properties);
        jakarta.mail.Transport transport = smtpSession.getTransport("smtp");
        transport.connect("127.0.0.1", server.getSmtp().getPort(), email, PASSWORD);
        transport.sendMessage(message, message.getAllRecipients());
        transport.close();
    }

    /**
     * Keeps the map import meaningful for the settings assertions.
     *
     * @return an empty settings map
     */
    static Map<String, String> emptySettings() {
        return Map.of();
    }
}
