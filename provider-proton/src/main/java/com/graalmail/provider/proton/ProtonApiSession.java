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
import com.graalmail.provider.api.AttachmentService;
import com.graalmail.provider.api.FolderSyncState;
import com.graalmail.provider.api.MailboxService;
import com.graalmail.provider.api.MessageService;
import com.graalmail.provider.api.MessageSubmissionService;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.SearchService;
import com.graalmail.provider.api.SendResult;
import com.graalmail.provider.api.SynchronizationService;
import com.graalmail.provider.proton.api.ProtonApiClient;
import com.graalmail.provider.proton.api.ProtonCrypto;
import com.graalmail.provider.proton.api.ProtonKeyUnlock;
import com.graalmail.provider.proton.api.ProtonMessageCrypto;
import com.graalmail.provider.proton.internal.MimeParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A session against the Proton API with the Proton username and password.
 *
 * <p>Message bodies and subjects are PGP-encrypted on the server and are
 * decrypted locally with the mailbox password, so the server only ever sees
 * ciphertext. Sending mail is not supported yet.</p>
 */
public final class ProtonApiSession implements ProviderSession {

    private static final int STARRED_LABEL = 10;
    private static final List<String> FOLDER_LABELS =
            List.of("0", "3", "4", "6", "7", "8");

    private final String accountId;
    private final ProtonApiClient client;
    private final byte[] mailboxPassword;
    private final String senderAddress;

    private volatile List<ProtonCrypto.UnlockedKey> keys;
    private volatile List<ProtonApiClient.Label> labels;
    /**
     * Newest-first listings per folder, kept so UID-based sync can serve
     * messages without refetching. Proton IDs are strings, so sync UIDs are
     * stable hashes of the IDs (see {@link #uidOf}).
     */
    private final Map<String, Map<Long, ProtonApiClient.Message>> listings = new HashMap<>();

    /** Creates a session; unlock the keys with {@link #unlock()} before use. */
    public ProtonApiSession(String accountId, ProtonApiClient client,
                            byte[] mailboxPassword, String senderAddress) {
        this.accountId = accountId;
        this.client = client;
        this.mailboxPassword = mailboxPassword.clone();
        this.senderAddress = senderAddress;
    }

