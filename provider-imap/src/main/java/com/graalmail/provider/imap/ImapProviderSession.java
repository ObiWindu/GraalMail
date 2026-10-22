/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.core.domain.Attachment;
import jakarta.mail.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.domain.SyncResult;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AttachmentService;
import com.graalmail.provider.api.MailboxService;
import com.graalmail.provider.api.MessageService;
import com.graalmail.provider.api.MessageSubmissionService;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.SearchService;
import com.graalmail.provider.api.SendResult;
import com.graalmail.provider.api.SynchronizationService;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A session backed by a standard IMAP and SMTP server.
 *
 * <p>The session keeps a single IMAP connection open and opens short lived
 * SMTP connections for every submission, so a failed submission never leaves a
 * broken state behind.</p>
 */
public final class ImapProviderSession implements ProviderSession {

    private final String accountId;
    private final String email;
    private final String password;
    private final ImapServerConfig imapConfig;
    private final SmtpServerConfig smtpConfig;
    private final FolderTypeResolver folderTypes;
    private final Map<String, String> settings;
    private final long maxAttachmentBytes;
    private final java.util.function.Supplier<String> secretSupplier;

    private Store store;
    private Session mailSession;
    private final Map<String, Folder> openFolders = new java.util.HashMap<>();

    /**
     * Creates a session.
     *
     * @param accountId the account id
     * @param email the account email address
     * @param password the plain password, may be {@code null}
     * @param imapConfig the IMAP configuration
     * @param smtpConfig the SMTP configuration
     * @param folderTypes the folder role resolver
     * @param settings the account settings
     */
    public ImapProviderSession(String accountId, String email, String password,
                               ImapServerConfig imapConfig, SmtpServerConfig smtpConfig,
                               FolderTypeResolver folderTypes, java.util.Map<String, String> settings) {
        this(accountId, email, password, imapConfig, smtpConfig, folderTypes, settings, null);
    }

    /**
     * Creates a session whose secret is supplied on demand.
     *
     * <p>The supplier is used for OAuth based accounts: it returns the current
     * access token and refreshes it when it expired.</p>
     *
     * @param accountId the account id
     * @param email the account email address
     * @param password the plain password, may be {@code null}
     * @param imapConfig the IMAP configuration
     * @param smtpConfig the SMTP configuration
     * @param folderTypes the folder role resolver
     * @param settings the account settings
     * @param secretSupplier supplies the current secret, may be {@code null}
     */
    public ImapProviderSession(String accountId, String email, String password,
                               ImapServerConfig imapConfig, SmtpServerConfig smtpConfig,
                               FolderTypeResolver folderTypes, java.util.Map<String, String> settings,
                               java.util.function.Supplier<String> secretSupplier) {
        this.accountId = accountId == null ? "" : accountId;
        this.email = email == null ? "" : email;
        this.password = password;
        this.imapConfig = imapConfig;
        this.smtpConfig = smtpConfig;
        this.folderTypes = folderTypes == null ? ImapFolderTypes.DEFAULT : folderTypes;
        this.settings = settings == null ? java.util.Map.of() : java.util.Map.copyOf(settings);
        this.maxAttachmentBytes = ImapSettings.longValue(settings, ImapSettings.ATTACHMENT_MAX_BYTES,
                ImapSettings.DEFAULT_MAX_ATTACHMENT_BYTES);
        this.secretSupplier = secretSupplier;
    }

    /**
     * Opens the IMAP connection.
     *
     * @throws ProviderException when the server cannot be reached or the
     *         credentials are rejected
     */
    public void connect() {
        try {
            String secret = currentSecret();
            Session session = MailSessions.imap(imapConfig, secret);
            mailSession = session;
            store = session.getStore("imap");
            store.connect(imapConfig.host(), imapConfig.port(), imapConfig.username(), secret);
        } catch (MessagingException | RuntimeException e) {
            store = null;
            throw ImapErrorMapper.map(e, "connect to the IMAP server");
        }
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
        for (Folder folder : openFolders.values()) {
            try {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            } catch (MessagingException | RuntimeException ignored) {
                // the store is closed anyway
            }
        }
        openFolders.clear();
        if (store != null) {
            try {
                store.close();
            } catch (MessagingException | RuntimeException ignored) {
                // nothing to do
            }
            store = null;
        }
    }

