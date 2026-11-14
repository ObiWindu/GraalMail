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
import com.graalmail.core.domain.Message;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderSession;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import java.util.List;
import java.util.Properties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test of folder management and raw message export against a
 * local GreenMail server.
 */
class ImapFolderManagementTest {

    private static final String EMAIL = "user@example.com";
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
    void createsRenamesAndDeletesFolders() throws Exception {
        server.setUser(EMAIL, PASSWORD);
        try (ProviderSession session = openSession()) {
            Folder created = session.mailboxes().createFolder(null, "Projects");
            assertEquals("Projects", created.name());
            assertTrue(session.mailboxes().listFolders().stream()
                    .anyMatch(f -> f.name().equals("Projects")));

            session.mailboxes().renameFolder(created.id(), "Work");
            Folder renamed = session.mailboxes().listFolders().stream()
                    .filter(f -> f.name().equals("Work"))
                    .findFirst().orElseThrow();
            assertFalse(session.mailboxes().listFolders().stream()
                    .anyMatch(f -> f.name().equals("Projects")));

            session.mailboxes().deleteFolder(renamed.id());
            assertFalse(session.mailboxes().listFolders().stream()
                    .anyMatch(f -> f.name().equals("Work")));
        }
    }

    @Test
    void creatingAnExistingFolderFailsCleanly() throws Exception {
        server.setUser(EMAIL, PASSWORD);
        try (ProviderSession session = openSession()) {
            session.mailboxes().createFolder(null, "Dup");
            ProviderException e = assertThrows(ProviderException.class,
                    () -> session.mailboxes().createFolder(null, "Dup"));
            assertEquals(ErrorCode.CONFIGURATION_ERROR, e.code());
        }
    }

    @Test
    void emptyFolderRemovesAllMessages() throws Exception {
        server.setUser(EMAIL, PASSWORD);
        seedMessage("One");
        seedMessage("Two");
        try (ProviderSession session = openSession()) {
            Folder inbox = session.mailboxes().listFolders().stream()
                    .filter(f -> f.name().equalsIgnoreCase("INBOX"))
                    .findFirst().orElseThrow();
            assertEquals(2, session.mailboxes().listMessages(inbox.id(), 0, 50).messages().size());
            session.mailboxes().emptyFolder(inbox.id());
            assertEquals(0, session.mailboxes().listMessages(inbox.id(), 0, 50).messages().size());
        }
    }

    @Test
    void exportsTheRawRfc822Source() throws Exception {
        server.setUser(EMAIL, PASSWORD);
        seedMessage("Export me");
        try (ProviderSession session = openSession()) {
            Message listed = session.mailboxes().listMessages("INBOX", 0, 20).messages().get(0);
            byte[] raw = session.messages().rawMessage(listed.id());
            assertNotNull(raw);
            String source = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(source.contains("Subject: Export me"), "the raw source keeps the headers");
        }
    }

    private ProviderSession openSession() {
        AccountSetup setup = AccountSetup.builder(AuthMethod.PASSWORD)
                .email(EMAIL)
                .password(EMAIL, PASSWORD)
                .setting(ImapSettings.IMAP_HOST, "127.0.0.1")
                .setting(ImapSettings.IMAP_PORT, String.valueOf(server.getImap().getPort()))
                .setting(ImapSettings.IMAP_SECURITY, "none")
                .setting(ImapSettings.SMTP_HOST, "127.0.0.1")
                .setting(ImapSettings.SMTP_PORT, String.valueOf(server.getSmtp().getPort()))
                .setting(ImapSettings.SMTP_SECURITY, "none")
                .setting(ImapSettings.USERNAME, EMAIL)
                .build();
        return new GenericImapProvider().openSession(setup);
    }

    private void seedMessage(String subject) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(EMAIL);
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, EMAIL);
        message.setSubject(subject);
        message.setText("The body of " + subject + ".");
        message.saveChanges();
        Properties properties = new Properties();
        properties.put("mail.smtp.host", "127.0.0.1");
        properties.put("mail.smtp.port", String.valueOf(server.getSmtp().getPort()));
        properties.put("mail.smtp.starttls.enable", "false");
        Session smtpSession = Session.getInstance(properties);
        jakarta.mail.Transport transport = smtpSession.getTransport("smtp");
        transport.connect("127.0.0.1", server.getSmtp().getPort(), EMAIL, PASSWORD);
        transport.sendMessage(message, message.getAllRecipients());
        transport.close();
        server.waitForIncomingEmail(5000, 1);
    }
}