    /** Unlocks the PGP keys now, failing fast on a wrong mailbox password. */
    public void unlock() {
        ProtonApiClient.User user = client.currentUser();
        Map<String, byte[]> salts = ProtonKeyUnlock.decodeSalts(client.keySalts());
        List<ProtonCrypto.UnlockedKey> userKeys =
                ProtonKeyUnlock.unlock(user.Keys(), salts, mailboxPassword, List.of());
        if (userKeys.isEmpty()) {
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "No Proton key could be unlocked. The mailbox password may be wrong.", false);
        }
        List<ProtonCrypto.UnlockedKey> all = new ArrayList<>(userKeys);
        for (ProtonApiClient.Address address : client.addresses()) {
            all.addAll(ProtonKeyUnlock.unlock(address.Keys(), salts, mailboxPassword, userKeys));
        }
        if (all.isEmpty()) {
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "No Proton address key could be unlocked.", false);
        }
        keys = List.copyOf(all);
        labels = List.copyOf(client.labels());
    }

    private List<ProtonCrypto.UnlockedKey> keys() {
        List<ProtonCrypto.UnlockedKey> unlocked = keys;
        if (unlocked == null) {
            synchronized (this) {
                unlocked = keys;
                if (unlocked == null) {
                    unlock();
                    unlocked = keys;
                }
            }
        }
        return unlocked;
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
        // Stateless HTTPS session; nothing to close.
    }

    private Map<String, ProtonApiClient.LabelCount> counts() {
        Map<String, ProtonApiClient.LabelCount> counts = new HashMap<>();
        try {
            for (ProtonApiClient.LabelCount count : client.messageCounts("")) {
                counts.put(count.LabelID(), count);
            }
        } catch (RuntimeException ignored) {
            // counts stay unknown
        }
        return counts;
    }

    private Folder folderOf(ProtonApiClient.Label label, Map<String, ProtonApiClient.LabelCount> counts) {
        ProtonApiClient.LabelCount count = counts.get(label.ID());
        return new Folder(label.ID(), label.Name().isBlank() ? label.ID() : label.Name(), "",
                folderType(label.ID()), count == null ? 0 : count.Total(),
                count == null ? 0 : count.Unread(), true, false, "proton-api");
    }

    /** Stable sync UID for a Proton message ID. */
    static long uidOf(String protonId) {
        return Integer.toUnsignedLong(protonId.hashCode());
    }

    static FolderType folderType(String labelId) {
        return switch (labelId) {
            case ProtonApiClient.LABEL_INBOX -> FolderType.INBOX;
            case ProtonApiClient.LABEL_DRAFTS -> FolderType.DRAFTS;
            case ProtonApiClient.LABEL_SENT -> FolderType.SENT;
            case ProtonApiClient.LABEL_ARCHIVE -> FolderType.ARCHIVE;
            case ProtonApiClient.LABEL_SPAM -> FolderType.SPAM;
            case ProtonApiClient.LABEL_TRASH -> FolderType.TRASH;
            case ProtonApiClient.LABEL_ALL_MAIL -> FolderType.ALL;
            default -> FolderType.CUSTOM;
        };
    }

    private Message toMessage(String folderId, ProtonApiClient.Message remote) {
        return toMessage(folderId, remote, null);
    }

    private Message toMessage(String folderId, ProtonApiClient.Message remote,
                              MimeParser.ParsedMessage parsed) {
        String subject = parsed != null && !parsed.header("subject").isBlank()
                ? parsed.header("subject")
                : decryptSubject(remote.Subject());
        Message.Builder builder = Message.builder(remote.ID())
                .accountId(accountId)
                .folderId(folderId)
                .internetMessageId(remote.ExternalID())
                .subject(subject)
                .from(address(remote.Sender()))
                .to(addresses(remote.ToList()))
                .cc(addresses(remote.CCList()))
                .bcc(addresses(remote.BCCList()))
                .date(remote.Time() <= 0 ? null : Instant.ofEpochSecond(remote.Time()))
                .unread(remote.unread())
                .flagged(remote.LabelIDs().contains(String.valueOf(STARRED_LABEL)))
                .draft(ProtonApiClient.LABEL_DRAFTS.equals(folderId))
                .size(remote.Size())
                .snippet(parsed == null ? "" : snippet(parsed));
        if (parsed != null) {
            builder.bodyText(parsed.bodyText())
                    .bodyHtml(parsed.bodyHtml())
                    .attachments(toAttachments(remote.ID(), parsed))
                    .attachment(!parsed.attachments().isEmpty());
        } else {
            builder.attachment(remote.NumAttachments() > 0);
        }
        return builder.build();
    }

    private String decryptSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return "";
        }
        if (!subject.contains("PGP MESSAGE")) {
            return subject;
        }
        try {
            byte[] plain = ProtonMessageCrypto.decryptArmored(subject, keys());
            String text = new String(plain, StandardCharsets.UTF_8).strip();
            if (!text.toLowerCase(Locale.ROOT).startsWith("subject:")) {
                return text;
            }
            return text.substring("subject:".length()).strip();
        } catch (RuntimeException e) {
            return "(Encrypted subject)";
        }
    }

    private static String snippet(MimeParser.ParsedMessage parsed) {
        String text = parsed.bodyText().isBlank() ? parsed.bodyHtml() : parsed.bodyText();
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200);
    }

    private static List<Attachment> toAttachments(String messageId, MimeParser.ParsedMessage parsed) {
        List<Attachment> attachments = new ArrayList<>();
        for (MimeParser.Part part : parsed.attachments()) {
            attachments.add(new Attachment(messageId + "|" + part.path(), part.fileName(),
                    part.mimeType(), part.size(), part.inline(), part.contentId()));
        }
        return attachments;
    }

    private static Address address(ProtonApiClient.MessageAddress address) {
        if (address == null) {
            return new Address("", "");
        }
        return new Address(address.Name(), address.Address());
    }

    private static List<Address> addresses(List<ProtonApiClient.MessageAddress> addresses) {
        List<Address> result = new ArrayList<>();
        for (ProtonApiClient.MessageAddress address : addresses) {
            result.add(address(address));
        }
        return result;
    }

    private MimeParser.ParsedMessage decryptedBody(ProtonApiClient.Message remote) {
        if (remote.IsEncrypted() == 0) {
            return MimeParser.parse(remote.Body().getBytes(StandardCharsets.UTF_8));
        }
        byte[] mime = ProtonMessageCrypto.decryptArmored(remote.Body(), keys());
        return MimeParser.parse(mime);
    }

    private final class Mailboxes implements MailboxService {

        @Override
        public List<Folder> listFolders() {
            Map<String, ProtonApiClient.LabelCount> folderCounts = counts();
            List<Folder> folders = new ArrayList<>();
            for (ProtonApiClient.Label label : client.labels()) {
                if (label.Type() == 2) {
                    continue;
                }
                folders.add(folderOf(label, folderCounts));
            }
            return folders;
        }

        @Override
        public MessageListPage listMessages(String folderId, int page, int pageSize) {
            int size = pageSize <= 0 ? 50 : pageSize;
            int number = page <= 0 ? 0 : page - 1;
            List<ProtonApiClient.Message> remote = client.listMessages(folderId, number, size + 1);
            boolean hasMore = remote.size() > size;
            List<Message> messages = new ArrayList<>();
            for (ProtonApiClient.Message item : remote.subList(0, Math.min(size, remote.size()))) {
                messages.add(toMessage(folderId, item));
            }
            return new MessageListPage(messages, hasMore, -1);
        }

        @Override
        public MessageListPage listMessagesByUidRange(String folderId, long startUid, long endUid) {
            Map<Long, ProtonApiClient.Message> stashed;
            synchronized (listings) {
                stashed = listings.getOrDefault(folderId, Map.of());
            }
            List<Message> messages = new ArrayList<>();
            for (var entry : stashed.entrySet()) {
                if (entry.getKey() >= startUid && entry.getKey() <= endUid) {
                    messages.add(toMessage(folderId, entry.getValue()));
                }
            }
            return new MessageListPage(messages, false, messages.size());
        }

        @Override
        public Folder refreshCounts(String folderId) {
            for (ProtonApiClient.Label label : client.labels()) {
                if (label.ID().equals(folderId)) {
                    return folderOf(label, counts());
                }
            }
            return new Folder(folderId, folderId, "", FolderType.CUSTOM, 0, 0, true, false, "proton-api");
        }
    }

    private final class Messages implements MessageService {

        @Override
        public Message getMessage(String messageId, boolean includeBody) {
            ProtonApiClient.Message remote = client.getMessage(messageId);
            if (!includeBody) {
                return toMessage(remote.LabelIDs().isEmpty() ? "" : remote.LabelIDs().get(0), remote);
            }
            MimeParser.ParsedMessage parsed = decryptedBody(remote);
            return toMessage(remote.LabelIDs().isEmpty() ? "" : remote.LabelIDs().get(0),
                    remote, parsed);
        }

        @Override
        public void setRead(String messageId, boolean read) {
            client.setRead(List.of(messageId), read);
        }

        @Override
        public void setFlagged(String messageId, boolean flagged) {
            if (flagged) {
                client.setLabel(List.of(messageId), String.valueOf(STARRED_LABEL));
            } else {
                client.unsetLabel(List.of(messageId), String.valueOf(STARRED_LABEL));
            }
        }

        @Override
        public void move(String messageId, String targetFolderId) {
            move(client.getMessage(messageId), targetFolderId);
        }

        @Override
        public void delete(String messageId) {
            ProtonApiClient.Message remote = client.getMessage(messageId);
            if (remote.LabelIDs().contains(ProtonApiClient.LABEL_TRASH)) {
                client.delete(List.of(messageId));
            } else {
                move(remote, ProtonApiClient.LABEL_TRASH);
            }
        }

        @Override
        public void markSpam(String messageId) {
            move(client.getMessage(messageId), ProtonApiClient.LABEL_SPAM);
        }

        @Override
        public void markNotSpam(String messageId) {
            move(client.getMessage(messageId), ProtonApiClient.LABEL_INBOX);
        }

        private void move(ProtonApiClient.Message remote, String targetFolderId) {
            String messageId = remote.ID();
            List<String> remove = new ArrayList<>();
            for (String labelId : remote.LabelIDs()) {
                if (FOLDER_LABELS.contains(labelId) && !labelId.equals(targetFolderId)) {
                    remove.add(labelId);
                }
            }
            for (String labelId : remove) {
                client.unsetLabel(List.of(messageId), labelId);
            }
            client.setLabel(List.of(messageId), targetFolderId);
        }

        @Override
        public String saveDraft(String draftJson) {
            throw new ProviderException(ErrorCode.DRAFT_UNSUPPORTED,
                    "Saving drafts with a Proton account is not supported yet.", false);
        }

        @Override
        public SendResult send(String payloadBase64) {
            throw new ProviderException(ErrorCode.FEATURE_UNSUPPORTED,
                    "Sending mail with a Proton account is not supported yet.", false);
        }
    }

    private final class Submission implements MessageSubmissionService {

        @Override
        public SendResult send(MessageDraft draft) {
            throw new ProviderException(ErrorCode.FEATURE_UNSUPPORTED,
                    "Sending mail with a Proton account is not supported yet.", false);
        }

        @Override
        public void saveDraft(MessageDraft draft) {
            throw new ProviderException(ErrorCode.DRAFT_UNSUPPORTED,
                    "Saving drafts with a Proton account is not supported yet.", false);
        }
    }

    private final class Attachments implements AttachmentService {

        @Override
        public InputStream open(Attachment attachment) {
            return new ByteArrayInputStream(readAttachment(attachment));
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
            } catch (Exception e) {
                throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                        "The attachment could not be written: " + e.getMessage(), true, e);
            }
        }

        private byte[] readAttachment(Attachment attachment) {
            String[] parts = attachment.id().split("\\|");
            if (parts.length != 2) {
                throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                        "The attachment reference is invalid", false);
            }
            ProtonApiClient.Message remote = client.getMessage(parts[0]);
            MimeParser.ParsedMessage parsed = decryptedBody(remote);
            for (MimeParser.Part part : parsed.attachments()) {
                if (part.path().equals(parts[1])) {
                    return part.content();
                }
            }
            throw new ProviderException(ErrorCode.ATTACHMENT_UNAVAILABLE,
                    "The attachment could not be found in the message", false);
        }
    }

    private final class Search implements SearchService {

        @Override
        public List<Message> search(SearchQuery query) {
            List<String> folders = query.folderIds().isEmpty()
                    ? List.of(ProtonApiClient.LABEL_ALL_MAIL) : query.folderIds();
            List<Message> results = new ArrayList<>();
            for (String folderId : folders) {
                List<ProtonApiClient.Message> remote =
                        client.listMessages(folderId, 0, Math.max(query.limit(), 50));
                for (ProtonApiClient.Message item : remote) {
                    Message message = toMessage(folderId, item);
                    if (!matches(message, item, query)) {
                        continue;
                    }
                    if (query.unreadOnly() && !message.unread()) {
                        continue;
                    }
                    if (query.flaggedOnly() && !message.flagged()) {
                        continue;
                    }
                    if (query.withAttachmentsOnly() && !message.hasAttachments()) {
                        continue;
                    }
                    results.add(message);
                    if (results.size() >= query.limit() && query.limit() > 0) {
                        return results;
                    }
                }
            }
            return results;
        }

        private boolean matches(Message message, ProtonApiClient.Message remote, SearchQuery query) {
            if (!query.text().isBlank()) {
                String text = query.text().toLowerCase(Locale.ROOT);
                String haystack = (message.subject() + "\n" + message.from().format() + "\n"
                        + formatAll(message.to())).toLowerCase(Locale.ROOT);
                if (!haystack.contains(text)) {
                    return false;
                }
            }
            if (!query.from().isBlank() && !message.from().format()
                    .toLowerCase(Locale.ROOT).contains(query.from().toLowerCase(Locale.ROOT))) {
                return false;
            }
            if (!query.to().isBlank()) {
                boolean toMatch = message.to().stream().anyMatch(address -> address.format()
                        .toLowerCase(Locale.ROOT).contains(query.to().toLowerCase(Locale.ROOT)));
                if (!toMatch) {
                    return false;
                }
            }
            if (!query.subject().isBlank() && !message.subject()
                    .toLowerCase(Locale.ROOT).contains(query.subject().toLowerCase(Locale.ROOT))) {
                return false;
            }
            return true;
        }

        private String formatAll(List<Address> addresses) {
            StringBuilder out = new StringBuilder();
            for (Address address : addresses) {
                out.append(address.format()).append('\n');
            }
            return out.toString();
        }

        @Override
        public boolean isServerSide() {
            return true;
        }
    }

    private final class Synchronization implements SynchronizationService {

        @Override
        public SyncResult synchronize(String folderId, FolderSyncState syncState) {
            List<ProtonApiClient.Message> remote;
            try {
                remote = client.listMessages(folderId, 0, 100);
            } catch (RuntimeException e) {
                return new SyncResult(folderId, 0, 0, 0, Instant.now(),
                        List.of("Proton messages could not be listed: " + e.getMessage()));
            }
            Map<Long, ProtonApiClient.Message> stashed = new HashMap<>();
            long highestTime = syncState.isNeverSynced() ? 0 : syncState.uidNext();
            long maxTime = highestTime;
            List<Long> fresh = new ArrayList<>();
            for (ProtonApiClient.Message item : remote) {
                long uid = uidOf(item.ID());
                stashed.put(uid, item);
                maxTime = Math.max(maxTime, item.Time());
                if (item.Time() > highestTime) {
                    fresh.add(uid);
                }
            }
            synchronized (listings) {
                listings.put(folderId, stashed);
            }
            // On the first sync every message is new to the cache, but only
            // genuinely new mail should notify.
            int freshCount = syncState.isNeverSynced() ? 0 : fresh.size();
            return new SyncResult(folderId, freshCount, 0, 0, Instant.now(), List.of(),
                    fresh, List.of(), List.of(), 1, maxTime, "");
        }

        @Override
        public boolean supportsIdle() {
            return false;
        }
    }
}