    private String currentSecret() {
        if (secretSupplier != null) {
            String value = secretSupplier.get();
            return value == null ? "" : value;
        }
        return password == null ? "" : password;
    }

    private Folder folder(String folderId) {
        try {
            jakarta.mail.Folder cached = openFolders.get(folderId);
            if (cached != null && cached.isOpen()) {
                return cached;
            }
            jakarta.mail.Folder folder = store.getFolder(folderId);
            if (!folder.exists()) {
                throw new ProviderException(ErrorCode.MAILBOX_NOT_FOUND,
                        "The mailbox '" + folderId + "' does not exist on the server.", false);
            }
            folder.open(Folder.READ_WRITE);
            openFolders.put(folderId, folder);
            return folder;
        } catch (ProviderException e) {
            throw e;
        } catch (MessagingException | RuntimeException e) {
            throw ImapErrorMapper.map(e, "open the mailbox '" + folderId + "'");
        }
    }

    private String folderIdOf(jakarta.mail.Folder folder) {
        String name = folder.getFullName();
        return name == null || name.isBlank() ? folder.getName() : name;
    }

    private String messageIdOf(String folderId, long uid) {
        return folderId + "|" + uid;
    }

    private static final class MessageId {
        private final String folderId;
        private final long uid;

        private MessageId(String folderId, long uid) {
            this.folderId = folderId;
            this.uid = uid;
        }

