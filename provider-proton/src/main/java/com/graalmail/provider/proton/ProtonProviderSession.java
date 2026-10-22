/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Attachment;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.domain.SyncResult;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AttachmentService;
import com.graalmail.provider.api.MailboxService;
import com.graalmail.provider.api.MessageService;
import com.graalmail.provider.api.MessageSubmissionService;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.SearchService;
import com.graalmail.provider.api.SendResult;
import com.graalmail.provider.api.SynchronizationService;
import com.graalmail.provider.proton.internal.ImapClient;
import com.graalmail.provider.proton.internal.MimeParser;
import com.graalmail.provider.proton.internal.SmtpClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A session against the local Proton Mail Bridge. Mail access uses the Bridge
 * IMAP endpoint, sending uses the Bridge SMTP endpoint.
 */
public final class ProtonProviderSession implements ProviderSession {

    private static final DateTimeFormatter INTERNAL_DATE =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final AccountSetup setup;
    private final ProtonBridgeConfig config;
    private final String accountId;
    private final String senderAddress;

    private ImapClient imap;
    private SmtpClient smtp;

    ProtonProviderSession(AccountSetup setup, ProtonBridgeConfig config) {
        this.setup = setup;
        this.config = config;
        this.accountId = setup.email();
        this.senderAddress = setup.email();
    }

    @Override
    public MailboxService mailboxes() {
        return new Mailboxes();
    }

    @Override
    public MessageService messages() {
        return new Messages();
    }

    @Override
    public MessageSubmissionService submission() {
        return new Submission();
    }

    @Override
    public AttachmentService attachments() {
        return new Attachments();
    }

    @Override
    public SearchService search() {
        return new Search();
    }

    @Override
    public SynchronizationService synchronization() {
        return new Synchronization();
    }

    @Override
    public void close() {
        if (imap != null) {
            imap.close();
            imap = null;
        }
        if (smtp != null) {
            smtp.close();
            smtp = null;
        }
    }

    private ImapClient imap() {
        if (imap == null) {
            imap = new ImapClient(config.bridgeHost(), config.imapPort(), config.sslContext(),
                    config.tlsMode() == ProtonBridgeConfig.TlsMode.STARTTLS,
                    (int) config.connectTimeout().toMillis(),
                    (int) config.readTimeout().toMillis());
            try {
                imap.connect();
                imap.login(config.bridgeUsername(), config.bridgePassword());
            } catch (IOException e) {
                imap.close();
                imap = null;
                throw new ProviderException(ErrorCode.CONNECTION_REFUSED,
                        "Proton Mail Bridge could not be reached: " + e.getMessage(), true, e);
            } catch (RuntimeException e) {
                imap.close();
                imap = null;
                throw e;
            }
        }
        return imap;
    }

    private SmtpClient smtp() {
        if (smtp == null) {
            smtp = new SmtpClient(config.bridgeHost(), config.smtpPort(), config.sslContext(),
                    config.tlsMode() == ProtonBridgeConfig.TlsMode.STARTTLS,
                    (int) config.connectTimeout().toMillis(),
                    (int) config.readTimeout().toMillis());
            try {
                smtp.connectAndLogin(config.bridgeUsername(), config.bridgePassword());
            } catch (IOException e) {
                smtp.close();
                smtp = null;
                throw new ProviderException(ErrorCode.CONNECTION_REFUSED,
                        "Proton Mail Bridge could not be reached for sending: " + e.getMessage(),
                        true, e);
            }
        }
        return smtp;
    }