        static MessageId parse(String messageId) {
            int index = messageId.lastIndexOf('|');
            if (index <= 0 || index == messageId.length() - 1) {
                throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                        "The message reference is not valid.", false);
            }
            String folder = messageId.substring(0, index);
            long uid;
            try {
                uid = Long.parseLong(messageId.substring(index + 1));
            } catch (NumberFormatException e) {
                throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                        "The message reference is not valid.", false, e);
            }
            return new MessageId(folder, uid);
        }
    }

    private final class Mailboxes implements MailboxService {

        @Override
        public List<com.graalmail.core.domain.Folder> listFolders() {
            try {
                List<com.graalmail.core.domain.Folder> folders = new ArrayList<>();
                jakarta.mail.Folder defaultFolder = store.getDefaultFolder();
                boolean defaultAdded = false;
                for (String pattern : new String[]{"%", "*"}) {
                    for (jakarta.mail.Folder candidate : defaultFolder.list(pattern)) {
                        if (candidate.getType() != Folder.HOLDS_FOLDERS) {
                            continue;
                        }
                        if (folderIdOf(candidate).equals(folderIdOf(defaultFolder))) {
                            defaultAdded = true;
                        }
                        folders.add(toFolder(candidate));
                    }
                    if (!folders.isEmpty()) {
                        break;
                    }
                }
                if (!defaultAdded) {
                    folders.add(toFolder(defaultFolder));
                }
                return folders;
            } catch (MessagingException | RuntimeException e) {
                throw ImapErrorMapper.map(e, "list the mailboxes");
            }
        }

        @Override
        public MessageListPage listMessages(String folderId, int page, int pageSize) {
            int safePage = Math.max(page, 0);
            int safeSize = pageSize <= 0 ? 50 : pageSize;
            int index = safePage * safeSize;
            try {
                jakarta.mail.Folder folder = folder(folderId);
                jakarta.mail.Message[] messages = folder.getMessages();
                List<Message> result = new ArrayList<>();
                int end = Math.max(messages.length - index, 0);
                int start = Math.max(end - safeSize, 0);
                for (int i = end - 1; i >= start; i--) {
                    jakarta.mail.Message message = messages[i];
                    long uid = uidOf(folder, message);
                    result.add(toMessage(folder, message, uid, false));
                }
                return new MessageListPage(result, index > 0, folder.getMessageCount());
            } catch (MessagingException | RuntimeException e) {
                throw ImapErrorMapper.map(e, "read the messages of '" + folderId + "'");
            }
        }

        @Override
        public com.graalmail.core.domain.Folder refreshCounts(String folderId) {
            try {
                jakarta.mail.Folder folder = folder(folderId);
                expunge(folder);
                int unread = folder.getUnreadMessageCount();
                int total = folder.getMessageCount();
                return new com.graalmail.core.domain.Folder(folderId, folder.getName(),
                        fullName(folder), folderType(folder),
                        total, unread, true, false, "");
            } catch (MessagingException | RuntimeException e) {
                throw ImapErrorMapper.map(e, "refresh the counters of '" + folderId + "'");
            }
        }
    }

    private final class Messages implements MessageService {

        @Override
        public Message getMessage(String messageId, boolean includeBody) {
            MessageId id = MessageId.parse(messageId);
            try {
                jakarta.mail.Folder folder = folder(id.folderId);
                jakarta.mail.Message message = messageById(folder, id.uid);
                if (message == null) {
                    throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                            "The message no longer exists on the server.", false);
                }
                return toMessage(folder, message, id.uid, includeBody);
            } catch (ProviderException e) {
                throw e;
            } catch (MessagingException | RuntimeException e) {
                throw ImapErrorMapper.map(e, "read the message");
            }
        }

        @Override
        public void setRead(String messageId, boolean read) {
            MessageId id = MessageId.parse(messageId);
            try {
                jakarta.mail.Folder folder = folder(id.folderId);
                jakarta.mail.Message message = messageById(folder, id.uid);
                if (message == null) {
                    throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                            "The message no longer exists on the server.", false);
                }
                message.setFlag(Flags.Flag.SEEN, read);
            } catch (ProviderException e) {
                throw e;
            } catch (MessagingException | RuntimeException e) {
                throw ImapErrorMapper.map(e, "change the read state of the message");
            }
        }

        @Override
        public void setFlagged(String messageId, boolean flagged) {
            MessageId id = MessageId.parse(messageId);
            try {
                jakarta.mail.Folder folder = folder(id.folderId);
                jakarta.mail.Message message = messageById(folder, id.uid);
                if (message == null) {
                    throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                            "The message no longer exists on the server.", false);
                }
                message.setFlag(Flags.Flag.FLAGGED, flagged);
            } catch (ProviderException e) {
                throw e;
            } catch (MessagingException | RuntimeException e) {
                throw ImapErrorMapper.map(e, "change the flagged state of the message");
            }
        }

        @Override
        public void move(String messageId, String targetFolderId) {
            MessageId id = MessageId.parse(messageId);
            try {
                jakarta.mail.Folder source = folder(id.folderId);
                jakarta.mail.Message message = messageById(source, id.uid);
                if (message == null) {
                    throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                            "The message no longer exists on the server.", false);
                }
                jakarta.mail.Folder target = folder(targetFolderId);
                source.copyMessages(new jakarta.mail.Message[]{message}, target);
                message.setFlag(Flags.Flag.DELETED, true);
                expunge(source);
            } catch (ProviderException e) {
                throw e;
            } catch (MessagingException | RuntimeException e) {
                throw ImapErrorMapper.map(e, "move the message");
            }
        }

        @Override
        public void delete(String messageId) {
            MessageId id = MessageId.parse(messageId);
            try {
                jakarta.mail.Folder folder = folder(id.folderId);
                jakarta.mail.Message message = messageById(folder, id.uid);
                if (message == null) {
                    return;
                }
                message.setFlag(Flags.Flag.DELETED, true);
                expunge(folder);
            } catch (MessagingException | RuntimeException e) {
                throw ImapErrorMapper.map(e, "delete the message");
            }
        }

        @Override
        public void markSpam(String messageId) {
            move(messageId, spamFolder());
        }

        @Override
        public void markNotSpam(String messageId) {
            move(messageId, inboxFolder());
        }
    }

    private final class Submission implements MessageSubmissionService {

        @Override
        public SendResult send(MessageDraft draft) {
            byte[] raw = MailMime.build(draft, email);
            List<String> recipients = new ArrayList<>();
            draft.to().forEach(address -> recipients.add(address.email()));
            draft.cc().forEach(address -> recipients.add(address.email()));
            draft.bcc().forEach(address -> recipients.add(address.email()));
            try {
                String secret = currentSecret();
                Session session = MailSessions.smtp(smtpConfig, secret);
                jakarta.mail.Transport transport = session.getTransport("smtp");
                transport.connect(smtpConfig.host(), smtpConfig.port(), smtpConfig.username(), secret);
                MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(raw));
                // The blind carbon copy header must not be transmitted.
                message.setHeader("Bcc", null);
                List<jakarta.mail.Address> envelope = new ArrayList<>();
                for (String recipient : recipients) {
                    try {
                        envelope.add(new jakarta.mail.internet.InternetAddress(recipient));
                    } catch (jakarta.mail.internet.AddressException e) {
                        throw new ProviderException(ErrorCode.INVALID_RECIPIENT,
                                "The recipient address '" + recipient + "' is not valid.", false, e);
                    }
                }
                transport.sendMessage(message, envelope.toArray(new jakarta.mail.Address[0]));
                transport.close();
                String sentFolder = sentFolderAfterSend();
                if (!sentFolder.isBlank()) {
                    appendToSentFolder(sentFolder, raw);
                }
                return SendResult.accepted("");
            } catch (MessagingException e) {
                throw ImapErrorMapper.map(e, "submit the message");
            } catch (RuntimeException e) {
                throw ImapErrorMapper.map(e, "submit the message");
            }
        }

        @Override
        public void saveDraft(MessageDraft draft) {
            String draftsFolder = draftsFolder();
            if (draftsFolder.isBlank()) {
                throw new ProviderException(ErrorCode.FEATURE_UNSUPPORTED,
                        "The server does not advertise a drafts folder, so drafts cannot be saved "
                                + "on the server. They are kept locally instead.", false);
            }
            try {
                jakarta.mail.Folder folder = folder(draftsFolder);
                MimeMessage message = new MimeMessage(mailSession);
                message.setFrom(new jakarta.mail.internet.InternetAddress(email));
                for (com.graalmail.core.domain.Address recipient : draft.to()) {
                    message.addRecipient(RecipientType.TO,
                            new jakarta.mail.internet.InternetAddress(recipient.email()));
                }
                message.setSubject(draft.subject() == null ? "" : draft.subject(), "UTF-8");
                message.setText(draft.bodyText() == null ? "" : draft.bodyText(), "UTF-8");
                message.setFlag(Flags.Flag.DRAFT, true);
                folder.appendMessages(new jakarta.mail.Message[]{message});
            } catch (MessagingException | RuntimeException e) {
                throw ImapErrorMapper.map(e, "save the draft");
            }
        }
    }

    private void appendToSentFolder(String sentFolder, byte[] raw) {
        try {
            jakarta.mail.Folder folder = folder(sentFolder);
            MimeMessage message = new MimeMessage(mailSession);
            message.setContent(raw, "message/rfc822");
            folder.appendMessages(new jakarta.mail.Message[]{message});
        } catch (MessagingException | RuntimeException e) {
            throw ImapErrorMapper.map(e, "copy the sent message to the sent folder");
        }
    }

    private String sentFolderAfterSend() {
        if (!Boolean.parseBoolean(settings.getOrDefault("sent.appendAfterSend", "false"))) {
            return "";
        }
        String configured = ImapSettings.text(settings, ImapSettings.SENT_FOLDER, "");
        if (!configured.isBlank()) {
            return configured;
        }
        return findFolderByType(FolderType.SENT);
    }

    private final class Attachments implements AttachmentService {

        @Override
        public InputStream open(Attachment attachment) {
            String id = attachment.id();
            int index = id.lastIndexOf('#');
            if (index < 0) {
                throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                        "The attachment reference is not valid.", false);
            }
            String messageId = id.substring(0, index);
            int part = parseIndex(id.substring(index + 1));
            try {
                byte[] raw = rawMessage(messageId);
                List<Part> parts = MailMime.bodyParts(raw);
                if (part < 0 || part >= parts.size()) {
                    throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                            "The attachment is no longer part of the message.", false);
                }
                return new ByteArrayInputStream(MailMime.readContent(parts.get(part)));
            } catch (MessagingException | IOException e) {
                throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                        "The attachment could not be read.", false, e);
            }
        }

        @Override
        public void download(Attachment attachment, Path target) {
            try (InputStream in = open(attachment)) {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                        "The attachment could not be saved.", false, e);
            }
        }

        @Override
        public long maxAttachmentSize() {
            return maxAttachmentBytes;
        }
    }

    private final class Search implements SearchService {

        @Override
        public List<Message> search(SearchQuery query) {
            try {
                List<Message> results = new ArrayList<>();
                for (jakarta.mail.Folder candidate : store.getDefaultFolder().list("%")) {
                    if (candidate.getType() != Folder.HOLDS_FOLDERS) {
                        continue;
                    }
                    String folderId = folderIdOf(candidate);
                    jakarta.mail.Folder folder = folder(folderId);
                    jakarta.mail.search.SearchTerm term = buildTerm(query);
                    jakarta.mail.Message[] messages = folder.search(term);
                    int added = 0;
                    for (jakarta.mail.Message message : messages) {
                        if (added >= 100) {
                            break;
                        }
                        results.add(toMessage(folder, message, uidOf(folder, message), false));
                        added++;
                    }
                }
                return results;
            } catch (MessagingException | RuntimeException e) {
                throw ImapErrorMapper.map(e, "search the mailboxes");
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
                jakarta.mail.Folder folder = folder(folderId);
                expunge(folder);
                return new SyncResult(folderId, 0, 0, 0, java.time.Instant.now(), List.of());
            } catch (RuntimeException e) {
                throw ImapErrorMapper.map(e, "synchronize the mailbox '" + folderId + "'");
            }
        }

        @Override
        public java.util.List<SyncResult> synchronizeAll() {
            List<SyncResult> results = new ArrayList<>();
            for (com.graalmail.core.domain.Folder folder : mailboxes().listFolders()) {
                results.add(synchronize(folder.id()));
            }
            return results;
        }
    }

    private jakarta.mail.search.SearchTerm buildTerm(SearchQuery query) {
        List<jakarta.mail.search.SearchTerm> terms = new ArrayList<>();
        if (!query.text().isBlank()) {
            terms.add(new jakarta.mail.search.BodyTerm(query.text()));
        }
        if (!query.from().isBlank()) {
            terms.add(new jakarta.mail.search.FromStringTerm(query.from()));
        }
        if (!query.to().isBlank()) {
            terms.add(new jakarta.mail.search.RecipientStringTerm(RecipientType.TO, query.to()));
        }
        if (!query.subject().isBlank()) {
            terms.add(new jakarta.mail.search.SubjectTerm(query.subject()));
        }
        if (query.unreadOnly()) {
            terms.add(new jakarta.mail.search.FlagTerm(new Flags(Flags.Flag.SEEN), false));
        }
        if (query.flaggedOnly()) {
            terms.add(new jakarta.mail.search.FlagTerm(new Flags(Flags.Flag.FLAGGED), true));
        }
        if (terms.isEmpty()) {
            return new jakarta.mail.search.OrTerm(new jakarta.mail.search.SearchTerm[0]);
        }
        return new jakarta.mail.search.AndTerm(terms.toArray(new jakarta.mail.search.SearchTerm[0]));
    }

    private Message toMessage(jakarta.mail.Folder folder, jakarta.mail.Message message, long uid,
                               boolean includeBody) {
        try {
            String folderId = folderIdOf(folder);
            Message.Builder builder = Message.builder(messageIdOf(folderId, uid))
                    .accountId(accountId)
                    .folderId(folderId)
                    .subject(message.getSubject() == null ? "" : message.getSubject())
                    .from(first(addresses(message.getFrom())))
                    .to(addresses(message.getRecipients(RecipientType.TO)))
                    .cc(addresses(message.getRecipients(RecipientType.CC)))
                    .bcc(addresses(message.getRecipients(RecipientType.BCC)))
                    .replyTo(addresses(message.getReplyTo()))
                    .unread(!message.isSet(Flags.Flag.SEEN))
                    .flagged(message.isSet(Flags.Flag.FLAGGED))
                    .answered(message.isSet(Flags.Flag.ANSWERED))
                    .draft(message.isSet(Flags.Flag.DRAFT))
                    .snippet(snippet(message))
                    .size(message.getSize());
            if (message.getSentDate() != null) {
                builder.date(message.getSentDate().toInstant());
            }
            if (includeBody) {
                byte[] raw = rawOf(message);
                Message parsed = MailMime.parse(accountId, folderId,
                        messageIdOf(folderId, uid), raw, messageIdOf(folderId, uid) + "#");
                builder.bodyText(parsed.bodyText());
                builder.bodyHtml(parsed.bodyHtml());
                builder.attachments(parsed.attachments());
                builder.attachment(parsed.hasAttachments());
                builder.headers(parsed.headers());
            }
            return builder.build();
        } catch (MessagingException | IOException | RuntimeException e) {
            throw ImapErrorMapper.map(e, "read the message");
        }
    }

    private static String snippet(jakarta.mail.Message message) {
        try {
            String body = bodyText(message);
            if (body == null || body.isBlank()) {
                return "";
            }
            String collapsed = body.replaceAll("\\s+", " ").trim();
            return collapsed.length() > 120 ? collapsed.substring(0, 117) + "..." : collapsed;
        } catch (MessagingException | IOException | RuntimeException e) {
            return "";
        }
    }

    private static String bodyText(jakarta.mail.Message message) throws IOException, MessagingException {
        Object content = message.getContent();
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                Part part = multipart.getBodyPart(i);
                Object partContent = part.getContent();
                if (partContent instanceof String text
                        && String.valueOf(part.getContentType()).toLowerCase(Locale.ROOT)
                        .startsWith("text/plain")) {
                    return text;
                }
            }
            return "";
        }
        return "";
    }

    private byte[] rawMessage(String messageId) throws MessagingException, IOException {
        MessageId id = MessageId.parse(messageId);
        jakarta.mail.Folder folder = folder(id.folderId);
        jakarta.mail.Message message = messageById(folder, id.uid);
        if (message == null) {
            throw new ProviderException(ErrorCode.MESSAGE_NOT_FOUND,
                    "The message no longer exists on the server.", false);
        }
        return rawOf(message);
    }

    private static byte[] rawOf(jakarta.mail.Message message) throws MessagingException, IOException {
        if (message instanceof MimeMessage mime) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            mime.writeTo(out);
            return out.toByteArray();
        }
        throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                "The server returned a message the client cannot read.", false);
    }

    private static com.graalmail.core.domain.Address first(
            List<com.graalmail.core.domain.Address> addresses) {
        return addresses.isEmpty() ? new com.graalmail.core.domain.Address("", "") : addresses.get(0);
    }

    private static List<com.graalmail.core.domain.Address> addresses(Address[] addresses) {
        List<com.graalmail.core.domain.Address> result = new ArrayList<>();
        if (addresses == null) {
            return result;
        }
        for (Address address : addresses) {
            if (address instanceof jakarta.mail.internet.InternetAddress internet) {
                result.add(new com.graalmail.core.domain.Address(
                        internet.getPersonal() == null ? "" : internet.getPersonal(),
                        internet.getAddress()));
            } else {
                result.add(new com.graalmail.core.domain.Address("", address.toString()));
            }
        }
        return result;
    }

    private com.graalmail.core.domain.Folder toFolder(jakarta.mail.Folder folder) {
        String id = folderIdOf(folder);
        int total = 0;
        int unread = 0;
        try {
            total = folder.getMessageCount();
            unread = folder.getUnreadMessageCount();
        } catch (MessagingException | RuntimeException ignored) {
            // counters are optional, the mailbox is still listed
        }
        return new com.graalmail.core.domain.Folder(id, folder.getName(), fullName(folder),
                folderType(folder), total, unread, true, false, "");
    }

    private String fullName(jakarta.mail.Folder folder) {
        String full = folder.getFullName();
        return full == null ? "" : full;
    }

    private FolderType folderType(jakarta.mail.Folder folder) {
        List<String> attributes = new ArrayList<>();
        try {
            if (folder instanceof org.eclipse.angus.mail.imap.IMAPFolder imapFolder) {
                String[] special = imapFolder.getAttributes();
                if (special != null) {
                    attributes.addAll(List.of(special));
                }
            }
        } catch (MessagingException | RuntimeException ignored) {
            // some servers do not report attributes, the name is used as fallback
        }
        return folderTypes.resolve(folder.getFullName(), attributes);
    }

    private String spamFolder() {
        String configured = ImapSettings.text(settings, ImapSettings.SPAM_FOLDER, "");
        if (!configured.isBlank()) {
            return configured;
        }
        return findFolderByType(FolderType.SPAM);
    }

    private String inboxFolder() {
        String configured = ImapSettings.text(settings, ImapSettings.INBOX_FOLDER, "");
        if (!configured.isBlank()) {
            return configured;
        }
        return findFolderByType(FolderType.INBOX);
    }

    private String draftsFolder() {
        String configured = ImapSettings.text(settings, ImapSettings.DRAFTS_FOLDER, "");
        if (!configured.isBlank()) {
            return configured;
        }
        return findFolderByType(FolderType.DRAFTS);
    }

    private String findFolderByType(FolderType type) {
        try {
            for (jakarta.mail.Folder candidate : store.getDefaultFolder().list("%")) {
                if (candidate.getType() == Folder.HOLDS_FOLDERS && folderType(candidate) == type) {
                    return folderIdOf(candidate);
                }
            }
        } catch (MessagingException | RuntimeException ignored) {
            // the caller reports a clear error
        }
        return "";
    }

    private static void expunge(Folder folder) {
        try {
            folder.expunge();
        } catch (MessagingException | RuntimeException ignored) {
            // the flags stay set, the server removes the message later
        }
    }

    private static long uidOf(Folder folder, jakarta.mail.Message message) {
        try {
            if (folder instanceof org.eclipse.angus.mail.imap.IMAPFolder imapFolder) {
                return imapFolder.getUID(message);
            }
            return sequenceNumber(folder, message);
        } catch (MessagingException | RuntimeException e) {
            return 0;
        }
    }

    private static long sequenceNumber(Folder folder, jakarta.mail.Message message)
            throws MessagingException {
        jakarta.mail.Message[] all = folder.getMessages();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == message) {
                return i + 1;
            }
        }
        return 0;
    }

    private static jakarta.mail.Message messageById(Folder folder, long id) throws MessagingException {
        if (folder instanceof org.eclipse.angus.mail.imap.IMAPFolder imapFolder) {
            return imapFolder.getMessageByUID(id);
        }
        return folder.getMessage((int) id);
    }

    private static int parseIndex(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                    "The attachment reference is not valid.", false, e);
        }
    }
}