    private List<ImapClient.ImapFolder> bridgeFolders() {
        try {
            return imap().listFolders();
        } catch (IOException e) {
            throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                    "The mailboxes could not be read from Proton Mail Bridge: " + e.getMessage(),
                    true, e);
        }
    }

    private Folder mailboxOf(String mailboxName) {
        FolderType type = ProtonFolderMapper.map(mailboxName);
        int[] counts = new int[]{0, 0};
        try {
            counts = imap().status(mailboxName);
        } catch (IOException ignored) {
            // counts stay unknown
        }
        return new Folder(mailboxName, mailboxName, "", type, counts[0], counts[1], true, false,
                "proton-bridge");
    }

    private final class Mailboxes implements MailboxService {

        @Override
        public List<Folder> listFolders() {
            List<Folder> folders = new ArrayList<>();
            for (ImapClient.ImapFolder folder : bridgeFolders()) {
                folders.add(mailboxOf(folder.name()));
            }
            return folders;
        }

        @Override
        public MessageListPage listMessages(String folderId, int page, int pageSize) {
            int size = pageSize <= 0 ? 50 : pageSize;
            int number = page <= 0 ? 1 : page;
            try {
                ImapClient client = imap();
                int exists = client.select(folderId);
                int from = (number - 1) * size + 1;
                int to = Math.min(from + size - 1, exists);
                List<Message> messages = new ArrayList<>();
                if (to >= from) {
                    for (ImapClient.ImapEnvelope envelope : client.fetchEnvelopes(from, to)) {
                        messages.add(toMessage(folderId, envelope));
                    }
                }
                boolean hasMore = to < exists;
                return new MessageListPage(messages, hasMore, exists);
            } catch (IOException e) {
                throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                        "The messages could not be read from Proton Mail Bridge: " + e.getMessage(),
                        true, e);
            }
        }

        @Override
        public Folder refreshCounts(String folderId) {
            return mailboxOf(folderId);
        }
    }

    private final class Messages implements MessageService {

        @Override
        public Message getMessage(String messageId, boolean includeBody) {
            MessageRef ref = MessageRef.parse(messageId);
            try {
                ImapClient client = imap();
                client.select(ref.folder());
                ImapClient.ImapEnvelope envelope = findByUid(client, ref.folder(), ref.uid());
                if (envelope == null) {
                    throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                            "The message could not be found in Proton Mail Bridge", false);
                }
                Message.Builder builder = toMessage(ref.folder(), envelope).toBuilder();
                if (includeBody) {
                    byte[] raw = client.fetchRaw(envelope.sequence());
                    MimeParser.ParsedMessage parsed = MimeParser.parse(raw);
                    builder.bodyText(parsed.bodyText());
                    builder.bodyHtml(parsed.bodyHtml());
                    builder.attachments(toAttachments(parsed, messageId));
                    builder.snippet(snippet(parsed));
                }
                return builder.build();
            } catch (IOException e) {
                throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                        "The message could not be read from Proton Mail Bridge: " + e.getMessage(),
                        true, e);
            }
        }

        @Override
        public void setRead(String messageId, boolean read) {
            changeFlag(messageId, "\\Seen", read);
        }

        @Override
        public void setFlagged(String messageId, boolean flagged) {
            changeFlag(messageId, "\\Flagged", flagged);
        }

        private void changeFlag(String messageId, String flag, boolean add) {
            MessageRef ref = MessageRef.parse(messageId);
            try {
                ImapClient client = imap();
                client.select(ref.folder());
                ImapClient.ImapEnvelope envelope = findByUid(client, ref.folder(), ref.uid());
                if (envelope == null) {
                    throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                            "The message could not be found in Proton Mail Bridge", false);
                }
                client.storeFlags(envelope.sequence(), add, flag);
            } catch (IOException e) {
                throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                        "The message flags could not be changed: " + e.getMessage(), true, e);
            }
        }

        @Override
        public void move(String messageId, String targetFolderId) {
            MessageRef ref = MessageRef.parse(messageId);
            try {
                ImapClient client = imap();
                client.select(ref.folder());
                ImapClient.ImapEnvelope envelope = findByUid(client, ref.folder(), ref.uid());
                if (envelope == null) {
                    throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                            "The message could not be found in Proton Mail Bridge", false);
                }
                client.copy(envelope.sequence(), targetFolderId);
                client.storeFlags(envelope.sequence(), false, "\\Deleted");
                client.expunge();
            } catch (IOException e) {
                throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                        "The message could not be moved: " + e.getMessage(), true, e);
            }
        }

        @Override
        public void delete(String messageId) {
            MessageRef ref = MessageRef.parse(messageId);
            try {
                ImapClient client = imap();
                client.select(ref.folder());
                ImapClient.ImapEnvelope envelope = findByUid(client, ref.folder(), ref.uid());
                if (envelope == null) {
                    throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                            "The message could not be found in Proton Mail Bridge", false);
                }
                client.storeFlags(envelope.sequence(), false, "\\Deleted");
                client.expunge();
            } catch (IOException e) {
                throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                        "The message could not be deleted: " + e.getMessage(), true, e);
            }
        }

        @Override
        public void markSpam(String messageId) {
            move(messageId, folderOfType(FolderType.SPAM, "Spam"));
        }

        @Override
        public void markNotSpam(String messageId) {
            move(messageId, folderOfType(FolderType.INBOX, "INBOX"));
        }
    }

    private final class Submission implements MessageSubmissionService {

        @Override
        public SendResult send(MessageDraft draft) {
            if (!draft.hasRecipients()) {
                throw new ProviderException(ErrorCode.INVALID_RECIPIENT,
                        "The message has no recipient", false);
            }
            List<String> recipients = new ArrayList<>();
            for (Address address : draft.to()) {
                recipients.add(address.email());
            }
            for (Address address : draft.cc()) {
                recipients.add(address.email());
            }
            for (Address address : draft.bcc()) {
                recipients.add(address.email());
            }
            byte[] payload = buildMessage(draft, false);
            try {
                String reply = smtp().send(payload, senderAddress, recipients);
                if (!reply.startsWith("2")) {
                    return SendResult.ambiguous("Proton Mail Bridge answered: " + reply.trim());
                }
                return SendResult.accepted("");
            } catch (IOException e) {
                return SendResult.ambiguous("The connection to Proton Mail Bridge failed: "
                        + e.getMessage());
            }
        }

        @Override
        public void saveDraft(MessageDraft draft) {
            byte[] payload = buildMessage(draft, true);
            String drafts = folderOfType(FolderType.DRAFTS, "Drafts");
            try {
                imap().append(drafts, payload);
            } catch (IOException e) {
                throw new ProviderException(ErrorCode.DRAFT_UNSUPPORTED,
                        "The draft could not be saved through Proton Mail Bridge: " + e.getMessage(),
                        true, e);
            }
        }
    }

    private final class Attachments implements AttachmentService {

        @Override
        public InputStream open(Attachment attachment) {
            byte[] content = readAttachment(attachment);
            return new java.io.ByteArrayInputStream(content);
        }

        @Override
        public void download(Attachment attachment, Path target) {
            byte[] content = readAttachment(attachment);
            try {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(target, content);
            } catch (IOException e) {
                throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                        "The attachment could not be written: " + e.getMessage(), true, e);
            }
        }
    }

    private final class Search implements SearchService {

        @Override
        public List<Message> search(SearchQuery query) {
            List<String> mailboxes = new ArrayList<>();
            if (!query.folderIds().isEmpty()) {
                mailboxes.addAll(query.folderIds());
            } else {
                for (ImapClient.ImapFolder folder : bridgeFolders()) {
                    mailboxes.add(folder.name());
                }
            }
            String criteria = criteria(query);
            List<Message> results = new ArrayList<>();
            try {
                ImapClient client = imap();
                for (String mailbox : mailboxes) {
                    client.select(mailbox);
                    List<Long> uids = client.search(criteria);
                    for (Long uid : uids) {
                        if (results.size() >= query.limit()) {
                            return results;
                        }
                        ImapClient.ImapEnvelope envelope = findByUid(client, mailbox, uid);
                        if (envelope != null) {
                            results.add(toMessage(mailbox, envelope));
                        }
                    }
                }
                return results;
            } catch (IOException e) {
                throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                        "The search through Proton Mail Bridge failed: " + e.getMessage(), true, e);
            }
        }

        @Override
        public boolean isServerSide() {
            return true;
        }
    }

    private final class Synchronization implements SynchronizationService {

        @Override
        public SyncResult synchronize(String folderId) {
            try {
                ImapClient client = imap();
                int exists = client.select(folderId);
                if (exists > 0) {
                    client.fetchEnvelopes(1, exists);
                }
                return new SyncResult(folderId, 0, 0, 0, Instant.now(), List.of());
            } catch (IOException e) {
                return new SyncResult(folderId, 0, 0, 0, Instant.now(),
                        List.of("Proton Mail Bridge could not be synchronized: " + e.getMessage()));
            }
        }

        @Override
        public boolean supportsIdle() {
            return true;
        }
    }

    private String folderOfType(FolderType type, String fallback) {
        for (ImapClient.ImapFolder folder : bridgeFolders()) {
            if (ProtonFolderMapper.map(folder.name()) == type) {
                return folder.name();
            }
        }
        return fallback;
    }

    private ImapClient.ImapEnvelope findByUid(ImapClient client, String mailbox, long uid)
            throws IOException {
        int exists = client.select(mailbox);
        if (exists == 0) {
            return null;
        }
        for (ImapClient.ImapEnvelope envelope : client.fetchEnvelopes(1, exists)) {
            if (envelope.uid() == uid) {
                return envelope;
            }
        }
        return null;
    }

    private Message toMessage(String mailbox, ImapClient.ImapEnvelope envelope) {
        Map<String, String> headers = MimeParser.parseHeaders(envelope.headerBlock());
        Message.Builder builder = Message.builder(messageId(mailbox, envelope.uid()))
                .accountId(accountId)
                .folderId(mailbox)
                .internetMessageId(headers.getOrDefault("message-id", ""))
                .subject(headers.getOrDefault("subject", ""))
                .from(address(headers.getOrDefault("from", "")))
                .to(addresses(headers.getOrDefault("to", "")))
                .cc(addresses(headers.getOrDefault("cc", "")))
                .bcc(addresses(headers.getOrDefault("bcc", "")))
                .replyTo(addresses(headers.getOrDefault("reply-to", "")))
                .date(parseDate(headers.getOrDefault("date", ""), envelope.internalDate()))
                .unread(!envelope.hasFlag("Seen"))
                .flagged(envelope.hasFlag("Flagged"))
                .draft(ProtonFolderMapper.map(mailbox) == FolderType.DRAFTS)
                .answered(envelope.hasFlag("Answered"))
                .size(envelope.size())
                .snippet("");
        return builder.build();
    }

    private List<Attachment> toAttachments(MimeParser.ParsedMessage parsed, String messageId) {
        List<Attachment> attachments = new ArrayList<>();
        for (MimeParser.Part part : parsed.attachments()) {
            attachments.add(new Attachment(messageId + "|" + part.path(), part.fileName(),
                    part.mimeType(), part.size(), part.inline(), part.contentId()));
        }
        return attachments;
    }

    private String snippet(MimeParser.ParsedMessage parsed) {
        String text = parsed.bodyText().isEmpty() ? parsed.bodyHtml() : parsed.bodyText();
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200);
    }

    private byte[] readAttachment(Attachment attachment) {
        String[] parts = attachment.id().split("\\|");
        if (parts.length != 2) {
            throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                    "The attachment reference is invalid", false);
        }
        MessageRef ref = MessageRef.parse(parts[0]);
        try {
            ImapClient client = imap();
            client.select(ref.folder());
            ImapClient.ImapEnvelope envelope = findByUid(client, ref.folder(), ref.uid());
            if (envelope == null) {
                throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                        "The message of the attachment could not be found", false);
            }
            return client.fetchPart(envelope.sequence(), parts[1]);
        } catch (IOException e) {
            throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                    "The attachment could not be read: " + e.getMessage(), true, e);
        }
    }

    private byte[] buildMessage(MessageDraft draft, boolean forDraft) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder headers = new StringBuilder();
        headers.append("From: ").append(senderAddress).append("\r\n");
        if (!draft.to().isEmpty()) {
            headers.append("To: ").append(formatAddresses(draft.to())).append("\r\n");
        }
        if (!draft.cc().isEmpty()) {
            headers.append("Cc: ").append(formatAddresses(draft.cc())).append("\r\n");
        }
        if (!forDraft && !draft.bcc().isEmpty()) {
            headers.append("Bcc: ").append(formatAddresses(draft.bcc())).append("\r\n");
        }
        headers.append("Subject: ").append(draft.subject()).append("\r\n");
        headers.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now()))
                .append("\r\n");
        if (!draft.inReplyTo().isEmpty()) {
            headers.append("In-Reply-To: ").append(draft.inReplyTo()).append("\r\n");
        }
        if (!draft.references().isEmpty()) {
            headers.append("References: ").append(draft.references()).append("\r\n");
        }
        headers.append("MIME-Version: 1.0\r\n");
        headers.append("Content-Type: text/plain; charset=utf-8\r\n");
        headers.append("Content-Transfer-Encoding: 8bit\r\n");
        headers.append("\r\n");
        byte[] headerBytes = headers.toString().getBytes(StandardCharsets.UTF_8);
        out.writeBytes(headerBytes);
        out.writeBytes(draft.bodyText().getBytes(StandardCharsets.UTF_8));
        if (draft.bodyText().isEmpty() && !draft.bodyHtml().isEmpty()) {
            out.writeBytes(draft.bodyHtml().getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static String formatAddresses(List<Address> addresses) {
        StringBuilder sb = new StringBuilder();
        for (Address address : addresses) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(address.format());
        }
        return sb.toString();
    }

    private static Address address(String raw) {
        return Address.parse(raw);
    }

    private static List<Address> addresses(String raw) {
        List<Address> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                result.add(Address.parse(part));
            }
        }
        return result;
    }

    private static Instant parseDate(String header, String internalDate) {
        try {
            return java.time.ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
        } catch (RuntimeException ignored) {
            // fall through to the internal date
        }
        try {
            return LocalDate.parse(internalDate, INTERNAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String criteria(SearchQuery query) {
        List<String> parts = new ArrayList<>();
        if (!query.text().isBlank()) {
            parts.add("TEXT " + quote(query.text()));
        }
        if (!query.from().isBlank()) {
            parts.add("FROM " + quote(query.from()));
        }
        if (!query.to().isBlank()) {
            parts.add("TO " + quote(query.to()));
        }
        if (!query.subject().isBlank()) {
            parts.add("SUBJECT " + quote(query.subject()));
        }
        if (query.since() != null) {
            parts.add("SINCE " + DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
                    .format(query.since().atZone(ZoneOffset.UTC)));
        }
        if (query.until() != null) {
            parts.add("BEFORE " + DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
                    .format(query.until().atZone(ZoneOffset.UTC)));
        }
        if (query.unreadOnly()) {
            parts.add("UNSEEN");
        }
        if (query.flaggedOnly()) {
            parts.add("FLAGGED");
        }
        return parts.isEmpty() ? "ALL" : String.join(" ", parts);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String messageId(String folder, long uid) {
        return folder + ":" + uid;
    }

    /** Reference to a message inside a bridge mailbox. */
    private static final class MessageRef {
        private final String folder;
        private final long uid;

        private MessageRef(String folder, long uid) {
            this.folder = folder;
            this.uid = uid;
        }

        static MessageRef parse(String messageId) {
            int separator = messageId.lastIndexOf(':');
            if (separator <= 0) {
                throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                        "The message reference is invalid", false);
            }
            try {
                return new MessageRef(messageId.substring(0, separator),
                        Long.parseLong(messageId.substring(separator + 1)));
            } catch (NumberFormatException e) {
                throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                        "The message reference is invalid", false, e);
            }
        }

        String folder() { return folder; }
        long uid() { return uid; }
    }
}
